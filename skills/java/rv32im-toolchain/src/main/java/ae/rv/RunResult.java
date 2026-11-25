package ae.rv;

/** Outcome of a run: halt reason, exit code (a0 at exit, else 0), executed instruction count and console output. */
public record RunResult(Halt halt, int exitCode, long steps, String output) { }
