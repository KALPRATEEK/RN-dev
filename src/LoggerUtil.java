import java.net.InetSocketAddress;

public class LoggerUtil {

    public static boolean enabled = false; // Standard: Logging ist an

    public static void info(String tag, String msg) {
        if (enabled) {
            System.out.println("[INFO][" + tag + "] " + msg);
        }
    }

    public static void warn(String tag, String msg) {
        if (enabled) {
            System.out.println("[WARN][" + tag + "] " + msg);
        }
    }

    public static void error(String tag, String msg) {
        if (enabled) {
            System.err.println("[ERROR][" + tag + "] " + msg);
        }
    }

    public static void debug(String tag, String msg) {
        if (enabled && isDebugEnabled()) {
            System.out.println("[DEBUG][" + tag + "] " + msg);
        }
    }

    private static boolean isDebugEnabled() {
        return enabled; // Kann optional später noch über ein Flag gesteuert werden
    }

    public static void sendPacket(String neighbor){
        if (enabled)
        {
            System.out.println("Sending packet to: " + neighbor);
        }
    }

    public static void sendLength(int length) {
        System.out.println("Packet length: " + length);
    }

    public static void packetData(byte[] packetData) {
        if (enabled){
            System.out.print("Packet data (decimal): [");
            for (int i = 0; i < packetData.length; i++) {
                System.out.print(Byte.toUnsignedInt(packetData[i]));
                if (i < packetData.length - 1) {
                    System.out.print(", ");
                }
            }
            System.out.println("]");
        }
    }


    public static void poisonedUpdate(InetSocketAddress neighbor) {
        if (enabled){
            System.out.println("Poisoned Update gesendet an " + neighbor);
        }
    }
    public static void syn(String key) {
        if (enabled){
            System.out.println("SYN empfangen von " + key + ", SYN-ACK gesendet");
        }
    }


    public static void fin(String key) {
        if (enabled){
            System.out.println("FIN empfangen von " + key + ", FIN-ACK gesendet, Verbindung geschlossen");
        }
    }

    public static void finAck(String key) {
        if (enabled){
            System.out.println("FIN-ACK empfangen von " + key + ", Verbindung geschlossen");
        }
    }


}
