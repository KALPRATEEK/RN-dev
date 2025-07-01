import java.net.InetAddress;

class RoutingEntry {
    InetAddress destIP;
    int destPort;
    InetAddress nextHopIP;
    int nextHopPort;
    int hopCount;

    RoutingEntry(InetAddress destIP, int destPort, InetAddress nextHopIP, int nextHopPort, int hopCount) {
        this.destIP = destIP;
        this.destPort = destPort;
        this.nextHopIP = nextHopIP;
        this.nextHopPort = nextHopPort;
        this.hopCount = hopCount;
    }
}