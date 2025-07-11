import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

// Manages fragmentation and reassembly of messages
public class FragmentManager {
    private static final int MAX_CHUNK_PAYLOAD = 980; // MTU 1000 - headers
    private static final int TIMEOUT_MS = 5000; // 5-second timeout for incomplete messages

    private final Map<Integer, MessageReassemblyBuffer> reassemblyBuffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(1);
    private int nextMessageId = 0;

    // Fragment payload into chunks with fragmentation header
    public List<byte[]> fragment(byte[] payload) {
        int totalChunks = (payload.length + MAX_CHUNK_PAYLOAD - 1) / MAX_CHUNK_PAYLOAD;
        int messageId = getNextMessageId();
        List<byte[]> chunks = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            int start = i * MAX_CHUNK_PAYLOAD;
            int end = Math.min(start + MAX_CHUNK_PAYLOAD, payload.length);
            byte[] chunkPayload = Arrays.copyOfRange(payload, start, end);

            ByteBuffer buffer = ByteBuffer.allocate(10 + chunkPayload.length);
            buffer.putShort((short) messageId); // MessageID (2 bytes)
            buffer.putInt(i); // ChunkNumber (4 bytes)
            buffer.putInt(totalChunks); // TotalChunks (4 bytes)
            buffer.put(chunkPayload); // Payload
            chunks.add(buffer.array());
        }
        return chunks;
    }

    // Process a chunk, verify checksum, and return assembled payload if complete
    public byte[] processChunk(byte[] chunkData, int expectedChecksum) {
        // Verify checksum
        int calculatedChecksum = CRC.calculate(chunkData);
        if (calculatedChecksum != expectedChecksum) {
            System.out.println("Checksum mismatch: expected " + expectedChecksum + ", got " + calculatedChecksum);
            return null; // Discard invalid chunk
        }

        ByteBuffer buffer = ByteBuffer.wrap(chunkData);
        int messageId = Short.toUnsignedInt(buffer.getShort());
        int chunkNumber = buffer.getInt();
        int totalChunks = buffer.getInt();

        byte[] payload = new byte[chunkData.length - 10];
        buffer.get(payload);

        MessageReassemblyBuffer reassemblyBuffer = reassemblyBuffers.computeIfAbsent(messageId,
                id -> {
                    MessageReassemblyBuffer buf = new MessageReassemblyBuffer(totalChunks);
                    // Schedule timeout for incomplete messages
                    timeoutExecutor.schedule(() -> {
                        if (!buf.isComplete()) { // Use buf instead of reassemblyBuffer
                            reassemblyBuffers.remove(messageId);
                            System.out.println("Timeout: Discarded incomplete message ID " + messageId);
                        }
                    }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    return buf;
                });

        reassemblyBuffer.addChunk(chunkNumber, payload);

        if (reassemblyBuffer.isComplete()) {
            reassemblyBuffers.remove(messageId);
            return reassemblyBuffer.assemble();
        }
        return null;
    }

    private synchronized int getNextMessageId() {
        return nextMessageId++;
    }

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

    public void shutdown() {
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
        }
    }
}