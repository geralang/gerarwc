
package typesafeschwalbe.gerac.compiler.backend;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import typesafeschwalbe.gerac.compiler.Source;
import typesafeschwalbe.gerac.compiler.Symbols;
import typesafeschwalbe.gerac.compiler.frontend.Namespace;
import typesafeschwalbe.gerac.compiler.types.DataType;
import typesafeschwalbe.gerac.compiler.types.TypeContext;
import typesafeschwalbe.gerac.compiler.types.TypeVariable;

public class JsCodeGen implements CodeGen {

    private static final String CORE_LIB = """
        function gera___eq(a, b) {
            if(typeof a === "object") {
                if(a instanceof Array) {
                    if(a.length !== b.length) { return false; }
                    for(let i = 0; i < a.length; i += 1) {
                        if(!gera___eq(a[i], b[i])) { return false; }
                    }
                    return true;
                }
                for(const key of Object.keys(a)) {
                    if(!gera___eq(a[key], b[key])) { return false; }
                }
                return true;
            }
            return a === b;
        }
        
        const gera___hash = (function() {
            const random_int = () => {
                const upper = BigInt.asIntN(
                    32, BigInt(Math.floor(Math.random() * (2 ** 32)))
                ) << 32n;
                const lower = BigInt.asIntN(
                    32, BigInt(Math.floor(Math.random() * (2 ** 32)))
                );
                return BigInt.asIntN(64, upper | lower);
            };
            const object_hashes = new WeakMap();
            return (data) => {
                if(typeof data === "object" && !data.GERA___HASH_VALS) {
                    if(!object_hashes.has(data)) {
                        const h = random_int();
                        object_hashes.set(data, h);
                        return h;
                    }
                    return object_hashes.get(data);
                }
                const d = typeof data === "string"? data : data.toString();
                let h = 0n;
                for(let i = 0; i < d.length; i += 1) {
                    h = BigInt.asIntN(64, BigInt(d.charCodeAt(i))
                        + (h << 6n) + (h << 16n) - h);
                }
                return h;
            };
        })();
        
        function gera___verify_size(size, file, line) {
            if(size >= 0n) {
                return size;
            }
            gera___stack.push("<array-init>", file, line);
            throw `the value ${size} is not a valid array size`;

        }

        function gera___verify_index(index, length, file, line) {
            const final_index = index < 0n
                ? BigInt(length) + index : index;
            if(final_index >= 0 && final_index < BigInt(length)) {
                return final_index;
            }
            gera___stack.push("<index>", file, line);
            throw `the index ${index} is out of bounds`
                + ` for an array of length ${length}`;
        }
        
        function gera___verify_integer_divisor(d, file, line) {
            if(d != 0n) { return d; }
            gera___stack.push("<division>", file, line);
            throw "integer division by zero";
        }
        
        function gera___substring(s, s_start_idx, s_end_idx) {
            const s_length = BigInt(s.length);
            let start_idx = s_start_idx;
            if(start_idx < 0) {
                start_idx += s_length;
            }
            if(start_idx < 0 || start_idx > s_length) {
                throw `the start index ${s_start_idx} is out of bounds`
                    + ` for a string of length ${s_length}`;
            }
            let end_idx = s_end_idx;
            if(end_idx < 0) {
                end_idx += s_length;
            }
            if(end_idx < 0 || end_idx > s_length) {
                throw `the end index ${s_end_idx} is out of bounds`
                    + ` for a string of length ${s_length}`;
            }
            let start_offset = 0;
            for(let i = 0n; i < start_idx; i += 1n) {
                start_offset += s.codePointAt(start_offset) > 0xFFFF? 2 : 1;
            }
            let end_offset = start_offset;
            for(let c = start_idx; c < end_idx; c += 1n) {
                end_offset += s.codePointAt(end_offset) > 0xFFFF? 2 : 1;
            }
            return s.substring(start_offset, end_offset);
        }
        
        function gera___strlen(s) {
            let length = 0n;
            for(let i = 0; i < s.length; length += 1n) {
                const code = s.codePointAt(i);
                i += code > 0xFFFF? 2 : 1;
            }
            return length;
        }    
        """;
    
    @FunctionalInterface
    private static interface BuiltInProcedure {
        void emit(
            TypeContext tctx, List<Ir.Variable> args, List<TypeVariable> argt,
            Ir.Variable dest, StringBuilder out
        );
    }

    private void addBuiltins() {
        this.builtIns.put(
            new Namespace(List.of("core", "addr_eq")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = ");
                this.emitVariable(args.get(0), out);
                out.append(" === ");
                this.emitVariable(args.get(1), out);
                out.append(";\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "tag_eq")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = ");
                this.emitVariable(args.get(0), out);
                out.append(".tag === ");
                this.emitVariable(args.get(1), out);
                out.append(".tag;\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "length")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = ");
                if(tctx.get(argt.get(0)).type == DataType.Type.ARRAY) {
                    out.append("BigInt(");
                    this.emitVariable(args.get(0), out);
                    out.append(".length)");
                } else {
                    out.append("gera___strlen(");
                    this.emitVariable(args.get(0), out);
                    out.append(")");
                }
                out.append(";\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "exhaust")),
            (tctx, args, argt, dest, out) -> {
                out.append("while(");
                this.emitVariable(args.get(0), out);
                out.append("().tag == ");
                out.append(this.getVariantTagNumber("next"));
                out.append(") {}\n");
                this.emitVariable(dest, out);
                out.append(" = undefined;\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "panic")),
            (tctx, args, argt, dest, out) -> {
                out.append("throw ");
                this.emitVariable(args.get(0), out);
                out.append(";\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "as_str")),
            (tctx, args, argt, dest, out) -> {
                switch(tctx.get(argt.get(0)).type) {
                    case UNIT: {
                        this.emitVariable(dest, out);
                        out.append(" = \"unit\";\n");
                    } break;
                    case BOOLEAN: {
                        this.emitVariable(dest, out);
                        out.append(" = ");
                        this.emitVariable(args.get(0), out);
                        out.append("? \"true\" : \"false\";\n");
                    } break;
                    case INTEGER: {
                        this.emitVariable(dest, out);
                        out.append(" = ");
                        this.emitVariable(args.get(0), out);
                        out.append(".toString();\n");
                    } break;
                    case FLOAT: {
                        this.emitVariable(dest, out);
                        out.append(" = ");
                        this.emitVariable(args.get(0), out);
                        out.append(".toString();\n");
                    } break;
                    case STRING: {
                        this.emitVariable(dest, out);
                        out.append(" = ");
                        this.emitVariable(args.get(0), out);
                        out.append(";\n");
                    } break;
                    case ARRAY: {
                        this.emitVariable(dest, out);
                        out.append(" = \"<array>\";\n");
                    } break;
                    case UNORDERED_OBJECT: {
                        this.emitVariable(dest, out);
                        out.append(" = \"<object>\";\n");
                    } break;
                    case CLOSURE: {
                        this.emitVariable(dest, out);
                        out.append(" = \"<closure>\";\n");
                    } break;
                    case UNION: {
                        DataType.Union<TypeVariable> union
                            = tctx.get(argt.get(0)).getValue();
                        out.append("switch(");
                        this.emitVariable(args.get(0), out);
                        out.append(".tag) {\n");
                        for(String variant: union.variantTypes().keySet()) {
                            out.append("case ");
                            out.append(this.getVariantTagNumber(variant));
                            out.append(": ");
                            this.emitVariable(dest, out);
                            out.append(" = \"#");
                            out.append(variant);
                            out.append(" <...>\"; break;\n");
                        }
                        out.append("}\n");
                    } break;
                    case ANY:
                    case NUMERIC:
                    case INDEXED:
                    case REFERENCED: {
                        out.append(
                            "(() => { throw \"if you read this, that means that"
                                + " the compiler fucked up real bad :(\" "
                                + "})();\n"
                        );
                    } break;
                    default: {
                        throw new RuntimeException("unhandled type!");
                    }
                }
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "as_int")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = ");
                switch(tctx.get(argt.get(0)).type) {
                    case INTEGER: {
                        this.emitVariable(args.get(0), out);
                    } break;
                    case FLOAT: {
                        out.append("BigInt(Math.trunc(");
                        this.emitVariable(args.get(0), out);
                        out.append("))");
                    } break;
                    case NUMERIC: {
                        out.append(
                            "(() => { throw \"if you read this, that means that"
                                + " the compiler fucked up real bad :(\" "
                                + "})();\n"
                        );
                    } break;
                    default: {
                        throw new RuntimeException(
                            "should not be encountered!"
                        );
                    }
                }
                out.append(";\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "as_flt")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = Number(");
                this.emitVariable(args.get(0), out);
                out.append(");\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "substring")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = gera___substring(");
                this.emitVariable(args.get(0), out);
                out.append(", ");
                this.emitVariable(args.get(1), out);
                out.append(", ");
                this.emitVariable(args.get(2), out);
                out.append(");\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "concat")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = ");
                this.emitVariable(args.get(0), out);
                out.append(" + ");
                this.emitVariable(args.get(1), out);
                out.append(";\n");
            }
        );
        this.builtIns.put(
            new Namespace(List.of("core", "hash")),
            (tctx, args, argt, dest, out) -> {
                this.emitVariable(dest, out);
                out.append(" = ");
                out.append("gera___hash(");
                this.emitVariable(args.get(0), out);
                out.append(");\n");
            }
        );
    }

    private final Map<String, String> sourceFiles;
    private final Symbols symbols;
    private final TypeContext typeContext;
    private final Ir.StaticValues staticValues;
    private final Map<Namespace, BuiltInProcedure> builtIns;

    private final List<Ir.Context> contextStack;

    private long nextUnionTagNumber;
    private Map<String, Long> unionVariantTagNumbers;

    public JsCodeGen(
        Map<String, String> sourceFiles, Symbols symbols, 
        TypeContext typeContext, Ir.StaticValues staticValues
    ) {
        this.sourceFiles = sourceFiles;
        this.symbols = symbols;
        this.typeContext = typeContext;
        this.staticValues = staticValues;
        this.builtIns = new HashMap<>();
        this.contextStack = new LinkedList<>();
        this.addBuiltins();
    }

    private void enterContext(Ir.Context context) {
        this.contextStack.add(context);
    }

    private void exitContext() {
        this.contextStack.remove(this.contextStack.size() - 1);
    }

    private long getVariantTagNumber(String variantName) {
        Long existingNumber = this.unionVariantTagNumbers.get(variantName);
        if(existingNumber != null) { return existingNumber; }
        long number = this.nextUnionTagNumber;
        this.unionVariantTagNumbers.put(variantName, number);
        this.nextUnionTagNumber += 1;
        return number;
    }

    @Override
    public String generate(Namespace mainPath) {
        this.nextUnionTagNumber = 0;
        this.unionVariantTagNumbers = new HashMap<>();
        StringBuilder out = new StringBuilder();
        out.append("\n");
        out.append("""
            //
            // Generated from Gera source code by the Gera compiler.
            // See: https://github.com/geralang
            //
            """);
        out.append("\n");
        out.append("(function() {\n");
        out.append("\"use strict\";\n");
        out.append("\n");
        out.append(CORE_LIB);
        out.append("\n");
        this.emitStaticValues(out);
        out.append("\n");
        this.emitSymbols(out);
        out.append("\n");
        this.emitVariant(mainPath, 0, out);
        out.append("();\n");
        out.append("})();\n");
        return out.toString();
    }

    private void emitStaticValues(StringBuilder out) {
        int valueC = this.staticValues.values.size();
        for(int valueI = 0; valueI < valueC; valueI += 1) {
            out.append("let GERA_STATIC_VALUE_");
            out.append(valueI);
            out.append(" = ");
            this.emitDeclValueDirect(this.staticValues.values.get(valueI), out);
            out.append(";\n");
        }
        out.append("\n");
        for(int valueI = 0; valueI < valueC; valueI += 1) {
            this.emitValueInitDirect(this.staticValues.values.get(valueI), out);
        }
    }

    private void emitValueRef(Ir.StaticValue v, StringBuilder out) {
        out.append("GERA_STATIC_VALUE_");
        out.append(this.staticValues.getIndexOf(v));
    }

    private void emitDeclValueDirect(Ir.StaticValue v, StringBuilder out) {
        if(v instanceof Ir.StaticValue.Unit) {
            out.append("undefined");
        } else if(v instanceof Ir.StaticValue.Bool) {
            out.append(
                v.<Ir.StaticValue.Bool>getValue().value
                    ? "true"
                    : "false"
            );
        } else if(v instanceof Ir.StaticValue.Int) {
            out.append("BigInt.asIntN(64, ");
            out.append(String.valueOf(
                v.<Ir.StaticValue.Int>getValue().value
            ));
            out.append("n)");
        } else if(v instanceof Ir.StaticValue.Float) {
            out.append(String.valueOf(
                v.<Ir.StaticValue.Float>getValue().value
            ));
        } else if(v instanceof Ir.StaticValue.Str) {
            this.emitStringLiteral(
                v.<Ir.StaticValue.Str>getValue().value, out
            );
        } else if(v instanceof Ir.StaticValue.Arr) {
            out.append("[]");
        } else if(v instanceof Ir.StaticValue.Obj) {
            out.append("{}");
        } else if(v instanceof Ir.StaticValue.Closure) {
            out.append("() => {}");
        } else if(v instanceof Ir.StaticValue.Union) {
            long variantTag = this.getVariantTagNumber(
                v.<Ir.StaticValue.Union>getValue().variant
            );
            out.append("{ GERA___HASH_VALS: true, tag: ");
            out.append(variantTag);
            out.append(", value: null }");
        } else {
            throw new RuntimeException("unhandled value type!");
        }
    }

    private void emitValueInitDirect(Ir.StaticValue v, StringBuilder out) {
        if(v instanceof Ir.StaticValue.Arr) {
            Ir.StaticValue.Arr data = v.getValue();
            this.emitValueRef(v, out);
            out.append(".push(");
            for(int valueI = 0; valueI < data.value.size(); valueI += 1) {
                if(valueI > 0) {
                    out.append(", ");
                }
                this.emitValueRef(data.value.get(valueI), out);
            }
            out.append(");\n");
        } else if(v instanceof Ir.StaticValue.Obj) {
            Ir.StaticValue.Obj data = v.getValue();
            for(String member: data.value.keySet()) {
                this.emitValueRef(v, out);
                out.append(".");
                out.append(member);
                out.append(" = ");
                this.emitValueRef(data.value.get(member), out);
                out.append(";\n");
            }
        } else if(v instanceof Ir.StaticValue.Closure) {
            Ir.StaticValue.Closure data = v.getValue();
            this.emitValueRef(v, out);
            out.append(" = (function() {\n");
            for(String capture: data.captureValues.keySet()) {
                out.append("let captured_");
                out.append(capture);
                out.append(" = ");
                this.emitValueRef(data.captureValues.get(capture), out);
                out.append(";\n");
            }
            out.append("return ");
            this.emitArgListDef(data.argumentTypes.size(), out);
            out.append(" => {\n");
            this.enterContext(new Ir.Context());
            this.enterContext(data.context);
            this.emitContextInit(out);
            this.emitInstructions(data.body, out);
            this.exitContext();
            this.exitContext();
            out.append("} })();\n");
        } else if(v instanceof Ir.StaticValue.Union) {
            this.emitValueRef(v, out);
            out.append(".value = ");
            this.emitValueRef(v.<Ir.StaticValue.Union>getValue().value, out);
            out.append(";\n");
        }
    }
    
    private void emitSymbols(StringBuilder out) {
        for(Namespace path: this.symbols.allSymbolPaths()) {
            Symbols.Symbol symbol = this.symbols.get(path).get();
            if(symbol.type != Symbols.Symbol.Type.PROCEDURE) {
                continue;
            }
            Symbols.Symbol.Procedure symbolData = symbol.getValue();
            if(symbolData.body().isEmpty()) {
                continue;
            }
            for(
                int variantI = 0; 
                variantI < symbol.variantCount(); 
                variantI += 1
            ) {
                if(symbol.mappedVariantIdx(variantI) != variantI) { continue; }
                Symbols.Symbol.Procedure variantData = symbol
                    .getVariant(variantI);
                out.append("function ");
                this.emitVariant(path, variantI, out);
                this.emitArgListDef(
                    variantData.argumentTypes().get().size(), out
                );
                out.append(" {\n");
                this.enterContext(variantData.ir_context().get());
                this.emitContextInit(out);
                this.emitInstructions(variantData.ir_body().get(), out);
                this.exitContext();
                out.append("}\n");
                out.append("\n");
            }
        }
    }

    private void emitArgListDef(int argC, StringBuilder out) {
        out.append("(");
        for(int argI = 0; argI < argC; argI += 1) {
            if(argI > 0) {
                out.append(", ");
            }
            out.append("arg");
            out.append(argI);
        }
        out.append(")");
    }

    private void emitContextInit(StringBuilder out) {
        int ctxI = this.contextStack.size() - 1;
        Ir.Context ctx = this.contextStack.get(ctxI);
        for(String capturedName: ctx.capturedNames.values()) {
            out.append("let captured_");
            out.append(capturedName);
            out.append(";\n");
        }
        for(int varI = 0; varI < ctx.variableTypes.size(); varI += 1) {
            if(ctx.capturedNames.containsKey(varI)) { continue; }
            out.append("let local_");
            out.append(varI);
            out.append(";\n");
        }
        for(int argI = 0; argI < ctx.argumentVars.size(); argI += 1) {
            this.emitVariable(ctx.argumentVars.get(argI), out);
            out.append(" = arg");
            out.append(argI);
            out.append(";\n");
        }
    }

    private void emitVariable(Ir.Variable v, StringBuilder out) {
        int ctxI = this.contextStack.size() - 1;
        Ir.Context ctx = this.contextStack.get(ctxI);
        String capturedName = ctx.capturedNames.get(v.index);
        if(capturedName != null) {
            out.append("captured_");
            out.append(capturedName);
        } else {
            out.append("local_");
            out.append(v.index);
        }
    }

    private void emitVariant(Namespace path, int variant, StringBuilder out) {
        Symbols.Symbol symbol = this.symbols.get(path).get();
        this.emitPath(path, out);
        out.append("_");
        out.append(symbol.mappedVariantIdx(variant));
    }

    private void emitPath(Namespace path, StringBuilder out) {
        for(
            int elementI = 0; elementI < path.elements().size(); elementI += 1
        ) {
            if(elementI > 0) {
                out.append("_");
            }
            String element = path.elements().get(elementI);
            for(int charI = 0; charI < element.length(); charI += 1) {
                if(element.charAt(charI) == '_') {
                    out.append("__");
                } else {
                    out.append(element.charAt(charI));
                }
            }
        }
    }

    private void emitStringLiteral(String content, StringBuilder out) {
        out.append("\"");
        for(int charI = 0; charI < content.length(); charI += 1) {
            char c = content.charAt(charI);
            switch(c) {
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\"': out.append("\\\""); break;
                default: out.append(c);
            }
        }
        out.append("\"");
    }

    private void emitInstructions(List<Ir.Instr> instr, StringBuilder out) {
        for(Ir.Instr i: instr) {
            this.emitInstruction(i, out);
        }
    }

    private void emitInstruction(Ir.Instr instr, StringBuilder out) {
        switch(instr.type) {
            case LOAD_OBJECT: {
                Ir.Instr.LoadObject data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = { ");
                for(
                    int memberI = 0; 
                    memberI < data.memberNames().size(); 
                    memberI += 1
                ) {
                    if(memberI > 0) {
                        out.append(", ");
                    }
                    out.append(data.memberNames().get(memberI));
                    out.append(": ");
                    this.emitVariable(instr.arguments.get(memberI), out);
                }
                out.append(" };\n");
            } break;
            case LOAD_FIXED_ARRAY: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = [");
                for(
                    int valueI = 0; valueI < instr.arguments.size(); valueI += 1
                ) {
                    if(valueI > 0) {
                        out.append(", ");
                    }
                    this.emitVariable(instr.arguments.get(valueI), out);
                }
                out.append("];\n");
            } break;
            case LOAD_REPEAT_ARRAY: {
                Ir.Instr.LoadRepeatArray data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = new Array(Number(");
                this.emitArraySizeVerify(
                    instr.arguments.get(1), data.source(), out
                );
                out.append(")).fill(");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(");\n");
            } break;
            case LOAD_VARIANT: {
                Ir.Instr.LoadVariant data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = { GERA___HASH_VALS: true, tag: ");
                out.append(this.getVariantTagNumber(data.variantName()));
                out.append(", value: ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(" };\n");
            } break;
            case LOAD_CLOSURE: {
                Ir.Instr.LoadClosure data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitArgListDef(data.argumentTypes().size(), out);
                out.append(" => {\n");
                this.enterContext(data.context());
                this.emitContextInit(out);
                this.emitInstructions(data.body(), out);
                this.exitContext();
                out.append("};\n");
            } break;
            case LOAD_STATIC_VALUE: {
                Ir.Instr.LoadStaticValue data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitValueRef(data.value(), out);
                out.append(";\n");
            } break;
            case LOAD_EXT_VARIABLE: {
                Ir.Instr.LoadExtVariable data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Symbols.Symbol symbol = this.symbols.get(data.path()).get();
                out.append(symbol.externalName.get());
                out.append(";\n");
            } break;

            case READ_OBJECT: {
                Ir.Instr.ObjectAccess data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(".");
                out.append(data.memberName());
                out.append(";\n");
            } break;
            case WRITE_OBJECT: {
                Ir.Instr.ObjectAccess data = instr.getValue();
                this.emitVariable(instr.arguments.get(0), out);
                out.append(".");
                out.append(data.memberName());
                out.append(" = ");
                this.emitVariable(instr.arguments.get(1), out);
                out.append(";\n");
            } break;
            case READ_ARRAY: {
                Ir.Instr.ArrayAccess data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append("[");
                this.emitArrayIndexVerify(
                    instr.arguments.get(1), 
                    instr.arguments.get(0),
                    data.source(),
                    out
                );
                out.append("];\n");
            } break;
            case WRITE_ARRAY: {
                Ir.Instr.ArrayAccess data = instr.getValue();
                this.emitVariable(instr.arguments.get(0), out);
                out.append("[");
                this.emitArrayIndexVerify(
                    instr.arguments.get(1), 
                    instr.arguments.get(0),
                    data.source(),
                    out
                );
                out.append("] = ");
                this.emitVariable(instr.arguments.get(2), out);
                out.append(";\n");
            } break;
            case READ_CAPTURE: {
                Ir.Instr.CaptureAccess data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = captured_");
                out.append(data.captureName());
                out.append(";\n");
            } break;
            case WRITE_CAPTURE: {
                Ir.Instr.CaptureAccess data = instr.getValue();
                out.append("captured_");
                out.append(data.captureName());
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(";\n");
            } break;

            case COPY: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(";\n");
            } break;

            case ADD: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Ir.Context ctx = this.contextStack
                    .get(this.contextStack.size() - 1);
                boolean isInteger = this.typeContext
                    .get(ctx.variableTypes.get(instr.arguments.get(0).index))
                    .type == DataType.Type.INTEGER;
                if(isInteger) {
                    out.append("BigInt.asIntN(64, ");
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" + ");
                    this.emitVariable(instr.arguments.get(1), out);
                    out.append(")");
                } else {
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" + ");
                    this.emitVariable(instr.arguments.get(1), out);
                }
                out.append(";\n");
            } break;
            case SUBTRACT: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Ir.Context ctx = this.contextStack
                    .get(this.contextStack.size() - 1);
                boolean isInteger = this.typeContext
                    .get(ctx.variableTypes.get(instr.arguments.get(0).index))
                    .type == DataType.Type.INTEGER;
                if(isInteger) {
                    out.append("BigInt.asIntN(64, ");
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" - ");
                    this.emitVariable(instr.arguments.get(1), out);
                    out.append(")");
                } else {
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" - ");
                    this.emitVariable(instr.arguments.get(1), out);
                }
                out.append(";\n");
            } break;
            case MULTIPLY: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Ir.Context ctx = this.contextStack
                    .get(this.contextStack.size() - 1);
                boolean isInteger = this.typeContext
                    .get(ctx.variableTypes.get(instr.arguments.get(0).index))
                    .type == DataType.Type.INTEGER;
                if(isInteger) {
                    out.append("BigInt.asIntN(64, ");
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" * ");
                    this.emitVariable(instr.arguments.get(1), out);
                    out.append(")");
                } else {
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" * ");
                    this.emitVariable(instr.arguments.get(1), out);
                }
                out.append(";\n");
            } break;
            case DIVIDE: {
                Ir.Instr.Division data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Ir.Context ctx = this.contextStack
                    .get(this.contextStack.size() - 1);
                boolean isInteger = this.typeContext
                    .get(ctx.variableTypes.get(instr.arguments.get(0).index))
                    .type == DataType.Type.INTEGER;
                if(isInteger) {
                    out.append("BigInt.asIntN(64, ");
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" / ");
                    this.emitIntDivisorVerify(
                        instr.arguments.get(1), data.source(), out
                    );
                    out.append(")");
                } else {
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" / ");
                    this.emitVariable(instr.arguments.get(1), out);
                }
                out.append(";\n");
            } break;
            case MODULO: {
                Ir.Instr.Division data = instr.getValue();
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Ir.Context ctx = this.contextStack
                    .get(this.contextStack.size() - 1);
                boolean isInteger = this.typeContext
                    .get(ctx.variableTypes.get(instr.arguments.get(0).index))
                    .type == DataType.Type.INTEGER;
                if(isInteger) {
                    out.append("BigInt.asIntN(64, ");
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" % ");
                    this.emitIntDivisorVerify(
                        instr.arguments.get(1), data.source(), out
                    );
                    out.append(")");
                } else {
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(" % ");
                    this.emitVariable(instr.arguments.get(1), out);
                }
                out.append(";\n");
            } break;
            case NEGATE: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                Ir.Context ctx = this.contextStack
                    .get(this.contextStack.size() - 1);
                boolean isInteger = this.typeContext
                    .get(ctx.variableTypes.get(instr.arguments.get(0).index))
                    .type == DataType.Type.INTEGER;
                if(isInteger) {
                    out.append("BigInt.asIntN(64, -");
                    this.emitVariable(instr.arguments.get(0), out);
                    out.append(")");
                } else {
                    out.append("-");
                    this.emitVariable(instr.arguments.get(0), out);
                }
                out.append(";\n");
            } break;
            case LESS_THAN: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(" < ");
                this.emitVariable(instr.arguments.get(1), out);
                out.append(";\n");
            } break;
            case LESS_THAN_EQUAL: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(" <= ");
                this.emitVariable(instr.arguments.get(1), out);
                out.append(";\n");
            } break;
            case EQUALS: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = gera___eq(");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(", ");
                this.emitVariable(instr.arguments.get(1), out);
                out.append(");\n");
            } break;
            case NOT_EQUALS: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = !gera___eq(");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(", ");
                this.emitVariable(instr.arguments.get(1), out);
                out.append(");\n");
            } break;
            case NOT: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                out.append(" !");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(";\n");
            } break;

            case BRANCH_ON_VALUE: {
                Ir.Instr.BranchOnValue data = instr.getValue();
                out.append("switch(");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(") {\n");
                for(
                    int branchI = 0; 
                    branchI < data.branchBodies().size(); 
                    branchI += 1
                ) {
                    out.append("case ");
                    this.emitValueRef(
                        data.branchValues().get(branchI), out
                    );
                    out.append(":\n");
                    this.emitInstructions(
                        data.branchBodies().get(branchI), out
                    );
                    out.append("break;\n");
                }
                out.append("default:\n");
                this.emitInstructions(data.elseBody(), out);
                out.append("}\n");
            } break;
            case BRANCH_ON_VARIANT: {
                Ir.Instr.BranchOnVariant data = instr.getValue();
                out.append("switch(");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(".tag) {\n");
                for(
                    int branchI = 0; 
                    branchI < data.branchBodies().size(); 
                    branchI += 1
                ) {
                    out.append("case ");
                    long variantTag = this.getVariantTagNumber(
                        data.branchVariants().get(branchI)
                    );
                    out.append(variantTag);
                    out.append(":\n");
                    Optional<Ir.Variable> bVar = data.branchVariables()
                        .get(branchI);
                    if(bVar.isPresent()) {
                        this.emitVariable(bVar.get(), out);
                        out.append(" = ");
                        this.emitVariable(instr.arguments.get(0), out);
                        out.append(".value;\n");
                    }
                    this.emitInstructions(
                        data.branchBodies().get(branchI), out
                    );
                    out.append("break;\n");
                }
                out.append("default:\n");
                this.emitInstructions(data.elseBody(), out);
                out.append("}\n");
            } break;

            case CALL_PROCEDURE: {
                Ir.Instr.CallProcedure data = instr.getValue();
                Symbols.Symbol symbol = this.symbols.get(data.path()).get();
                boolean isExternal = symbol.externalName.isPresent();
                boolean hasBody = symbol.<Symbols.Symbol.Procedure>getValue()
                    .body().isPresent();
                if(isExternal || hasBody) {
                    this.emitVariable(instr.dest.get(), out);
                    out.append(" = ");
                    if(isExternal) {
                        out.append(symbol.externalName.get());
                    } else if(hasBody) {
                        this.emitVariant(
                            data.path(), 
                            symbol.mappedVariantIdx(data.variant()), 
                            out
                        );
                    }
                    out.append("(");
                    for(int argI = 0; argI < instr.arguments.size(); argI += 1) {
                        if(argI > 0) {
                            out.append(", ");
                        }
                        this.emitVariable(instr.arguments.get(argI), out);
                    }
                    out.append(");\n");
                } else {
                    Ir.Context ctx = this.contextStack
                        .get(this.contextStack.size() - 1);
                    this.builtIns.get(data.path()).emit(
                        this.typeContext,
                        instr.arguments, 
                        instr.arguments.stream()
                            .map(a -> ctx.variableTypes.get(a.index))
                            .toList(), 
                        instr.dest.get(), 
                        out
                    );
                }
            } break;
            case CALL_CLOSURE: {
                this.emitVariable(instr.dest.get(), out);
                out.append(" = ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append("(");
                for(int argI = 1; argI < instr.arguments.size(); argI += 1) {
                    if(argI > 1) {
                        out.append(", ");
                    }
                    this.emitVariable(instr.arguments.get(argI), out);
                }
                out.append(");\n");
            } break;
            case RETURN: {
                out.append("return ");
                this.emitVariable(instr.arguments.get(0), out);
                out.append(";\n");
            }
            case PHI: break;
            default: {
                throw new RuntimeException("unhandled instruction type!");
            }
        }
    }

    private void emitArraySizeVerify(
        Ir.Variable size, Source source, StringBuilder out
    ) {
        out.append("gera___verify_size(");
        this.emitVariable(size, out);
        out.append(", ");
        this.emitStringLiteral(source.file(), out);
        out.append(", ");
        out.append(source.computeLine(this.sourceFiles));
        out.append(")");
    }

    private void emitArrayIndexVerify(
        Ir.Variable index, Ir.Variable accessed,
        Source source, StringBuilder out
    ) {
        out.append("gera___verify_index(");
        this.emitVariable(index, out);
        out.append(", ");
        this.emitVariable(accessed, out);
        out.append(".length, ");
        this.emitStringLiteral(source.file(), out);
        out.append(", ");
        out.append(source.computeLine(this.sourceFiles));
        out.append(")");
    }

    private void emitIntDivisorVerify(
        Ir.Variable divisor, 
        Source source, StringBuilder out
    ) {
        out.append("gera___verify_integer_divisor(");
        this.emitVariable(divisor, out);
        out.append(", ");
        this.emitStringLiteral(source.file(), out);
        out.append(", ");
        out.append(source.computeLine(this.sourceFiles));
        out.append(")");
    }

}
