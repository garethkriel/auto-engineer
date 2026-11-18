package ae.net;

import java.time.Duration;

/** Final outcome of a (possibly retried) request. */
public record HttpResult(int status, String body, int attempts, Duration totalDelay) {}
