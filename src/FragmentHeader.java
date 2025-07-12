

import java.nio.ByteBuffer;

public class FragmentHeader {
    private int msgID;
    private int chunkNumber;
    private int totalChunks;

    public FragmentHeader(int msgID, int chunkNumber, int totalChunks) {
        this.msgID = msgID;
        this.chunkNumber = chunkNumber;
        this.totalChunks = totalChunks;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(10);
        buffer.putInt(msgID);
        buffer.putShort((short) chunkNumber);
        buffer.putShort((short) totalChunks);
        return buffer.array();
    }

    public static FragmentHeader fromBytes(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        int msgID = buffer.getInt();
        int chunkNumber = buffer.getShort() & 0xFFFF;
        int totalChunks = buffer.getShort() & 0xFFFF;
        return new FragmentHeader(msgID, chunkNumber, totalChunks);
    }

    // Getters
    public int getMsgID() { return msgID; }
    public int getChunkNumber() { return chunkNumber; }
    public int getTotalChunks() { return totalChunks; }
}
