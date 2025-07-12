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


    // Go-Back-N Sendelogik
    public void sendWithGoBackN(int messageId, List<byte[]> fragments, DatagramSocket socket, InetAddress ip, int port) throws Exception {
        int base = 0;
        int nextSeq = 0;
        int total = fragments.size();

        socket.setSoTimeout(TIMEOUT_MS);

        while (base < total) {
            while (nextSeq < base + WINDOW_SIZE && nextSeq < total) {
                byte[] fragment = fragments.get(nextSeq);

                ByteBuffer bufferWithType = ByteBuffer.allocate(1 + fragment.length);
                bufferWithType.put(PacketHeader.PacketType.FILE.getValue());
                bufferWithType.put(fragment);
                byte[] packetData = bufferWithType.array();

                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
                socket.send(packet);
                LoggerUtil.info("GoBackN", "Gesendet: Fragment " + nextSeq);
                nextSeq++;
            }

            try {
                byte[] ackBuf = new byte[6];
                DatagramPacket ackPacket = new DatagramPacket(ackBuf, ackBuf.length);
                socket.receive(ackPacket);

                ByteBuffer ackBuffer = ByteBuffer.wrap(ackBuf);
                if (ackBuffer.get() == (byte) 0x7F) {
                    int ackMsgId = Short.toUnsignedInt(ackBuffer.getShort());
                    byte type = ackBuffer.get();
                    int ackFrag = Short.toUnsignedInt(ackBuffer.getShort());

                    if (type == 1 && ackMsgId == messageId) {
                        LoggerUtil.info("GoBackN", "ACK erhalten für Fragment " + ackFrag);
                        base = ackFrag + 1;
                    } else {
                        LoggerUtil.warn("GoBackN", "ACK ignoriert – falsche MessageID oder Typ");
                    }
                }
            } catch (SocketTimeoutException e) {
                LoggerUtil.warn("GoBackN", "Timeout – sende Fenster erneut ab Fragment " + base);
                nextSeq = base;
            }
        }
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

            int checksum = CRC.calculate(chunkPayload);

            ByteBuffer buffer = ByteBuffer.allocate(10 + chunkPayload.length + 4);
            buffer.putShort((short) messageId);
            buffer.putInt(i);
            buffer.putInt(totalChunks);
            buffer.put(chunkPayload);
            buffer.putInt(checksum);

            chunks.add(buffer.array());
        }

        return new FragmentedMessage(messageId, chunks);
    }


    // Verarbeitet Chunk + sendet optional ACK zurück
    public byte[] processChunk(byte[] chunkData, DatagramSocket socket, InetAddress senderIP, int senderPort) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(chunkData);
            int messageId = Short.toUnsignedInt(buffer.getShort());
            int chunkNumber = buffer.getInt();
            int totalChunks = buffer.getInt();

            int payloadLength = chunkData.length - 10 - 4;
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            int receivedCRC = buffer.getInt();
            int computedCRC = CRC.calculate(payload);
            LoggerUtil.debug("FragmentManager", "Empfangener CRC32: " + receivedCRC);
            LoggerUtil.debug("FragmentManager", "Berechneter CRC32: " + computedCRC);


            if (receivedCRC != computedCRC) {
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
            ByteBuffer ack = ByteBuffer.allocate(6);
            ack.put((byte) 0x7F); // ACK-Präfix
            ack.putShort((short) messageId);
            ack.put((byte) 1); // ACK-Typ
            ack.putShort((short) fragNum);
            DatagramPacket ackPacket = new DatagramPacket(ack.array(), ack.capacity(), ip, port);
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
