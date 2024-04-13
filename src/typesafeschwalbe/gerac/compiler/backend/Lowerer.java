package typesafeschwalbe.gerac.compiler.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import typesafeschwalbe.gerac.compiler.Error;
import typesafeschwalbe.gerac.compiler.ErrorException;
import typesafeschwalbe.gerac.compiler.Symbols;
import typesafeschwalbe.gerac.compiler.backend.Ir.StaticValue;
import typesafeschwalbe.gerac.compiler.frontend.AstNode;
import typesafeschwalbe.gerac.compiler.frontend.DataType;
import typesafeschwalbe.gerac.compiler.frontend.Namespace;

public class Lowerer {
    
    private static record BlockVariables(
        Map<String, Ir.Variable> variables,
        Map<String, Integer> lastUpdates
    ) {
        @Override
        public BlockVariables clone() {
            return new BlockVariables(
                new HashMap<>(this.variables), 
                new HashMap<>(this.lastUpdates)
            );
        }
    }

    private final Symbols symbols;
    private final Interpreter interpreter;

    private final Ir.StaticValues staticValues;
    private Ir.Context context;
    private final List<List<Ir.Instr>> blockStack;
    private final List<BlockVariables> variableStack;

    public Lowerer(
        Map<String, String> sourceFiles,
        Symbols symbols
    ) {
        this.symbols = symbols;
        this.interpreter = new Interpreter(sourceFiles, symbols);
        this.staticValues = new Ir.StaticValues();
        this.blockStack = new LinkedList<>();
        this.variableStack = new LinkedList<>();
    }

    public Optional<Error> lowerProcedures() {
        for(Namespace symbolPath: this.symbols.allSymbolPaths()) {
            Symbols.Symbol symbol = this.symbols.get(symbolPath).get();
            if(symbol.type != Symbols.Symbol.Type.PROCEDURE) { continue; }
            Symbols.Symbol.Procedure symbolData = symbol.getValue();
            if(symbolData.body().isEmpty()) { continue; }
            for(
                int variantI = 0;
                variantI < symbol.variantCount();
                variantI += 1
            ) {
                Symbols.Symbol.Procedure variant = symbol.getVariant(variantI);
                this.context = new Ir.Context();
                this.enterBlock();
                for(
                    int argI = 0; 
                    argI < variant.argumentNames().size(); 
                    argI += 1
                ) {
                    String argName = variant.argumentNames().get(argI);
                    DataType argType = variant.argumentTypes().get().get(argI);
                    Ir.Variable variable = this.context
                        .allocateArgument(argType);
                    this.variables().variables.put(argName, variable);
                    this.variables().lastUpdates.put(argName, variable.version);
                }
                List<Ir.Instr> body;
                try {
                    this.lowerNodes(
                        variant.body().get()
                    );
                    body = this.exitBlock();
                } catch(ErrorException e) {
                    this.exitBlock();
                    return Optional.of(e.error);
                }
                // DEBUG //
                for(Ir.Instr i: body) {
                    System.out.println(i);
                }
                ///////////
                symbol.setVariant(
                    variantI,
                    new Symbols.Symbol.LoweredProcedure(
                        variant.argumentTypes().get(),
                        this.context, body
                    )
                );
            }
        }
        return Optional.empty();
    }

    private void enterBlock() {
        this.blockStack.add(new ArrayList<>());
        this.variableStack.add(
            this.variableStack.size() > 0
                ? this.variables().clone()
                : new BlockVariables(new HashMap<>(), new HashMap<>())
        );
    }

    private List<Ir.Instr> block() {
        return this.blockStack.get(this.blockStack.size() - 1);
    } 

    private BlockVariables variables() {
        return this.variableStack.get(this.variableStack.size() - 1);
    }

    private List<Ir.Instr> exitBlock() {
        List<Ir.Instr> block = this.block();
        this.blockStack.remove(this.blockStack.size() - 1);
        this.variableStack.remove(this.variableStack.size() - 1);
        return block;
    }

    private void addPhi(
        List<BlockVariables> branchVariables
    ) {
        for(String name: this.variables().variables.keySet()) {
            Ir.Variable variable = this.variables().variables.get(name);
            int ogVersion = this.variables().lastUpdates.get(name);
            Set<Integer> versions = new HashSet<>();
            for(
                int branchI = 0; branchI < branchVariables.size(); branchI += 1
            ) {
                BlockVariables branch = branchVariables.get(branchI);
                Integer bVersion = branch.lastUpdates.get(name);
                versions.add(bVersion != null? bVersion : ogVersion);
            }
            System.out.println(versions);
            List<Ir.Variable> options = versions.stream()
                .map(v -> new Ir.Variable(variable.index, v)).toList();
            variable.version += 1;
            this.block().add(new Ir.Instr(
                Ir.Instr.Type.PHI,
                options,
                null,
                Optional.of(variable.clone())
            ));
            this.variables().lastUpdates.put(name, variable.version);
        }
    }

    private void lowerNodes(
        List<AstNode> nodes
    ) throws ErrorException {
        for(AstNode node: nodes) {
            this.lowerNode(node);
        }
    }

    private Optional<Ir.Variable> lowerNode(
        AstNode node
    ) throws ErrorException {
        switch(node.type) {
            case CLOSURE: {
                AstNode.Closure data = node.getValue();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                if(data.captures().isEmpty()) {
                    this.block().add(new Ir.Instr(
                        Ir.Instr.Type.LOAD_EMPTY_CLOSURE,
                        List.of(), null,
                        Optional.of(dest)
                    ));
                    return Optional.of(dest);
                }
                List<String> captureNames = new ArrayList<>();
                List<Ir.Variable> captureValues = new ArrayList<>();
                for(String captureName: data.captures().get().keySet()) {
                    captureNames.add(captureName);
                    if(this.variables().variables.containsKey(captureName)) {
                        Ir.Variable var = this.variables().variables
                            .get(captureName).clone();
                        captureValues.add(var);
                        this.context.markCaptured(var);
                    } else {
                        Ir.Variable value = this.context
                            .allocate(data.captures().get().get(captureName));
                        this.block().add(new Ir.Instr(
                            Ir.Instr.Type.READ_CAPTURE,
                            List.of(), 
                            new Ir.Instr.CaptureAccess(captureName),
                            Optional.of(value)
                        ));
                        captureValues.add(value);
                    }
                }
                Ir.Context prevContext = this.context;
                this.context = new Ir.Context();
                this.enterBlock();
                for(
                    int argI = 0; 
                    argI < data.argumentNames().size(); 
                    argI += 1
                ) {
                    String argName = data.argumentNames().get(argI);
                    DataType argType = data.argumentTypes().get().get(argI);
                    Ir.Variable variable = this.context
                        .allocateArgument(argType);
                    this.variables().variables.put(argName, variable);
                    this.variables().lastUpdates.put(argName, variable.version);
                }
                this.lowerNodes(data.body().get());
                List<Ir.Instr> body = this.exitBlock();
                Ir.Context context = this.context;
                this.context = prevContext;
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_CLOSURE,
                    captureValues,
                    new Ir.Instr.LoadClosure(
                        data.argumentTypes().get(), data.returnType().get(), 
                        captureNames, context, body
                    ), 
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case VARIABLE: {
                AstNode.Variable data = node.getValue();
                Ir.Variable value = this.lowerNode(data.value().get()).get();
                this.variables().variables.put(data.name(), value);
                this.variables().lastUpdates.put(data.name(), value.version);
                return Optional.empty();
            }
            case CASE_BRANCHING: {
                AstNode.CaseBranching data = node.getValue();
                Ir.Variable value = this.lowerNode(data.value()).get();
                List<BlockVariables> branches = new ArrayList<>();
                List<StaticValue> branchValues = new ArrayList<>();
                List<List<Ir.Instr>> branchBodies = new ArrayList<>();
                for(
                    int branchI = 0; 
                    branchI < data.branchBodies().size(); 
                    branchI += 1
                ) {
                    Value branchValue = this.interpreter.evaluateNode(
                        data.branchValues().get(branchI)
                    );
                    branchValues.add(this.staticValues.add(branchValue));
                    this.enterBlock();
                    this.lowerNodes(data.branchBodies().get(branchI));
                    branches.add(this.variables());
                    branchBodies.add(this.exitBlock());
                }
                this.enterBlock();
                this.lowerNodes(data.elseBody());
                branches.add(this.variables());
                List<Ir.Instr> elseBody = this.exitBlock();
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.BRANCH_ON_VALUE,
                    List.of(value),
                    new Ir.Instr.BranchOnValue(
                        branchValues, 
                        branchBodies, 
                        elseBody
                    ),
                    Optional.empty()
                ));
                this.addPhi(branches);
                return Optional.empty();
            }
            case CASE_CONDITIONAL: {
                AstNode.CaseConditional data = node.getValue();
                Ir.Variable condition = this.lowerNode(data.condition()).get();
                this.enterBlock();
                this.lowerNodes(data.ifBody());
                BlockVariables ifVars = this.variables();
                List<Ir.Instr> ifBody = this.exitBlock();
                this.enterBlock();;
                this.lowerNodes(data.elseBody());
                BlockVariables elseVars = this.variables();
                List<Ir.Instr> elseBody = this.exitBlock();
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.BRANCH_ON_VALUE,
                    List.of(condition),
                    new Ir.Instr.BranchOnValue(
                        List.of(this.staticValues.add(new Value.Bool(true))), 
                        List.of(ifBody), 
                        elseBody
                    ),
                    Optional.empty()
                ));
                this.addPhi(List.of(ifVars, elseVars));
                return Optional.empty();
            }
            case CASE_VARIANT: {
                AstNode.CaseVariant data = node.getValue();
                Ir.Variable value = this.lowerNode(data.value()).get();
                DataType.Union valueVariants = data.value()
                    .resultType.get().getValue();
                List<BlockVariables> branches = new ArrayList<>();
                List<Optional<Ir.Variable>> branchVariables = new ArrayList<>();
                List<List<Ir.Instr>> branchBodies = new ArrayList<>();
                for(
                    int branchI = 0;
                    branchI < data.branchBodies().size();
                    branchI += 1
                ) {
                    String variantName = data.branchVariants().get(branchI);
                    DataType variantType = valueVariants.variants()
                        .get(variantName);
                    this.enterBlock();
                    if(data.branchVariableNames().get(branchI).isPresent()) {
                        String bVarName = data.branchVariableNames()
                            .get(branchI).get();
                        Ir.Variable bVar = this.context.allocate(variantType);
                        this.variables().variables.put(bVarName, bVar);
                        this.variables().lastUpdates.put(
                            bVarName, bVar.version
                        );
                        branchVariables.add(Optional.of(bVar.clone()));
                    } else {
                        branchVariables.add(Optional.empty());
                    }
                    this.lowerNodes(data.branchBodies().get(branchI));
                    branches.add(this.variables());
                    branchBodies.add(this.exitBlock());
                }
                this.enterBlock();
                this.lowerNodes(data.elseBody().orElse(List.of()));
                branches.add(this.variables());
                List<Ir.Instr> elseBody = this.exitBlock();
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.BRANCH_ON_VARIANT,
                    List.of(value),
                    new Ir.Instr.BranchOnVariant(
                        data.branchVariants(), branchVariables,
                        branchBodies, elseBody
                    ),
                    Optional.empty()
                ));
                this.addPhi(branches);
                return Optional.empty();
            }
            case ASSIGNMENT: {
                AstNode.BiOp data = node.getValue();
                Ir.Variable value = this.lowerNode(data.right()).get();
                switch(data.left().type) {
                    case OBJECT_ACCESS: {
                        AstNode.ObjectAccess accessData = data.left()
                            .getValue();
                        Ir.Variable accessed = this
                            .lowerNode(accessData.accessed()).get();
                        this.block().add(new Ir.Instr(
                            Ir.Instr.Type.WRITE_OBJECT,
                            List.of(accessed, value),
                            new Ir.Instr.ObjectAccess(accessData.memberName()), 
                            Optional.empty()
                        ));
                    } break;
                    case ARRAY_ACCESS: {
                        AstNode.BiOp accessData = data.left()
                            .getValue();
                        Ir.Variable accessed = this
                            .lowerNode(accessData.left()).get();
                        Ir.Variable index = this
                            .lowerNode(accessData.right()).get();
                        this.block().add(new Ir.Instr(
                            Ir.Instr.Type.WRITE_ARRAY,
                            List.of(accessed, index, value),
                            null, 
                            Optional.empty()
                        ));
                    } break;
                    case VARIABLE_ACCESS: {
                        AstNode.VariableAccess accessData = data.left()
                            .getValue();
                        boolean isLocal = this.variables().variables
                            .containsKey(accessData.variableName());
                        if(isLocal) {
                            Ir.Variable accessed = this.variables()
                                .variables.get(accessData.variableName());
                            accessed.version += 1;
                            this.variables().lastUpdates.put(
                                accessData.variableName(), accessed.version
                            );
                            this.block().add(new Ir.Instr(
                                Ir.Instr.Type.COPY,
                                List.of(accessed.clone(), value),
                                null, 
                                Optional.empty()
                            ));
                        } else {
                            this.block().add(new Ir.Instr(
                                Ir.Instr.Type.WRITE_CAPTURE,
                                List.of(value),
                                new Ir.Instr.CaptureAccess(
                                    accessData.variableName()
                                ),
                                Optional.empty()
                            ));
                        }
                    } break;
                    default: {
                        throw new RuntimeException("unhandled node type!");
                    }
                }
                return Optional.empty();
            }
            case RETURN: {
                AstNode.MonoOp data = node.getValue();
                Ir.Variable value = this.lowerNode(data.value()).get();
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.RETURN,
                    List.of(value),
                    null,
                    Optional.empty()
                ));
                return Optional.empty();
            }
            case CALL: {
                AstNode.Call data = node.getValue();
                Ir.Variable called = this.lowerNode(data.called()).get();
                List<Ir.Variable> arguments = new ArrayList<>();
                arguments.add(called);
                for(AstNode argument: data.arguments()) {
                    arguments.add(this.lowerNode(argument).get());
                }
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.CALL_CLOSURE,
                    arguments,
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case PROCEDURE_CALL: {
                AstNode.ProcedureCall data = node.getValue();
                List<Ir.Variable> arguments = new ArrayList<>();
                for(AstNode argument: data.arguments()) {
                    arguments.add(this.lowerNode(argument).get());
                }
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.CALL_PROCEDURE,
                    arguments,
                    new Ir.Instr.CallProcedure(data.path(), data.variant()),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case METHOD_CALL: {
                AstNode.MethodCall data = node.getValue();
                Ir.Variable accessed = this.lowerNode(data.called()).get();
                DataType.UnorderedObject accessedObject = data.called()
                    .resultType.get().getValue(); 
                DataType calledType = accessedObject.memberTypes()
                    .get(data.memberName());
                Ir.Variable called = this.context.allocate(calledType);
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.READ_OBJECT,
                    List.of(accessed),
                    new Ir.Instr.ObjectAccess(data.memberName()),
                    Optional.of(called)
                ));
                List<Ir.Variable> arguments = new ArrayList<>();
                arguments.add(accessed);
                for(AstNode argument: data.arguments()) {
                    arguments.add(this.lowerNode(argument).get());
                }
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.CALL_CLOSURE,
                    arguments,
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case OBJECT_LITERAL: {
                AstNode.ObjectLiteral data = node.getValue();
                List<String> names = new ArrayList<>();
                List<Ir.Variable> values = new ArrayList<>();
                for(String member: data.values().keySet()) {
                    names.add(member);
                    values.add(this.lowerNode(data.values().get(member)).get());
                }
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_OBJECT,
                    values,
                    new Ir.Instr.LoadObject(names),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case ARRAY_LITERAL: {
                AstNode.ArrayLiteral data = node.getValue();
                List<Ir.Variable> values = new ArrayList<>();
                for(AstNode value: data.values()) {
                    values.add(this.lowerNode(value).get());
                }
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_FIXED_ARRAY,
                    values,
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case REPEATING_ARRAY_LITERAL: {
                AstNode.BiOp data = node.getValue();
                Ir.Variable value = this.lowerNode(data.left()).get();
                Ir.Variable size = this.lowerNode(data.right()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_REPEAT_ARRAY,
                    List.of(value, size),
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case OBJECT_ACCESS: {
                AstNode.ObjectAccess data = node.getValue();
                Ir.Variable accessed = this.lowerNode(data.accessed()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.READ_OBJECT,
                    List.of(accessed), 
                    new Ir.Instr.ObjectAccess(data.memberName()), 
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case ARRAY_ACCESS: {
                AstNode.BiOp data = node.getValue();
                Ir.Variable accessed = this.lowerNode(data.left()).get();
                Ir.Variable index = this.lowerNode(data.right()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.READ_ARRAY,
                    List.of(accessed, index), 
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case VARIABLE_ACCESS: {
                AstNode.VariableAccess data = node.getValue();
                if(this.variables().variables.containsKey(data.variableName())) {
                    return Optional.of(
                        this.variables().variables.get(data.variableName())
                            .clone()
                    );
                }
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.READ_CAPTURE,
                    List.of(), 
                    new Ir.Instr.CaptureAccess(data.variableName()),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case BOOLEAN_LITERAL: {
                AstNode.SimpleLiteral data = node.getValue();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                boolean value = Boolean.valueOf(data.value());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_BOOLEAN,
                    List.of(), new Ir.Instr.LoadBoolean(value),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case INTEGER_LITERAL: {
                AstNode.SimpleLiteral data = node.getValue();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                long value = Long.valueOf(data.value());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_INTEGER,
                    List.of(), new Ir.Instr.LoadInteger(value),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case FLOAT_LITERAL: {
                AstNode.SimpleLiteral data = node.getValue();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                double value = Double.valueOf(data.value());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_FLOAT,
                    List.of(), new Ir.Instr.LoadFloat(value),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case STRING_LITERAL: {
                AstNode.SimpleLiteral data = node.getValue();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_STRING,
                    List.of(), new Ir.Instr.LoadString(data.value()),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case UNIT_LITERAL: {
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_UNIT,
                    List.of(), null, Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case ADD:
            case SUBTRACT:
            case MULTIPLY:
            case DIVIDE:
            case MODULO:
            case LESS_THAN:
            case GREATER_THAN:
            case LESS_THAN_EQUAL:
            case GREATER_THAN_EQUAL:
            case EQUALS:
            case NOT_EQUALS: {
                AstNode.BiOp data = node.getValue();
                Ir.Variable left = this.lowerNode(data.left()).get();
                Ir.Variable right = this.lowerNode(data.right()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                Ir.Instr.Type t;
                switch(node.type) {
                    case ADD: t = Ir.Instr.Type.ADD; break;
                    case SUBTRACT: t = Ir.Instr.Type.SUBTRACT; break;
                    case MULTIPLY: t = Ir.Instr.Type.MULTIPLY; break;
                    case DIVIDE: t = Ir.Instr.Type.DIVIDE; break;
                    case MODULO: t = Ir.Instr.Type.MODULO; break;
                    case LESS_THAN: t = Ir.Instr.Type.LESS_THAN; break;
                    case GREATER_THAN: t = Ir.Instr.Type.GREATER_THAN; break;
                    case LESS_THAN_EQUAL:
                        t = Ir.Instr.Type.LESS_THAN_EQUAL; break;
                    case GREATER_THAN_EQUAL:
                        t = Ir.Instr.Type.GREATER_THAN_EQUAL; break;
                    case EQUALS: t = Ir.Instr.Type.EQUALS; break;
                    case NOT_EQUALS: t = Ir.Instr.Type.NOT_EQUALS; break;
                    default: throw new RuntimeException("unhandled type!");
                }
                this.block().add(new Ir.Instr(
                    t, List.of(left, right), null, Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case NEGATE:
            case NOT: {
                AstNode.MonoOp data = node.getValue();
                Ir.Variable value = this.lowerNode(data.value()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                Ir.Instr.Type t;
                switch(node.type) {
                    case NEGATE: t = Ir.Instr.Type.NEGATE; break;
                    case NOT: t = Ir.Instr.Type.NOT; break;
                    default: throw new RuntimeException("unhandled type!");
                }
                this.block().add(new Ir.Instr(
                    t, List.of(value), null, Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case OR: {
                AstNode.BiOp data = node.getValue();
                Ir.Variable left = this.lowerNode(data.left()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                StaticValue trueValue = this.staticValues.add(
                    new Value.Bool(true)
                );
                this.enterBlock();
                Ir.Variable right = this.lowerNode(data.right()).get();
                List<Ir.Instr> rightInstr = this.exitBlock();
                Ir.Variable destRight = dest.clone();
                rightInstr.add(new Ir.Instr(
                    Ir.Instr.Type.COPY,
                    List.of(destRight, right),
                    null, Optional.empty()
                ));
                dest.version += 1;
                Ir.Variable destLeft = dest.clone();
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.BRANCH_ON_VALUE,
                    List.of(left),
                    new Ir.Instr.BranchOnValue(
                        List.of(trueValue),
                        List.of(List.of(
                            new Ir.Instr(
                                Ir.Instr.Type.COPY,
                                List.of(destLeft, left),
                                null, Optional.empty()
                            )
                        )),
                        rightInstr
                    ),
                    Optional.empty()
                ));
                dest.version += 1;
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.PHI,
                    List.of(destLeft, destRight),
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case AND: {
                AstNode.BiOp data = node.getValue();
                Ir.Variable left = this.lowerNode(data.left()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                StaticValue falseValue = this.staticValues.add(
                    new Value.Bool(false)
                );
                this.enterBlock();
                Ir.Variable right = this.lowerNode(data.right()).get();
                List<Ir.Instr> rightInstr = this.exitBlock();
                Ir.Variable destRight = dest.clone();
                rightInstr.add(new Ir.Instr(
                    Ir.Instr.Type.COPY,
                    List.of(destRight, right),
                    null, Optional.empty()
                ));
                dest.version += 1;
                Ir.Variable destLeft = dest.clone();
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.BRANCH_ON_VALUE,
                    List.of(left),
                    new Ir.Instr.BranchOnValue(
                        List.of(falseValue),
                        List.of(List.of(
                            new Ir.Instr(
                                Ir.Instr.Type.COPY,
                                List.of(destLeft, left),
                                null, Optional.empty()
                            )
                        )),
                        rightInstr
                    ),
                    Optional.empty()
                ));
                dest.version += 1;
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.PHI,
                    List.of(destLeft, destRight),
                    null,
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case MODULE_ACCESS: {
                // this can only be a global variable access
                AstNode.ModuleAccess data = node.getValue();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                Symbols.Symbol symbol = this.symbols.get(data.path()).get();
                Symbols.Symbol.Variable variant = symbol
                    .getVariant(data.variant().get());
                if(variant.valueNode().isPresent()) {
                    if(variant.value().isEmpty()) {
                        Value value = this.interpreter.evaluateNode(
                            variant.valueNode().get()
                        );
                        symbol.setVariant(
                            data.variant().get(), 
                            new Symbols.Symbol.Variable(
                                variant.valueType(), variant.valueNode(), 
                                Optional.of(value)
                            )
                        );
                    }
                    Value value = variant.value().get();
                    Ir.StaticValue staticValue = this.staticValues.add(value);
                    this.block().add(new Ir.Instr(
                        Ir.Instr.Type.LOAD_STATIC_VALUE,
                        List.of(),
                        new Ir.Instr.LoadStaticValue(staticValue),
                        Optional.of(dest)
                    ));
                } else {
                    this.block().add(new Ir.Instr(
                        Ir.Instr.Type.LOAD_EXT_VARIABLE,
                        List.of(),
                        new Ir.Instr.LoadExtVariable(data.path()),
                        Optional.of(dest)
                    ));
                }
                return Optional.of(dest);
            }
            case VARIANT_LITERAL: {
                AstNode.VariantLiteral data = node.getValue();
                Ir.Variable value = this.lowerNode(data.value()).get();
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_VARIANT,
                    List.of(value),
                    new Ir.Instr.LoadVariant(data.variantName()),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case STATIC: {
                AstNode.MonoOp data = node.getValue();
                Value value = this.interpreter.evaluateNode(data.value());
                Ir.StaticValue staticValue = this.staticValues.add(value);
                Ir.Variable dest = this.context.allocate(node.resultType.get());
                this.block().add(new Ir.Instr(
                    Ir.Instr.Type.LOAD_STATIC_VALUE,
                    List.of(),
                    new Ir.Instr.LoadStaticValue(staticValue),
                    Optional.of(dest)
                ));
                return Optional.of(dest);
            }
            case PROCEDURE:
            case MODULE_DECLARATION:
            case USE:
            case TARGET: {
                throw new RuntimeException("should not be encountered!");
            }
            default: {
                throw new RuntimeException("unhandled node type!");
            }
        }
    }   

}
