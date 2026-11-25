package ae.dns;

import java.util.List;
import java.util.Objects;

/** A DNS message with exactly one question (class IN). */
public record Message(int id, boolean response, boolean aa, boolean tc, boolean rd, boolean ra, Rcode rcode,
        Name qname, RType qtype, List<Rr> answers, List<Rr> authority, List<Rr> additional) {
    public Message {
        if (id < 0 || id > 0xFFFF) {
            throw new IllegalArgumentException("id " + id);
        }
        Objects.requireNonNull(rcode);
        Objects.requireNonNull(qname);
        Objects.requireNonNull(qtype);
        answers = List.copyOf(answers);
        authority = List.copyOf(authority);
        additional = List.copyOf(additional);
    }

    /** A recursion-desired query. */
    public static Message query(int id, Name name, RType type) {
        return new Message(id, false, false, false, true, false, Rcode.NOERROR, name, type, List.of(), List.of(), List.of());
    }
}
