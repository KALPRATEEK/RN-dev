import java.util.zip.CRC32;

public class CRC {
    /**
     * Calculates CRC32 checksum of the given byte array.
     *
     * @param data The input data
     * @return 32-bit checksum as int
     */
    public static int calculate(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    /**
     * Appends a CRC32 checksum in front of the given data.
     *
     * @param payload Data to protect
     * @return New byte array: [CRC (4 bytes)] + [original data]
     */
    public static byte[] addCRC(byte[] payload) {
        int checksum = calculate(payload);
        byte[] result = new byte[4 + payload.length];

        // Add checksum
        result[0] = (byte) ((checksum >> 24) & 0xFF);
        result[1] = (byte) ((checksum >> 16) & 0xFF);
        result[2] = (byte) ((checksum >> 8) & 0xFF);
        result[3] = (byte) (checksum & 0xFF);

        // Copy payload
        System.arraycopy(payload, 0, result, 4, payload.length);
        return result;
    }

    /**
     * Validates a CRC32 checksum from a combined array of [CRC (4 bytes)] + [data].
     *
     * @param dataWithCRC The input with checksum prefix
     * @return true if valid, false if corrupted
     */
    public static boolean isValid(byte[] dataWithCRC) {
        if (dataWithCRC.length < 4) return false;

        // Extract received CRC
        int receivedCRC = ((dataWithCRC[0] & 0xFF) << 24)
                | ((dataWithCRC[1] & 0xFF) << 16)
                | ((dataWithCRC[2] & 0xFF) << 8)
                | (dataWithCRC[3] & 0xFF);

        // Extract payload
        byte[] payload = new byte[dataWithCRC.length - 4];
        System.arraycopy(dataWithCRC, 4, payload, 0, payload.length);

        int calculatedCRC = calculate(payload);
        return receivedCRC == calculatedCRC;
    }

    /**
     * Extracts payload without CRC from a validated buffer.
     */
    public static byte[] extractPayload(byte[] dataWithCRC) {
        byte[] payload = new byte[dataWithCRC.length - 4];
        System.arraycopy(dataWithCRC, 4, payload, 0, payload.length);
        return payload;
    }
}