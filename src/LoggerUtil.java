public class LoggerUtil {

    public static void info(String tag, String msg) {
        System.out.println("[INFO][" + tag + "] " + msg);
    }

    public static void warn(String tag, String msg) {
        System.out.println("[WARN][" + tag + "] " + msg);
    }

    public static void error(String tag, String msg) {
        System.err.println("[ERROR][" + tag + "] " + msg);
    }

    public static void debug(String tag, String msg) {
        if (isDebugEnabled()) {
            System.out.println("[DEBUG][" + tag + "] " + msg);
        }
    }

    private static boolean isDebugEnabled() {
        // Manuell schaltbar z. B. durch System-Property
        return Boolean.getBoolean("debug");
    }
}
