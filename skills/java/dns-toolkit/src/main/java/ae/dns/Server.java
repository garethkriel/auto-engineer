package ae.dns;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An authoritative server over one or more zones. */
public final class Server {
    private static final int MAX_CHAIN = 8;

    private final List<Zone> zones;

    public Server(List<Zone> zones) {
        this.zones = List.copyOf(zones);
    }

    private Zone zoneFor(Name name) {
        Zone best = null;
        for (Zone z : zones) {
            if (name.isSubdomainOf(z.origin()) && (best == null || z.origin().labelCount() > best.origin().labelCount())) {
                best = z;
            }
        }
        return best;
    }

    public Message handle(Message q) {
        if (q.response()) {
            return reply(q, Rcode.FORMERR, false, List.of(), List.of(), List.of());
        }
        List<Rr> answers = new ArrayList<>();
        Set<Name> visited = new HashSet<>();
        Name cur = q.qname();
        visited.add(cur);
        for (int hop = 0; hop <= MAX_CHAIN; hop++) {
            Zone z = zoneFor(cur);
            if (z == null) {
                if (answers.isEmpty()) {
                    return reply(q, Rcode.REFUSED, false, List.of(), List.of(), List.of());
                }
                break; // a chain that leaves our zones: return what we have
            }
            Zone.Result r = z.lookup(cur, q.qtype());
            switch (r.kind()) {
                case ANSWER -> {
                    answers.addAll(r.records());
                    return reply(q, Rcode.NOERROR, true, answers, List.of(), additional(z, r.records()));
                }
                case CNAME -> {
                    Rr cn = r.records().get(0);
                    answers.add(cn);
                    cur = ((RData.Cname) cn.data()).target();
                    if (!visited.add(cur)) {
                        return reply(q, Rcode.NOERROR, true, answers, List.of(), List.of()); // loop: stop
                    }
                }
                case NODATA -> {
                    return reply(q, Rcode.NOERROR, true, answers, List.of(z.soa()), List.of());
                }
                case NXDOMAIN -> {
                    return reply(q, Rcode.NXDOMAIN, true, answers, List.of(z.soa()), List.of());
                }
                case REFERRAL -> {
                    if (answers.isEmpty()) {
                        List<Rr> glue = new ArrayList<>();
                        for (Rr ns : r.records()) {
                            glue.addAll(z.addresses(((RData.Ns) ns.data()).host()));
                        }
                        return reply(q, Rcode.NOERROR, false, List.of(), r.records(), glue);
                    }
                    return reply(q, Rcode.NOERROR, true, answers, List.of(), List.of()); // chain entered a delegation
                }
                case NOT_IN_ZONE -> throw new IllegalStateException("zoneFor returned a zone not containing the name");
            }
        }
        return reply(q, Rcode.NOERROR, true, answers, List.of(), List.of()); // chain limit reached
    }

    private static List<Rr> additional(Zone z, List<Rr> answers) {
        List<Rr> out = new ArrayList<>();
        for (Rr r : answers) {
            switch (r.data()) {
                case RData.Ns ns -> out.addAll(z.addresses(ns.host()));
                case RData.Mx mx -> out.addAll(z.addresses(mx.host()));
                default -> { }
            }
        }
        return out;
    }

    private static Message reply(Message q, Rcode rc, boolean aa, List<Rr> an, List<Rr> ns, List<Rr> ar) {
        return new Message(q.id(), true, aa, false, q.rd(), false, rc, q.qname(), q.qtype(), an, ns, ar);
    }
}
