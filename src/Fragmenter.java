
import java.util.*;

public class Fragmenter {
    public static List<byte[]> fragmentData(byte[] data, int mtu, int msgID) {
        List<byte[]> fragments = new ArrayList<>();
        int totalChunks = (int) Math.ceil((double) data.length / mtu);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * mtu;
            int end = Math.min(start + mtu, data.length);
            byte[] chunkData = Arrays.copyOfRange(data, start, end);

            // Construct FragmentHeader (10 bytes)
            FragmentHeader fh = new FragmentHeader(msgID, i, totalChunks);
            byte[] fhBytes = fh.toBytes();

            // Combine: FragmentHeader + chunkData
            byte[] fragment = new byte[fhBytes.length + chunkData.length];
            System.arraycopy(fhBytes, 0, fragment, 0, fhBytes.length);
            System.arraycopy(chunkData, 0, fragment, fhBytes.length, chunkData.length);

            fragments.add(fragment);
        }
        return fragments;
    }
}
