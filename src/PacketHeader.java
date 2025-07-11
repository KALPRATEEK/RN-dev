
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class PacketHeader {
    public InetAddress sourceIP;
    public int sourcePort;
    public InetAddress destIP;
    public int destPort;
    public PacketType type;
    public int length; // Length of payload
    public int checksum; // 32-bit CRC

    public static final int HEADER_SIZE = 19; // 4 + 2 + 4 + 2 + 1 + 2 + 4

    public PacketHeader(InetAddress sourceIP, int sourcePort,
                        InetAddress destIP, int destPort,
                        PacketType type, int length, int checksum) {
        if (sourceIP == null || destIP == null) {
            throw new IllegalArgumentException("Source or destination IP cannot be null");
        }
        if (sourcePort < 0 || sourcePort > 65535 || destPort < 0 || destPort > 65535) {
            throw new IllegalArgumentException("Invalid port: sourcePort=" + sourcePort + ", destPort=" + destPort);
        }
        if (type == null) {
            throw new IllegalArgumentException("Packet type cannot be null");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Invalid length: " + length);
        }
        this.sourceIP = sourceIP;
        this.sourcePort = sourcePort;
        this.destIP = destIP;
        this.destPort = destPort;
        this.type = type;
        this.length = length;
        this.checksum = checksum;
    }

    // Convert header to byte array for sending
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        try {
            System.out.println("Serializing PacketHeader: sourceIP=" + sourceIP.getHostAddress() +
                    ", sourcePort=" + sourcePort + ", destIP=" + destIP.getHostAddress() +
                    ", destPort=" + destPort + ", type=" + type + ", length=" + length +
                    ", checksum=" + checksum);
            buffer.put(sourceIP.getAddress()); // 4 bytes
            System.out.println("After sourceIP, position: " + buffer.position());
            buffer.putShort((short) sourcePort); // 2 bytes
            System.out.println("After sourcePort, position: " + buffer.position());
            buffer.put(destIP.getAddress()); // 4 bytes
            System.out.println("After destIP, position: " + buffer.position());
            buffer.putShort((short) destPort); // 2 bytes
            System.out.println("After destPort, position: " + buffer.position());
            buffer.put(type.getValue()); // 1 byte
            System.out.println("After type, position: " + buffer.position());
            buffer.putShort((short) length); // 2 bytes
            System.out.println("After length, position: " + buffer.position());
            buffer.putInt(checksum); // 4 bytes
            System.out.println("After checksum, position: " + buffer.position());
            if (buffer.position() != HEADER_SIZE) {
                throw new IllegalStateException("Buffer position mismatch: expected " + HEADER_SIZE + ", got " + buffer.position());
            }
            return buffer.array();
        } catch (Exception e) {
            System.err.println("Error in toBytes: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    // Create PacketHeader from byte array (received packet)
    public static PacketHeader fromBytes(byte[] data) throws Exception {
        if (data.length < HEADER_SIZE) {
            throw new Exception("Header too short: " + data.length + " bytes, expected " + HEADER_SIZE);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            byte[] srcIpBytes = new byte[4];
            buffer.get(srcIpBytes);
            InetAddress srcIP = InetAddress.getByAddress(srcIpBytes);
            int srcPort = Short.toUnsignedInt(buffer.getShort());
            byte[] dstIpBytes = new byte[4];
            buffer.get(dstIpBytes);
            InetAddress dstIP = InetAddress.getByAddress(dstIpBytes);
            int dstPort = Short.toUnsignedInt(buffer.getShort());
            byte typeByte = buffer.get();
            PacketType type = PacketType.fromValue(typeByte);
            int length = Short.toUnsignedInt(buffer.getShort());
            int checksum = buffer.getInt(); // 4 bytes

            System.out.println("Deserialized PacketHeader: srcIP=" + srcIP.getHostAddress() +
                    ", srcPort=" + srcPort + ", dstIP=" + dstIP.getHostAddress() +
                    ", dstPort=" + dstPort + ", type=" + type + ", length=" + length +
                    ", checksum=" + checksum);

            return new PacketHeader(srcIP, srcPort, dstIP, dstPort, type, length, checksum);
        } catch (Exception e) {
            System.err.println("Error in fromBytes: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    @Override
    public String toString() {
        return "PacketHeader{" +
                "sourceIP=" + sourceIP.getHostAddress() +
                ", sourcePort=" + sourcePort +
                ", destIP=" + destIP.getHostAddress() +
                ", destPort=" + destPort +
                ", type=" + type +
                ", length=" + length +
                ", checksum=" + checksum +
                '}';
    }

    public enum PacketType {
        FILE((byte) 0),
        MESSAGE((byte) 1),
        SYN((byte) 2),
        ACK((byte) 3),
        FIN((byte) 4),
        SYN_ACK((byte) 5),
        FIN_ACK((byte) 6);

        private final byte value;

        PacketType(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }

        public static PacketType fromValue(byte value) throws Exception {
            for (PacketType t : PacketType.values()) {
                if (t.value == value) return t;
            }
            throw new Exception("Invalid PacketType: " + value);
        }
    }
}
