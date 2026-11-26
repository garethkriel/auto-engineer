package bench.bank;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** Thread-safe in-memory bank (single lock keeps transfers atomic and deadlock-free). */
public final class Bank {
    private final Map<String, BigDecimal> accounts = new HashMap<>();

    public Bank() {}

    public synchronized void open(String id, BigDecimal initial) {
        if (id == null || initial == null) throw new IllegalArgumentException("null argument");
        if (initial.signum() < 0) throw new IllegalArgumentException("negative initial balance");
        if (accounts.containsKey(id)) throw new IllegalArgumentException("duplicate account " + id);
        accounts.put(id, initial);
    }

    public synchronized void transfer(String from, String to, BigDecimal amount)
            throws InsufficientFundsException {
        if (from == null || to == null || amount == null) throw new IllegalArgumentException("null argument");
        if (amount.signum() <= 0) throw new IllegalArgumentException("amount must be positive");
        if (from.equals(to)) throw new IllegalArgumentException("same account");
        BigDecimal f = accounts.get(from);
        BigDecimal t = accounts.get(to);
        if (f == null || t == null) throw new IllegalArgumentException("unknown account");
        if (f.compareTo(amount) < 0) throw new InsufficientFundsException("insufficient funds in " + from);
        accounts.put(from, f.subtract(amount));
        accounts.put(to, t.add(amount));
    }

    public synchronized BigDecimal balance(String id) {
        BigDecimal b = id == null ? null : accounts.get(id);
        if (b == null) throw new IllegalArgumentException("unknown account");
        return b;
    }
}
