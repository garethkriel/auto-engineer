package ae.book;

import java.util.List;

/** Result of submitting an order; {@code leaves = qty - filled}. */
public record Result(Status status, List<Trade> trades, long filled, long leaves, String reason) {
    public Result {
        trades = List.copyOf(trades);
    }
}
