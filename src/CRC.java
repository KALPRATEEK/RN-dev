// Renamed from CRC32.java to reflect CRC32 usage
import java.util.zip.CRC32;
public class CRC {
    public static int calculate(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue(); // Returns 32-bit (4-byte) checksum
    }
}