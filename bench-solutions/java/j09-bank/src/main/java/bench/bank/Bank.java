package bench.bank;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** In-memory bank with atomic transfers. */
public final class Bank {
    private final Map<String, BigDecimal> accounts = new HashMap<>();

    public Bank() { }

    public synchronized void open(String id, BigDecimal initial) {
        if (id == null || initial == null || initial.signum() < 0 || accounts.containsKey(id)) {
            throw new IllegalArgumentException("cannot open account");
        }
        accounts.put(id, initial);
    }

    public synchronized void transfer(String from, String to, BigDecimal amount)
            throws InsufficientFundsException {
        if (from == null || to == null || amount == null || amount.signum() <= 0
                || from.equals(to) || !accounts.containsKey(from) || !accounts.containsKey(to)) {
            throw new IllegalArgumentException("invalid transfer");
        }
        BigDecimal fromBal = accounts.get(from);
        if (fromBal.compareTo(amount) < 0) {
            throw new InsufficientFundsException("insufficient funds in " + from);
        }
        BigDecimal newFrom = fromBal.subtract(amount);
        BigDecimal newTo = accounts.get(to).add(amount);
        accounts.put(from, newFrom);
        accounts.put(to, newTo);
    }

    public synchronized BigDecimal balance(String id) {
        BigDecimal b = id == null ? null : accounts.get(id);
        if (b == null) {
            throw new IllegalArgumentException("unknown account");
        }
        return b;
    }
}
