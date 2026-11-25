package ae.dns;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-process network of servers. Every query and reply crosses {@link Wire} so the codec is always exercised. */
public final class Network implements Transport {
    private final Map<Integer, Server> servers = new HashMap<>();
    private final Set<Integer> down = new HashSet<>();
    private final List<String> log = new ArrayList<>();

    public void add(String address, Server server) {
        servers.put(RData.A.parseAddress(address), server);
    }

    public void setDown(String address, boolean isDown) {
        int a = RData.A.parseAddress(address);
        if (isDown) {
            down.add(a);
        } else {
            down.remove(a);
        }
    }

    /** One entry per query sent (including those to servers that are down): {@code "10.0.0.1 www.example.test. A"}. */
    public List<String> queryLog() {
        return List.copyOf(log);
    }

    public int queryCount() {
        return log.size();
    }

    @Override
    public Message send(int serverAddress, Message query) {
        log.add(new RData.A(serverAddress) + " " + query.qname() + " " + query.qtype());
        Server s = servers.get(serverAddress);
        if (s == null || down.contains(serverAddress)) {
            return null;
        }
        Message received = Wire.decode(Wire.encode(query));
        return Wire.decode(Wire.encode(s.handle(received)));
    }
}
