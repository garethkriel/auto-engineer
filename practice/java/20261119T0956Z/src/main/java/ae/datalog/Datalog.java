package ae.datalog;

/** Entry point: {@code Datalog.run(source)}. */
public final class Datalog {
    private Datalog() {}

    /** Parses, checks and evaluates a program with the semi-naive strategy. */
    public static Result run(String source) {
        return run(source, Mode.SEMI_NAIVE);
    }

    /**
     * Parses, checks and evaluates a program.
     *
     * @throws DatalogException on syntax or semantic errors
     */
    public static Result run(String source, Mode mode) {
        Syntax.Program p = Parser.parse(source);
        return new Engine(p, Analyzer.analyze(p), mode).run();
    }
}
