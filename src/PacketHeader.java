// PacketHeader.java

import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class PacketHeader {
    public InetAddress sourceIP;
    public int sourcePort;
    public InetAddress destIP;
    public int destPort;
    public PacketType type;
    public int length; // Length of payload
    public int checksum; // 32-bit CRC

    public static final int HEADER_SIZE = 19; // laut Spezifikation

    public PacketHeader(InetAddress sourceIP, int sourcePort,
                        InetAddress destIP, int destPort,
                        PacketType type, int length, int checksum) {
        this.sourceIP = sourceIP;
        this.sourcePort = sourcePort;
        this.destIP = destIP;
        this.destPort = destPort;
        this.type = type;
        this.length = length;
        this.checksum = checksum;
    }

    // Wandelt Header in Byte-Array für Versand um
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        buffer.put(sourceIP.getAddress()); // 4 bytes
        buffer.putShort((short) sourcePort); // 2 bytes
        buffer.put(destIP.getAddress()); // 4 bytes
        buffer.putShort((short) destPort); // 2 bytes
        buffer.put(type.getValue()); // 1 byte
        buffer.putShort((short) length); // 2 bytes
        buffer.putInt(checksum); // 4 bytes
        return buffer.array();
    }

    // Erzeugt PacketHeader aus Byte-Array (empfangenes Paket)
    public static PacketHeader fromBytes(byte[] data) throws Exception {
        if (data.length < HEADER_SIZE) {
            throw new Exception("Header zu kurz");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
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
        int checksum = buffer.getInt(); // 4 bytes (updated)

        return new PacketHeader(srcIP, srcPort, dstIP, dstPort, type, length, checksum);
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

    // Enum für Paket-Typen
    public enum PacketType {
        FILE((byte) 0),
        MESSAGE((byte) 1),
        SYN((byte) 2),
        ACK((byte) 3),
        FIN((byte) 4),
        SYN_ACK((byte) 5),
        FIN_ACK((byte) 6),
        DATA_ACK((byte)7);

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
            throw new Exception("Ungültiger PacketType: " + value);
        }
    }
}
