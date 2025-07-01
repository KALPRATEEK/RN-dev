// CRC16.java

public class CRC16 {
    private static final int POLYNOMIAL = 0x1021; // CRC16-CCITT

    public static int calculate(byte[] data) {
        int crc = 0xFFFF; // Startwert

        for (byte b : data) {
            crc ^= (b << 8) & 0xFFFF;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ POLYNOMIAL) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
