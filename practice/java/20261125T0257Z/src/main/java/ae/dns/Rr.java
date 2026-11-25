package ae.dns;

import java.util.Objects;

/** A resource record (class IN). TTL is 0..2^31-1 seconds. */
public record Rr(Name name, long ttl, RData data) {
    public static final long MAX_TTL = 0x7FFFFFFFL;

    public Rr {
        Objects.requireNonNull(name);
        Objects.requireNonNull(data);
        if (ttl < 0 || ttl > MAX_TTL) {
            throw new IllegalArgumentException("ttl " + ttl);
        }
    }

    public RType type() {
        return data.type();
    }

    public Rr withTtl(long newTtl) {
        return new Rr(name, newTtl, data);
    }

    public Rr withName(Name newName) {
        return new Rr(newName, ttl, data);
    }
}
