package resilience;

/** Thrown when a call is rejected because the circuit breaker is open (or a probe is in flight). */
public class CircuitOpenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CircuitOpenException(String message) {
        super(message);
    }
}
