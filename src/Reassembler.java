
import java.util.*;

public class Reassembler {
    private final Map<Integer, Map<Integer, byte[]>> fragmentBuffer = new HashMap<>();
    private final Map<Integer, Integer> expectedChunks = new HashMap<>();

    public void addFragment(int msgID, int chunkNumber, int totalChunks, byte[] chunkData) {
        fragmentBuffer.putIfAbsent(msgID, new HashMap<>());
        expectedChunks.putIfAbsent(msgID, totalChunks);

        fragmentBuffer.get(msgID).put(chunkNumber, chunkData);
    }

    public boolean isComplete(int msgID) {
        return fragmentBuffer.containsKey(msgID)
                && fragmentBuffer.get(msgID).size() == expectedChunks.get(msgID);
    }

    public byte[] reassemble(int msgID) {
        if (!isComplete(msgID)) return null;

        Map<Integer, byte[]> chunks = fragmentBuffer.get(msgID);
        int totalSize = chunks.values().stream().mapToInt(a -> a.length).sum();
        byte[] fullData = new byte[totalSize];

        int offset = 0;
        for (int i = 0; i < expectedChunks.get(msgID); i++) {
            byte[] chunk = chunks.get(i);
            System.arraycopy(chunk, 0, fullData, offset, chunk.length);
            offset += chunk.length;
        }

        // Cleanup
        fragmentBuffer.remove(msgID);
        expectedChunks.remove(msgID);

        return fullData;
    }
}