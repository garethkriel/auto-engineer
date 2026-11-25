package ae.dns;

import java.util.List;

/** Shared fixture: root -> test -> {example, corp, other, lame} over a wire-level {@link Network}. */
final class World {
    static final String ROOT = """
            $TTL 86400
            . IN SOA a.root. admin.root. 1 7200 3600 1209600 300
            . IN NS a.root.
            a.root. IN A 10.0.0.1
            test. IN NS ns.test.
            ns.test. IN A 10.0.1.1
            """;
    static final String TEST = """
            $TTL 86400
            @ IN SOA ns.test. admin.test. 1 7200 3600 1209600 300
            @ IN NS ns.test.
            ns IN A 10.0.1.1
            example IN NS ns.example.test.
            example IN NS ns2.example.test.
            ns.example IN A 10.0.2.1
            ns2.example IN A 10.0.2.2
            corp IN NS ns.example.test.
            other IN NS ns.other.test.
            ns.other IN A 10.0.3.1
            lame IN NS ns.lame.test.
            ns.lame IN A 10.0.4.1
            """;
    static final String EXAMPLE = """
            $TTL 300
            @ 3600 IN SOA ns.example.test. admin.example.test. 1 7200 3600 1209600 300
            @ 86400 IN NS ns.example.test.
            @ 86400 IN NS ns2.example.test.
            ns 86400 IN A 10.0.2.1
            ns2 86400 IN A 10.0.2.2
            www IN A 192.0.2.1
            alias 60 IN CNAME www
            ext IN CNAME www.corp.test.
            far IN CNAME target.other.test.
            l1 IN CNAME l2
            l2 IN CNAME l1
            mx IN MX 10 mail
            mail IN A 192.0.2.25
            multi 100 IN A 192.0.2.60
            multi 200 IN A 192.0.2.61
            txt IN TXT "v=1"
            *.wild IN A 192.0.2.99
            """;
    static final String CORP = """
            $TTL 300
            @ 3600 IN SOA ns.example.test. admin.corp.test. 1 7200 3600 1209600 120
            @ 86400 IN NS ns.example.test.
            www IN A 192.0.2.77
            mail IN A 192.0.2.78
            """;
    static final String OTHER = """
            $TTL 300
            @ 3600 IN SOA ns.other.test. admin.other.test. 1 7200 3600 1209600 300
            @ 86400 IN NS ns.other.test.
            ns 86400 IN A 10.0.3.1
            target IN A 198.51.100.7
            """;
    /** A broken server: serves the parent zone but is the delegated "lame.test" server, so it refers to itself. */
    static final String LAME_TEST = """
            $TTL 86400
            @ IN SOA ns.test. admin.test. 1 7200 3600 1209600 300
            @ IN NS ns.test.
            ns IN A 10.0.1.1
            lame IN NS ns.lame.test.
            ns.lame IN A 10.0.4.1
            """;

    static final int ROOT_ADDR = RData.A.parseAddress("10.0.0.1");

    final Network net = new Network();
    final List<Zone> zones;

    World() {
        Zone root = Zone.parse(".", ROOT);
        Zone test = Zone.parse("test", TEST);
        Zone example = Zone.parse("example.test", EXAMPLE);
        Zone corp = Zone.parse("corp.test", CORP);
        Zone other = Zone.parse("other.test", OTHER);
        net.add("10.0.0.1", new Server(List.of(root)));
        net.add("10.0.1.1", new Server(List.of(test)));
        net.add("10.0.2.1", new Server(List.of(example, corp)));
        net.add("10.0.2.2", new Server(List.of(example)));
        net.add("10.0.3.1", new Server(List.of(other)));
        net.add("10.0.4.1", new Server(List.of(Zone.parse("test", LAME_TEST))));
        zones = List.of(root, test, example, corp, other);
    }

    Resolver resolver(Clock clock) {
        return new Resolver(net, List.of(ROOT_ADDR), clock);
    }
}
