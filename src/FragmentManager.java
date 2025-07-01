// FragmentManager.java

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class FragmentManager {
    private static final int MAX_CHUNK_PAYLOAD = 980; // Beispiel: MTU 1000 - Header ca. 20 Bytes

    private final Map<Integer, MessageReassemblyBuffer> reassemblyBuffers = new ConcurrentHashMap<>();
    private int nextMessageId = 0;

    // Zerlegt Payload in Chunks mit Fragmentierungsheader (MessageID, ChunkNumber, TotalChunks)
    public List<byte[]> fragment(byte[] payload) {
        int totalChunks = (payload.length + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD;
        int messageId = getNextMessageId();
        List<byte[]> chunks = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_CHUNK_PAYLOAD;
            int end = Math.min(start + MAX_CHUNK_PAYLOAD, payload.length);
            byte[] chunkPayload = Arrays.copyOfRange(payload, start, end);

            ByteBuffer buffer = ByteBuffer.allocate(10 + chunkPayload.length);
            buffer.putShort((short) messageId);         // MessageID (2 Byte)
            buffer.putInt(i);                            // ChunkNumber (4 Byte)
            buffer.putInt(totalChunks);                  // TotalChunks (4 Byte)
            buffer.put(chunkPayload);                     // Payload

            chunks.add(buffer.array());
        }
        return chunks;
    }

    // Verarbeitet einen Chunk: Rückgabe des vollständigen Payloads, wenn komplett, sonst null
    public byte[] processChunk(byte[] chunkData) {
        ByteBuffer buffer = ByteBuffer.wrap(chunkData);
        int messageId = Short.toUnsignedInt(buffer.getShort());
        int chunkNumber = buffer.getInt();
        int totalChunks = buffer.getInt();

        byte[] payload = new byte[chunkData.length - 10];
        buffer.get(payload);

        MessageReassemblyBuffer reassemblyBuffer = reassemblyBuffers.computeIfAbsent(messageId,
                id -> new MessageReassemblyBuffer(totalChunks));

        reassemblyBuffer.addChunk(chunkNumber, payload);

        if (reassemblyBuffer.isComplete()) {
            reassemblyBuffers.remove(messageId);
            return reassemblyBuffer.assemble();
        } else {
            return null;
        }
    }

    private synchronized int getNextMessageId() {
        return nextMessageId++;
    }

    // Hilfsklasse für das Rekonstruieren einer Nachricht aus Chunks
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
