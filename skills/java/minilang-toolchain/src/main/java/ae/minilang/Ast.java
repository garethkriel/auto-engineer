package ae.minilang;

import java.util.List;

/** Syntax tree. Positions are kept only where a static error can point at the node. */
public final class Ast {
    private Ast() { }

    /** 1-based source position. */
    public record Pos(int line, int col) {
        @Override public String toString() {
            return "line " + line + ", col " + col;
        }
    }

    /** Binary operators in precedence-agnostic form. */
    public enum BinOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
        LT("<"), LE("<="), GT(">"), GE(">="), EQ("=="), NE("!="),
        AND("&&"), OR("||");

        public final String symbol;

        BinOp(String symbol) {
            this.symbol = symbol;
        }
    }

    /** Unary operators. */
    public enum UnOp { NEG, NOT }

    /** Expressions. */
    public sealed interface Expr permits Num, Var, Unary, Binary, Call { }

    public record Num(long value) implements Expr { }

    public record Var(String name, Pos pos) implements Expr { }

    public record Unary(UnOp op, Expr operand) implements Expr { }

    public record Binary(BinOp op, Expr left, Expr right) implements Expr { }

    public record Call(String name, List<Expr> args, Pos pos) implements Expr { }

    /** Statements. A {@code List<Stmt>} body is always one lexical scope. */
    public sealed interface Stmt permits Let, Assign, If, While, Return, Print, ExprStmt, Block { }

    public record Let(String name, Expr init, Pos pos) implements Stmt { }

    public record Assign(String name, Expr value, Pos pos) implements Stmt { }

    public record If(Expr cond, List<Stmt> thenBody, List<Stmt> elseBody) implements Stmt { }

    public record While(Expr cond, List<Stmt> body) implements Stmt { }

    /** {@code value} is null for a bare {@code return;}. */
    public record Return(Expr value) implements Stmt { }

    public record Print(Expr value) implements Stmt { }

    public record ExprStmt(Expr expr) implements Stmt { }

    public record Block(List<Stmt> body) implements Stmt { }

    public record Func(String name, List<String> params, List<Stmt> body, Pos pos) { }

    /** Hoisted functions plus the main body. */
    public record Program(List<Func> funcs, List<Stmt> main) { }
}
