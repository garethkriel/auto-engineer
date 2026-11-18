package ae.udp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Receives one message sent by {@link ReliableSender}. A drop policy allows deterministic
 * fault injection: it is asked about every received datagram (index 0,1,2,...) and about
 * every ACK about to be sent (index -1, -2, ...); {@code true} means the datagram is lost.
 */
public final class ReliableReceiver {
    private final DatagramSocket socket;
    private final Predicate<Integer> dropPolicy;
    private final long lingerMillis;
    private int received;
    private int acksAttempted;

    public ReliableReceiver(DatagramSocket socket, Predicate<Integer> dropPolicy, long lingerMillis) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.dropPolicy = Objects.requireNonNull(dropPolicy, "dropPolicy");
        if (lingerMillis < 0) {
            throw new IllegalArgumentException("lingerMillis must be >= 0");
        }
        this.lingerMillis = lingerMillis;
    }

    /** Blocks until a complete message has arrived and the linger period has passed. */
    public byte[] receive(long timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[65_535];
        int expected = 0;
        while (true) {
            DatagramPacket in = next(buf, deadline);
            Optional<Packet> parsed = Packet.decode(buf, in.getLength());
            if (dropPolicy.test(received++) || parsed.isEmpty()) {
                continue;
            }
            Packet p = parsed.get();
            if (p.type() != Packet.Type.DATA) {
                continue;
            }
            if (p.seq() == expected) {
                out.writeBytes(p.payload());
                ack(p.seq(), in.getSocketAddress());
                expected++;
                if (p.last()) {
                    linger(buf, p.seq());
                    return out.toByteArray();
                }
            } else if (p.seq() < expected) {
                ack(p.seq(), in.getSocketAddress());
            }
            // future sequence numbers are neither accepted nor acknowledged
        }
    }

    private void linger(byte[] buf, int lastSeq) throws IOException {
        long end = System.nanoTime() + lingerMillis * 1_000_000L;
        while (true) {
            DatagramPacket in;
            try {
                in = next(buf, end);
            } catch (SocketTimeoutException e) {
                return;
            }
            Optional<Packet> parsed = Packet.decode(buf, in.getLength());
            if (dropPolicy.test(received++) || parsed.isEmpty()) {
                continue;
            }
            Packet p = parsed.get();
            if (p.type() == Packet.Type.DATA && p.seq() == lastSeq) {
                ack(lastSeq, in.getSocketAddress());
                end = System.nanoTime() + lingerMillis * 1_000_000L;
            }
        }
    }

    private DatagramPacket next(byte[] buf, long deadlineNanos) throws IOException {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMs < 1) {
            throw new SocketTimeoutException("receive deadline passed");
        }
        socket.setSoTimeout((int) remainingMs);
        DatagramPacket in = new DatagramPacket(buf, buf.length);
        socket.receive(in);
        return in;
    }

    private void ack(int seq, SocketAddress to) throws IOException {
        int index = -1 - acksAttempted++;
        if (dropPolicy.test(index)) {
            return;
        }
        byte[] wire = Packet.ack(seq).encode();
        socket.send(new DatagramPacket(wire, wire.length, to));
    }
}
