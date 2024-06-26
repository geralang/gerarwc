
package typesafeschwalbe.gerac.compiler.types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import typesafeschwalbe.gerac.compiler.ErrorException;
import typesafeschwalbe.gerac.compiler.Error;
import typesafeschwalbe.gerac.compiler.Source;
import typesafeschwalbe.gerac.compiler.Symbols;
import typesafeschwalbe.gerac.compiler.frontend.AstNode;
import typesafeschwalbe.gerac.compiler.frontend.Namespace;

public class ConstraintGenerator {
    
    private static class Block {
        private final Map<String, TypeVariable> variables = new HashMap<>();
        private final Map<String, Boolean> initialized = new HashMap<>();
        private final Map<String, Boolean> mutable = new HashMap<>();
        private final Set<String> initializes = new HashSet<>();
        private boolean alwaysReturns = false;

        private Block() {}
    }

    private static record CallFrame(
        List<TypeVariable> arguments,
        Set<String> captures,
        TypeVariable returned,
        List<Block> blocks,
        Source source
    ) {}

    public static record VariableUsage(
        AstNode node,
        Namespace fullPath,
        TypeVariable value
    ) {}

    public static record ProcedureUsage(
        AstNode node,
        Namespace shortPath,
        List<TypeVariable> arguments,
        TypeVariable returned
    ) {}

    private final Symbols symbols;
    private final TypeContext ctx;
    private Symbols.Symbol inSymbol;
    private List<TypeConstraint> constraints;
    private List<CallFrame> stack;
    private List<VariableUsage> varUsages;
    private List<ProcedureUsage> procUsages;

    public ConstraintGenerator(Symbols symbols, TypeContext ctx) {
        this.symbols = symbols;
        this.ctx = ctx;
    }

    private void reset(Symbols.Symbol s) {
        this.inSymbol = s;
        this.constraints = new LinkedList<>();
        this.stack = new LinkedList<>();
        this.varUsages = new ArrayList<>();
        this.procUsages = new ArrayList<>();
    }

    public static record ProcOutput(
        TypeContext ctx, List<TypeConstraint> constraints,
        List<TypeVariable> arguments, TypeVariable returned,
        List<VariableUsage> varUsages, List<ProcedureUsage> procUsages
    ) {}

    public ProcOutput generateProc(
        Symbols.Symbol s, Symbols.Symbol.Procedure p
    ) throws ErrorException {
        this.reset(s);
        this.enterFrame(p.argumentNames(), s.source);
        if(p.body().isPresent()) {
            this.walkBlock(p.body().get());
        }
        CallFrame frame = this.frame();
        this.exitFrame();
        return new ProcOutput(
            this.ctx, this.constraints,
            frame.arguments, frame.returned,
            this.varUsages, this.procUsages
        );
    }

    public static record VarOutput(
        TypeContext ctx, List<TypeConstraint> constraints,
        TypeVariable value,
        List<VariableUsage> varUsages, List<ProcedureUsage> procUsages
    ) {}

    public VarOutput generateVar(
        Symbols.Symbol s, Symbols.Symbol.Variable v
    ) throws ErrorException {
        this.reset(s);
        this.enterFrame(List.of(), s.source);
        TypeVariable value;
        if(v.valueNode().isPresent()) {
            value = this.walkNode(v.valueNode().get()).get();
        } else {
            value = this.ctx.makeVar();
        }
        this.exitFrame();
        return new VarOutput(
            ctx, this.constraints, value, this.varUsages, this.procUsages
        );
    }

    private void addConstraint(TypeConstraint c) {
        this.constraints.add(c);
    }

    private void enterFrame(List<String> argumentNames, Source source) {
        this.stack.add(new CallFrame(
            new ArrayList<>(), new HashSet<>(), this.ctx.makeVar(), 
            new LinkedList<>(), source
        ));
        this.enterBlock();
        for(String argName: argumentNames) {
            TypeVariable argType = this.ctx.makeVar();
            this.frame().arguments.add(argType);
            this.block().variables.put(argName, argType);
            this.block().initialized.put(argName, true);
            this.block().mutable.put(argName, false);
        }
    }

    private CallFrame frame() {
        return this.stack.get(this.stack.size() - 1);
    }

    private void enterBlock() {
        this.frame().blocks.add(new Block());
    }

    private Block block() {
        return this.frame().blocks.get(this.frame().blocks.size() - 1);
    }

    private void exitBlock() {
        this.frame().blocks.remove(this.frame().blocks.size() - 1);
    }

    private void exitFrame() {
        if(!this.block().alwaysReturns) {
            this.addConstraint(new TypeConstraint(
                this.frame().returned, this.frame().source,
                TypeConstraint.Type.IS_TYPE,
                new TypeConstraint.IsType(
                    DataType.Type.UNIT,
                    Optional.of(
                        "because the body does not always return a value"
                    )
                )
            ));
        }
        this.stack.remove(this.stack.size() - 1);
    }

    private void handleBranches(List<Block> branches) {
        if(branches.size() == 0) {
            throw new RuntimeException("need at least one branch!");
        }
        Set<String> initializes = null;
        boolean alwaysReturns = true;
        for(Block branch: branches) {
            if(!branch.alwaysReturns) {
                if(initializes == null) {
                    initializes = branch.initializes;
                } else {
                    initializes.retainAll(branch.initializes);
                }
            }
            alwaysReturns &= branch.alwaysReturns;
        }
        if(initializes != null) {
            for(String initName: initializes) {
                if(this.block().variables.containsKey(initName)) {
                    this.block().initialized.put(initName, true);
                } else {
                    this.block().initializes.add(initName);
                }   
            }
        }
        this.block().alwaysReturns |= alwaysReturns;
    }

    private void walkBlock(List<AstNode> body) throws ErrorException {
        for(AstNode node: body) {
            this.walkNode(node);
        }
    }

    private Optional<TypeVariable> walkNode(
        AstNode node
    ) throws ErrorException {
        return this.walkNode(node, false);
    }

    private Optional<TypeVariable> walkNode(
        AstNode node, boolean assigned
    ) throws ErrorException {
        Optional<TypeVariable> t = this.walkNodeInternal(node, assigned);
        node.resultType = t;
        return t;
    }    

    private Optional<TypeVariable> walkNodeInternal(
        AstNode node, boolean assigned
    ) throws ErrorException {
        switch(node.type) {
            case CLOSURE: {
                AstNode.Closure data = node.getValue();
                TypeVariable value = this.ctx.makeVar();
                this.enterFrame(data.argumentNames(), node.source);
                this.walkBlock(data.body());
                CallFrame frame = this.frame();
                this.exitFrame();
                data.captures().set(Optional.of(new HashMap<>()));
                for(String capture: frame.captures) {
                    TypeVariable captureVar = null;
                    for(
                        int frameI = this.stack.size() - 1; 
                        frameI >= 0; frameI -= 1
                    ) {
                        CallFrame varFrame = this.stack.get(frameI);
                        for(
                            int blockI = varFrame.blocks.size() - 1;
                            blockI >= 0; blockI -= 1
                        ) {
                            Block varBlock = varFrame.blocks.get(blockI);
                            if(!varBlock.variables.containsKey(capture)) {
                                continue;
                            }
                            captureVar = varBlock.variables.get(capture);
                            break;
                        }
                        if(captureVar != null) {
                            break;
                        }
                    }
                    data.captures().get().get().put(capture, captureVar);
                }
                data.argumentTypes().set(Optional.of(new ArrayList<>()));
                data.argumentTypes().get().get().addAll(frame.arguments);
                data.returnType().set(Optional.of(frame.returned));
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.HAS_SIGNATURE,
                    new TypeConstraint.HasSignature(
                        frame.arguments, frame.returned
                    )
                ));
                return Optional.of(value);
            }
            case VARIABLE: {
                AstNode.Variable data = node.getValue();
                TypeVariable value = data.value().isPresent()
                    ? this.walkNode(data.value().get()).get()
                    : this.ctx.makeVar();
                data.valueType().set(Optional.of(value));
                this.block().variables
                    .put(data.name(), value);
                this.block().initialized
                    .put(data.name(), data.value().isPresent());
                this.block().mutable
                    .put(data.name(), data.isMutable());
                return Optional.empty();
            }
            case CASE_BRANCHING: {
                AstNode.CaseBranching data = node.getValue();
                TypeVariable value = this.walkNode(data.value()).get();
                List<Block> branches = new ArrayList<>();
                for(
                    int branchI = 0; branchI < data.branchBodies().size(); 
                    branchI += 1
                ) {
                    List<CallFrame> prevStack = this.stack;
                    this.stack = new LinkedList<>();
                    TypeVariable branchValue = this
                        .walkNode(data.branchValues().get(branchI)).get();
                    this.stack = prevStack;
                    this.addConstraint(new TypeConstraint(
                        value, data.value().source,
                        TypeConstraint.Type.UNIFY,
                        new TypeConstraint.Unify(branchValue)
                    ));
                    this.enterBlock();
                    this.walkBlock(data.branchBodies().get(branchI));
                    branches.add(this.block());
                    this.exitBlock();
                }
                this.enterBlock();
                this.walkBlock(data.elseBody());
                branches.add(this.block());
                this.exitBlock();
                this.handleBranches(branches);
                return Optional.empty();
            }
            case CASE_CONDITIONAL: {
                AstNode.CaseConditional data = node.getValue();
                TypeVariable condition = this.walkNode(data.condition()).get();
                this.addConstraint(new TypeConstraint(
                    condition, data.condition().source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.BOOLEAN,
                        Optional.of("since it's used as a condition")
                    )
                ));
                List<Block> branches = new ArrayList<>();
                this.enterBlock();
                this.walkBlock(data.ifBody());
                branches.add(this.block());
                this.exitBlock();
                this.enterBlock();
                this.walkBlock(data.elseBody());
                branches.add(this.block());
                this.exitBlock();
                this.handleBranches(branches);
                return Optional.empty();
            }
            case CASE_VARIANT: {
                AstNode.CaseVariant data = node.getValue();
                TypeVariable value = this.walkNode(data.value()).get();
                List<Block> branches = new ArrayList<>();
                for(
                    int branchI = 0; branchI < data.branchBodies().size(); 
                    branchI += 1
                ) {
                    String variant = data.branchVariants().get(branchI);
                    Optional<String> branchVarName = data.branchVariableNames()
                        .get(branchI);
                    TypeVariable variantType = this.ctx.makeVar();
                    this.addConstraint(new TypeConstraint(
                        value, data.value().source,
                        TypeConstraint.Type.HAS_VARIANT,
                        new TypeConstraint.HasVariant(variant, variantType)
                    ));
                    this.enterBlock();
                    if(branchVarName.isPresent()) {
                        this.block().variables
                            .put(branchVarName.get(), variantType);
                        this.block().initialized
                            .put(branchVarName.get(), true);
                        this.block().mutable
                            .put(branchVarName.get(), false);
                    }
                    this.walkBlock(data.branchBodies().get(branchI));
                    branches.add(this.block());
                    this.exitBlock();
                }
                if(data.elseBody().isPresent()) {
                    this.enterBlock();
                    this.walkBlock(data.elseBody().get());
                    branches.add(this.block());
                    this.exitBlock();
                } else {                    
                    this.addConstraint(new TypeConstraint(
                        value, data.value().source,
                        TypeConstraint.Type.LIMIT_VARIANTS,
                        new TypeConstraint.LimitVariants(
                            data.branchVariants().stream()
                                .collect(Collectors.toSet())
                        )
                    ));
                }
                this.handleBranches(branches);
                return Optional.empty();
            }
            case ASSIGNMENT: {
                AstNode.BiOp data = node.getValue();
                // right is done first before left marks anything as assigned
                TypeVariable right = this.walkNode(data.right()).get();
                TypeVariable left = this.walkNode(data.left(), true).get();
                this.addConstraint(new TypeConstraint(
                    left, node.source,
                    TypeConstraint.Type.UNIFY,
                    new TypeConstraint.Unify(right)
                ));
                return Optional.empty();
            }
            case RETURN: {
                AstNode.MonoOp data = node.getValue();
                TypeVariable value = this.walkNode(data.value()).get();
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.UNIFY,
                    new TypeConstraint.Unify(this.frame().returned)
                ));
                this.block().alwaysReturns = true;
                return Optional.empty();
            }
            case CALL: {
                AstNode.Call data = node.getValue();
                boolean isProcCall = false;
                if(data.called().type == AstNode.Type.MODULE_ACCESS) {
                    isProcCall = true;
                    Namespace called = data.called()
                        .<AstNode.ModuleAccess>getValue().path();
                    if(called.elements().size() == 1) {
                        String name = called.elements().get(0);
                        for(CallFrame frame: this.stack) {
                            for(Block block: frame.blocks) {
                                if(!block.variables.containsKey(name)) {
                                    continue;
                                }
                                isProcCall = false;
                                break;
                            }
                            if(!isProcCall) { break; }
                        }
                    }
                }
                List<TypeVariable> arguments = new ArrayList<>();
                for(AstNode argument: data.arguments()) {
                    arguments.add(this.walkNode(argument).get());
                }
                TypeVariable returned = this.ctx.makeVar();
                if(isProcCall) {
                    Namespace shortPath = data.called()
                        .<AstNode.ModuleAccess>getValue().path();
                    this.procUsages.add(
                        new ProcedureUsage(node, shortPath, arguments, returned)
                    );
                } else {
                    TypeVariable called = this.walkNode(data.called()).get();
                    this.addConstraint(new TypeConstraint(
                        called, data.called().source,
                        TypeConstraint.Type.HAS_SIGNATURE,
                        new TypeConstraint.HasSignature(arguments, returned)
                    ));
                }
                return Optional.of(returned);
            }
            case METHOD_CALL: {
                AstNode.MethodCall data = node.getValue();
                TypeVariable accessed = this.walkNode(data.called()).get();
                TypeVariable called = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    accessed, data.called().source,
                    TypeConstraint.Type.HAS_MEMBER,
                    new TypeConstraint.HasMember(data.memberName(), called)
                ));
                List<TypeVariable> arguments = new ArrayList<>();
                arguments.add(accessed);
                for(AstNode argument: data.arguments()) {
                    arguments.add(this.walkNode(argument).get());
                }
                TypeVariable returned = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    called, node.source,
                    TypeConstraint.Type.HAS_SIGNATURE,
                    new TypeConstraint.HasSignature(arguments, returned)
                ));
                return Optional.of(returned);
            }
            case OBJECT_LITERAL: {
                AstNode.ObjectLiteral data = node.getValue();
                TypeVariable result = this.ctx.makeVar();
                for(String memberName: data.values().keySet()) {
                    TypeVariable member = this
                        .walkNode(data.values().get(memberName)).get();
                    this.addConstraint(new TypeConstraint(
                        result, node.source,
                        TypeConstraint.Type.HAS_MEMBER,
                        new TypeConstraint.HasMember(memberName, member)
                    ));
                }
                this.addConstraint(new TypeConstraint(
                    result, node.source,
                    TypeConstraint.Type.LIMIT_MEMBERS,
                    new TypeConstraint.LimitMembers(data.values().keySet())
                ));
                return Optional.of(result);
            }
            case ARRAY_LITERAL: {
                AstNode.ArrayLiteral data = node.getValue();
                TypeVariable element = this.ctx.makeVar();
                for(AstNode valueNode: data.values()) {
                    TypeVariable value = this.walkNode(valueNode).get();
                    this.addConstraint(new TypeConstraint(
                        value, node.source,
                        TypeConstraint.Type.UNIFY,
                        new TypeConstraint.Unify(element)
                    ));
                }
                TypeVariable result = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    result, node.source,
                    TypeConstraint.Type.HAS_ELEMENT,
                    new TypeConstraint.HasElement(element)
                ));
                return Optional.of(result);
            }
            case REPEATING_ARRAY_LITERAL: {
                AstNode.BiOp data = node.getValue();
                TypeVariable value = this.walkNode(data.left()).get();
                TypeVariable result = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    result, node.source,
                    TypeConstraint.Type.HAS_ELEMENT,
                    new TypeConstraint.HasElement(value)
                ));
                TypeVariable size = this.walkNode(data.right()).get();
                this.addConstraint(new TypeConstraint(
                    size, data.right().source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.INTEGER, 
                        Optional.of("since it's used as the size for an array")
                    )
                ));
                return Optional.of(result);
            }
            case OBJECT_ACCESS: {
                AstNode.ObjectAccess data = node.getValue();
                TypeVariable accessed = this.walkNode(data.accessed()).get();
                TypeVariable member = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    accessed, data.accessed().source,
                    TypeConstraint.Type.HAS_MEMBER,
                    new TypeConstraint.HasMember(data.memberName(), member)
                ));
                return Optional.of(member);
            }
            case ARRAY_ACCESS: {
                AstNode.BiOp data = node.getValue();
                TypeVariable accessed = this.walkNode(data.left()).get();
                TypeVariable element = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    accessed, data.left().source,
                    TypeConstraint.Type.HAS_ELEMENT,
                    new TypeConstraint.HasElement(element)
                ));
                TypeVariable index = this.walkNode(data.right()).get();
                this.addConstraint(new TypeConstraint(
                    index, data.right().source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.INTEGER,
                        Optional.of("since it's used to index into an array")
                    )
                ));
                return Optional.of(element);
            }
            case BOOLEAN_LITERAL: {
                TypeVariable value = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.BOOLEAN, Optional.empty()
                    )
                ));
                return Optional.of(value);
            }
            case INTEGER_LITERAL: {
                TypeVariable value = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.INTEGER, Optional.empty()
                    )
                ));
                return Optional.of(value);
            }
            case FLOAT_LITERAL: {
                TypeVariable value = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.FLOAT, Optional.empty()
                    )
                ));
                return Optional.of(value);
            }
            case STRING_LITERAL: {
                TypeVariable value = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.STRING, Optional.empty()
                    )
                ));
                return Optional.of(value);
            }
            case UNIT_LITERAL: {
                TypeVariable value = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    value, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.UNIT, Optional.empty()
                    )
                ));
                return Optional.of(value);
            }
            case ADD:
            case SUBTRACT: 
            case MULTIPLY:
            case DIVIDE:
            case MODULO: {
                AstNode.BiOp data = node.getValue();
                TypeVariable left = this.walkNode(data.left()).get();
                TypeVariable right = this.walkNode(data.right()).get();
                this.addConstraint(new TypeConstraint(
                    left, data.left().source,
                    TypeConstraint.Type.IS_NUMERIC,
                    null
                ));
                this.addConstraint(new TypeConstraint(
                    left, node.source,
                    TypeConstraint.Type.UNIFY,
                    new TypeConstraint.Unify(right)
                ));
                return Optional.of(left);
            }
            case NEGATE: {
                AstNode.MonoOp data = node.getValue();
                TypeVariable value = this.walkNode(data.value()).get();
                this.addConstraint(new TypeConstraint(
                    value, data.value().source,
                    TypeConstraint.Type.IS_NUMERIC,
                    null
                ));
                return Optional.of(value);
            }
            case LESS_THAN:
            case GREATER_THAN:
            case LESS_THAN_EQUAL:
            case GREATER_THAN_EQUAL: {
                AstNode.BiOp data = node.getValue();
                TypeVariable left = this.walkNode(data.left()).get();
                TypeVariable right = this.walkNode(data.right()).get();
                this.addConstraint(new TypeConstraint(
                    left, data.left().source,
                    TypeConstraint.Type.IS_NUMERIC,
                    null
                ));
                this.addConstraint(new TypeConstraint(
                    left, node.source,
                    TypeConstraint.Type.UNIFY,
                    new TypeConstraint.Unify(right)
                ));
                TypeVariable result = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    result, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.BOOLEAN, Optional.empty()
                    )
                ));
                return Optional.of(result);
            }
            case EQUALS:
            case NOT_EQUALS: {
                AstNode.BiOp data = node.getValue();
                TypeVariable left = this.walkNode(data.left()).get();
                TypeVariable right = this.walkNode(data.right()).get();
                this.addConstraint(new TypeConstraint(
                    left, node.source,
                    TypeConstraint.Type.UNIFY,
                    new TypeConstraint.Unify(right)
                ));
                TypeVariable result = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    result, node.source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.BOOLEAN, Optional.empty()
                    )
                ));
                return Optional.of(result);
            }
            case NOT: {
                AstNode.MonoOp data = node.getValue();
                TypeVariable value = this.walkNode(data.value()).get();
                this.addConstraint(new TypeConstraint(
                    value, data.value().source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.BOOLEAN, Optional.empty()
                    )
                ));
                return Optional.of(value);
            }
            case OR:
            case AND: {
                AstNode.BiOp data = node.getValue();
                TypeVariable left = this.walkNode(data.left()).get();
                TypeVariable right = this.walkNode(data.right()).get();
                this.addConstraint(new TypeConstraint(
                    left, data.left().source,
                    TypeConstraint.Type.IS_TYPE,
                    new TypeConstraint.IsType(
                        DataType.Type.BOOLEAN, Optional.empty()
                    )
                ));
                this.addConstraint(new TypeConstraint(
                    left, node.source,
                    TypeConstraint.Type.UNIFY,
                    new TypeConstraint.Unify(right)
                ));
                return Optional.of(left);
            }
            case MODULE_ACCESS: {
                AstNode.ModuleAccess data = node.getValue();
                if(data.path().elements().size() == 1) {
                    String name = data.path().elements().get(0);
                    boolean initialized = false;
                    for(
                        int frameI = this.stack.size() - 1; 
                        frameI >= 0; frameI -= 1
                    ) {
                        CallFrame frame = this.stack.get(frameI);
                        for(
                            int blockI = frame.blocks.size() - 1;
                            blockI >= 0; blockI -= 1
                        ) {
                            Block block = frame.blocks.get(blockI);
                            initialized |= block.initializes.contains(name);
                            if(!block.variables.containsKey(name)) {
                                continue;
                            }
                            initialized |= block.initialized.get(name);
                            boolean mutable = block.mutable.get(name);
                            if(!assigned && !initialized) {
                                throw new ErrorException(new Error(
                                    "Usage of possibly uninitialized variable",
                                    Error.Marking.error(
                                        node.source, 
                                        "this variable might"
                                            + " not always be initialized"
                                    )
                                ));
                            }
                            if(assigned && initialized && !mutable) {
                                throw new ErrorException(new Error(
                                    "Assignment to an immutable variable",
                                    Error.Marking.error(
                                        node.source, 
                                        "this variable has not been"
                                            + " declared as mutable"
                                    )
                                ));
                            }
                            if(this.block() == block) {
                                if(assigned) {
                                    block.initialized.put(name, true);
                                }
                            } else {
                                if(!initialized && assigned) {
                                    this.block().initializes.add(name);
                                }
                                for(
                                    int mFrameI = this.stack.size() - 1; 
                                    mFrameI > frameI; mFrameI -= 1
                                ) {
                                    this.stack.get(mFrameI).captures.add(name);
                                }
                            }
                            return Optional.of(block.variables.get(name));
                        }
                    }
                }
                List<Namespace> fullPaths = this.symbols.allowedPathExpansions(
                    data.path(), this.inSymbol, node.source
                );
                Namespace fullPath = fullPaths.size() > 0
                    ? fullPaths.get(fullPaths.size() - 1)
                    : data.path();
                Optional<Symbols.Symbol> accessed = this.symbols.get(fullPath);
                if(accessed.isEmpty()) {
                    throw new ErrorException(new Error(
                        "Access to unknown symbol",
                        Error.Marking.error(
                            node.source,
                            "'" + fullPath + "' is not a known symbol"
                        )
                    ));
                }
                switch(accessed.get().type) {
                    case VARIABLE: {
                        if(assigned) {
                            throw new ErrorException(new Error(
                                "Assignment to an immutable variable",
                                Error.Marking.error(
                                    node.source, 
                                    "this variable has not been"
                                        + " declared as mutable"
                                )
                            ));
                        }
                        TypeVariable value = this.ctx.makeVar();
                        this.varUsages.add(
                            new VariableUsage(node, fullPath, value)
                        );
                        return Optional.of(value);
                    }
                    case PROCEDURE: {
                        if(assigned) {
                            throw new ErrorException(new Error(
                                "Assignment to a procedure",
                                Error.Marking.error(
                                    node.source, 
                                    "procedures cannot be assigned to"
                                )
                            ));
                        }
                        Symbols.Symbol.Procedure symbolData = accessed.get()
                            .getValue();
                        List<TypeVariable> arguments = new ArrayList<>();
                        for(
                            int i = 0; i < symbolData.argumentNames().size(); 
                            i += 1
                        ) {
                            arguments.add(this.ctx.makeVar());
                        }
                        TypeVariable returns = this.ctx.makeVar();
                        this.procUsages.add(new ProcedureUsage(
                            node, fullPath, arguments, returns
                        ));
                        TypeVariable result = this.ctx.makeVar();
                        this.addConstraint(new TypeConstraint(
                            result, node.source,
                            TypeConstraint.Type.HAS_SIGNATURE,
                            new TypeConstraint.HasSignature(arguments, returns)
                        ));
                        return Optional.of(result);
                    }
                    default:
                        throw new RuntimeException("unhandled symbol type!");
                }
            }
            case VARIANT_LITERAL: {
                AstNode.VariantLiteral data = node.getValue();
                TypeVariable value = this.walkNode(data.value()).get();
                TypeVariable variant = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    variant, node.source,
                    TypeConstraint.Type.HAS_VARIANT,
                    new TypeConstraint.HasVariant(data.variantName(), value)
                ));
                return Optional.of(variant);
            }
            case VARIANT_UNWRAP: {
                AstNode.VariantUnwrap data = node.getValue();
                TypeVariable unwrapped = this.walkNode(data.unwrapped()).get();
                TypeVariable value = this.ctx.makeVar();
                this.addConstraint(new TypeConstraint(
                    unwrapped, node.source,
                    TypeConstraint.Type.HAS_VARIANT,
                    new TypeConstraint.HasVariant(data.variantName(), value)
                ));
                this.addConstraint(new TypeConstraint(
                    this.frame().returned, node.source,
                    TypeConstraint.Type.VARIANTS_OF_EXCEPT,
                    new TypeConstraint.VariantsOfExcept(
                        unwrapped, data.variantName()
                    )
                ));
                return Optional.of(value);
            }
            case STATIC: {
                AstNode.MonoOp data = node.getValue();
                List<CallFrame> prevStack = this.stack;
                this.stack = new LinkedList<>();
                TypeVariable value = this.walkNode(data.value()).get();
                this.stack = prevStack;
                return Optional.of(value);
            }
            case PROCEDURE:
            case PROCEDURE_CALL:
            case VARIABLE_ACCESS:
            case TARGET:
            case USE:
            case MODULE_DECLARATION: {
                throw new RuntimeException("should not be encountered!");
            }
            default: {
                throw new RuntimeException("unhandled node type!");
            }
        }
    }



}
