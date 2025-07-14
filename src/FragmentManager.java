// FragmentManager.java mit Go-Back-N

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class FragmentManager {
    private static final int MAX_CHUNK_PAYLOAD = 950; // MTU 1000 - Header-Overhead
    private static final int WINDOW_SIZE = 5;         // Go-Back-N Fenstergröße
    private static final int TIMEOUT_MS = 200;        // Timeout für ACK-Warten

    private final Map<Integer, MessageReassemblyBuffer> reassemblyBuffers = new ConcurrentHashMap<>();
    private int nextMessageId = 0;
    private InetAddress myIp;
    private int myPort;
    private final java.util.Map<Integer, Integer> dataAck;

    public FragmentManager(InetAddress ip, int port, Map ackMap){
        myIp = ip;
        myPort = port;
        dataAck = ackMap;
    }


    public void sendWithGoBackN(int messageId, List<byte[]> fragments, DatagramSocket socket, PacketHeader.PacketType ptype, InetAddress ip, int port, int sourcePort) throws Exception {
        int base = 0;
        int nextSeq = 0;
        int total = fragments.size();
        PacketHeader header;

        long lastSendTime = System.currentTimeMillis();

        while (base < total) {
            // Sende neue Fragmente innerhalb des Fensters
            while (nextSeq < base + WINDOW_SIZE && nextSeq < total) {
                byte[] fragment = fragments.get(nextSeq);

                ByteBuffer buffer = ByteBuffer.allocate(19 + fragment.length);
                header = new PacketHeader(myIp, sourcePort, ip, port, ptype, fragment.length, CRC.calculate(fragment));

                buffer.put(header.toBytes());
                buffer.put(fragment);
                byte[] packetData = buffer.array();

                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
                LoggerUtil.header(header.toString());
                socket.send(packet);
                LoggerUtil.info("GoBackN", "Gesendet: Fragment " + nextSeq);
                nextSeq++;
            }

            // Warten auf ACK oder Timeout
            try {
                Thread.sleep(TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Prüfen, ob ACKs empfangen wurden
            Integer ackFrag = dataAck.get(messageId);
            if (ackFrag != null && ackFrag > base) {
                LoggerUtil.info("GoBackN", "ACK-Map: messageId=" + messageId + ", erwartet nun Fragment " + ackFrag);
                base = ackFrag;
            } else {
                LoggerUtil.warn("GoBackN", "Timeout – sende Fenster erneut ab Fragment " + base);
                nextSeq = base;
            }
        }

        LoggerUtil.info("GoBackN", "Alle Fragmente erfolgreich gesendet!");
    }

    public record FragmentedMessage(int messageId, List<byte[]> fragments) {}

    public FragmentedMessage fragment(byte[] payload) {
        int totalChunks = (payload.length + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD;
        int messageId = getNextMessageId();
        List<byte[]> chunks = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_CHUNK_PAYLOAD;
            int end = Math.min(start + MAX_CHUNK_PAYLOAD, payload.length);
            byte[] chunkPayload = Arrays.copyOfRange(payload, start, end);

            ByteBuffer buffer = ByteBuffer.allocate(10 + chunkPayload.length);
            buffer.putShort((short) messageId);
            buffer.putInt(i);
            buffer.putInt(totalChunks);
            buffer.put(chunkPayload);


            chunks.add(buffer.array());
        }

        return new FragmentedMessage(messageId, chunks);
    }

    public byte[] processChunk(byte[] chunkData, DatagramSocket socket, InetAddress senderIP, int senderPort, int expectedCRC) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(chunkData);
            int messageId = Short.toUnsignedInt(buffer.getShort());
            int chunkNumber = buffer.getInt();
            int totalChunks = buffer.getInt();

            int payloadLength = chunkData.length - 10;
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            int computedCRC = CRC.calculate(chunkData);
            LoggerUtil.debug("FragmentManager", "Header-CRC: " + expectedCRC);
            LoggerUtil.debug("FragmentManager", "Computed CRC: " + computedCRC);

            if (computedCRC != expectedCRC) {
                System.out.println("CRC-Fehler: Chunk verworfen (msg=" + messageId + ", chunk=" + chunkNumber + ")");
                return null;
            }

            // ACK senden
            sendAck(socket, senderIP, senderPort, messageId, chunkNumber);

            MessageReassemblyBuffer reassemblyBuffer = reassemblyBuffers.computeIfAbsent(messageId,
                    id -> new MessageReassemblyBuffer(totalChunks));
            reassemblyBuffer.addChunk(chunkNumber, payload);

            if (reassemblyBuffer.isComplete()) {
                reassemblyBuffers.remove(messageId);
                return reassemblyBuffer.assemble();
            }
        } catch (Exception e) {
            System.out.println("Fehler bei processChunk: " + e.getMessage());
        }
        return null;
    }

    private void sendAck(DatagramSocket socket, InetAddress ip, int port, int messageId, int fragNum) {
        try {
            ByteBuffer payload = ByteBuffer.allocate(10);
            payload.putShort((short) messageId);
            payload.putInt(fragNum + 1);
            payload.putInt(0); // TotalChunks bei ACK = 0 (nicht relevant)
            byte[] payloadArray = payload.array();

            PacketHeader header = new PacketHeader(
                    myIp,
                    myPort,
                    ip,
                    port,
                    PacketHeader.PacketType.DATA_ACK,
                    10, // payload length
                    CRC.calculate(payloadArray)
            );

            ByteBuffer ackPacketBuffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE + 10);
            ackPacketBuffer.put(header.toBytes());
            ackPacketBuffer.put(payloadArray);

            DatagramPacket ackPacket = new DatagramPacket(ackPacketBuffer.array(), ackPacketBuffer.capacity(), ip, port);
            LoggerUtil.goBackAck(messageId, fragNum, ip.getHostAddress(), port);
            LoggerUtil.header(header.toString());

            socket.send(ackPacket);
        } catch (Exception e) {
            System.out.println("ACK-Senden fehlgeschlagen: " + e.getMessage());
        }
    }

    private synchronized int getNextMessageId() {
        return nextMessageId++;
    }

    // Hilfsklasse zum Rekonstruieren aus Fragmenten
    private static class MessageReassemblyBuffer {
        private final int totalChunks;
        private final Map<Integer, byte[]> chunks = new ConcurrentHashMap<>();

        public MessageReassemblyBuffer(int totalChunks) {
            this.totalChunks = totalChunks;
        }

        public void addChunk(int chunkNumber, byte[] data) {
            chunks.put(chunkNumber, data);
        }

        public boolean isComplete() {
            return chunks.size() == totalChunks;
        }

        public byte[] assemble() {
            int totalLength = chunks.values().stream().mapToInt(a -> a.length).sum();
            byte[] fullPayload = new byte[totalLength];
            int pos = 0;
            for (int i = 0; i < totalChunks; i++) {
                byte[] chunk = chunks.get(i);
                System.arraycopy(chunk, 0, fullPayload, pos, chunk.length);
                pos += chunk.length;
            }
            return fullPayload;
        }
    }
}
