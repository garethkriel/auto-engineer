package ae.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.util.Objects;

/** Sends a message as numbered chunks, waiting for each ACK (stop-and-wait ARQ). */
public final class ReliableSender {
    private final DatagramSocket socket;
    private final SocketAddress target;
    private final int chunkSize;
    private final long ackTimeoutMillis;
    private final int maxRetries;

    public ReliableSender(DatagramSocket socket, SocketAddress target, int chunkSize,
            long ackTimeoutMillis, int maxRetries) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.target = Objects.requireNonNull(target, "target");
        if (chunkSize < 1 || ackTimeoutMillis < 1 || maxRetries < 0) {
            throw new IllegalArgumentException("chunkSize>=1, ackTimeoutMillis>=1, maxRetries>=0 required");
        }
        this.chunkSize = chunkSize;
        this.ackTimeoutMillis = ackTimeoutMillis;
        this.maxRetries = maxRetries;
    }

    /**
     * Sends {@code message} reliably.
     *
     * @return number of datagrams transmitted, retransmissions included
     * @throws IOException when a chunk is not acknowledged after all retries
     */
    public int send(byte[] message) throws IOException {
        Objects.requireNonNull(message, "message");
        int chunks = Math.max(1, (message.length + chunkSize - 1) / chunkSize);
        int transmitted = 0;
        byte[] rx = new byte[Packet.HEADER_SIZE + 16];
        for (int seq = 0; seq < chunks; seq++) {
            int from = seq * chunkSize;
            int to = Math.min(message.length, from + chunkSize);
            byte[] wire = Packet.data(seq, seq == chunks - 1,
                    java.util.Arrays.copyOfRange(message, from, to)).encode();
            boolean acked = false;
            for (int attempt = 0; attempt <= maxRetries && !acked; attempt++) {
                socket.send(new DatagramPacket(wire, wire.length, target));
                transmitted++;
                acked = awaitAck(seq, rx);
            }
            if (!acked) {
                throw new IOException("no ack for chunk " + seq + " after " + (maxRetries + 1) + " attempts");
            }
        }
        return transmitted;
    }

    private boolean awaitAck(int seq, byte[] rx) throws IOException {
        long deadline = System.nanoTime() + ackTimeoutMillis * 1_000_000L;
        while (true) {
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
            if (remainingMs < 1) {
                return false;
            }
            socket.setSoTimeout((int) remainingMs);
            DatagramPacket in = new DatagramPacket(rx, rx.length);
            try {
                socket.receive(in);
            } catch (SocketTimeoutException e) {
                return false;
            }
            var p = Packet.decode(rx, in.getLength());
            if (p.isPresent() && p.get().type() == Packet.Type.ACK && p.get().seq() == seq) {
                return true;
            }
        }
    }
}
