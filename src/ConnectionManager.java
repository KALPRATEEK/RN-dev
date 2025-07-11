import java.net.*;
import java.util.*;
import java.util.concurrent.*;

// Manages reliable connections with Go-Back-N, flow control, and congestion control
public class ConnectionManager {
    private static final int INITIAL_WINDOW_SIZE = 5;
    private static final int TIMEOUT_MS = 1000;
    private static final int MAX_WINDOW_SIZE = 10;
    private static final int MIN_WINDOW_SIZE = 1;
    private static final double LOSS_THRESHOLD = 0.1; // Reduce window if loss rate > 10%

    private final DatagramSocket socket;
    private final InetAddress remoteIP;
    private final int remotePort;
    private int windowSize = INITIAL_WINDOW_SIZE;
    private int nextSeqNum = 0;
    private int base = 0;
    private volatile boolean connected = false;
    private volatile int advertisedWindow = INITIAL_WINDOW_SIZE; // Receiver's window
    private int packetLossCount = 0;
    private int packetSentCount = 0;

    private final Map<Integer, byte[]> sentPackets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    public ConnectionManager(DatagramSocket socket, InetAddress remoteIP, int remotePort) {
        this.socket = socket;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
    }

    // Establish connection with three-way handshake
    public void connect() throws Exception {
        sendPacket(PacketHeader.PacketType.SYN, new byte[0], nextSeqNum);
        sentPackets.put(nextSeqNum, new byte[0]);
        startTimer();
        while (!connected) {
            Thread.sleep(100);
        }
    }

    // Terminate connection with FIN, FIN-ACK, and ACK
    public void disconnect() throws Exception {
        // Ensure all data is sent before initiating teardown
        while (base < nextSeqNum) {
            Thread.sleep(100); // Wait for pending packets
        }
        sendPacket(PacketHeader.PacketType.FIN, new byte[0], nextSeqNum);
        sentPackets.put(nextSeqNum, new byte[0]);
        startTimer();
        while (connected) {
            Thread.sleep(100);
        }
    }

    // Send data reliably with Go-Back-N
    public void sendData(byte[] data) throws Exception {
        FragmentManager fm = new FragmentManager();
        List<byte[]> chunks = fm.fragment(data);
        for (byte[] chunk : chunks) {
            while (nextSeqNum >= base + Math.min(windowSize, advertisedWindow)) {
                Thread.sleep(50); // Respect flow control
            }
            sendPacket(PacketHeader.PacketType.FILE, chunk, nextSeqNum);
            sentPackets.put(nextSeqNum, chunk);
            packetSentCount++;
            if (base == nextSeqNum) {
                startTimer();
            }
            nextSeqNum++;
        }
    }

    // Process received ACKs with window size
    public void receiveAck(int seqNum, int advertisedWindow) {
        this.advertisedWindow = advertisedWindow;
        if (seqNum >= base) {
            base = seqNum + 1;
            sentPackets.entrySet().removeIf(e -> e.getKey() < base);
            if (base == nextSeqNum) {
                stopTimer();
            } else {
                startTimer();
            }
            // Congestion control: Increase window if no recent losses
            if (packetSentCount > 0 && (double) packetLossCount / packetSentCount < LOSS_THRESHOLD) {
                windowSize = Math.min(windowSize + 1, MAX_WINDOW_SIZE);
            }
        } else {
            packetLossCount++;
            // Congestion control: Reduce window on loss
            windowSize = Math.max(windowSize / 2, MIN_WINDOW_SIZE);
        }
    }

    // Process received packets (called by ChatNode)
    public void processPacket(PacketHeader header, byte[] payload) throws Exception {
        if (header.type == PacketHeader.PacketType.SYN) {
            sendPacket(PacketHeader.PacketType.SYN_ACK, new byte[0], 0);
        } else if (header.type == PacketHeader.PacketType.SYN_ACK) {
            sendPacket(PacketHeader.PacketType.ACK, new byte[0], 0);
            connected = true;
        } else if (header.type == PacketHeader.PacketType.ACK) {
            receiveAck(header.length, INITIAL_WINDOW_SIZE); // Length field repurposed for seqNum
        } else if (header.type == PacketHeader.PacketType.FIN) {
            sendPacket(PacketHeader.PacketType.FIN_ACK, new byte[0], 0);
        } else if (header.type == PacketHeader.PacketType.FIN_ACK) {
            sendPacket(PacketHeader.PacketType.ACK, new byte[0], 0);
            connected = false;
        }
    }

    private ScheduledFuture<?> timeoutTask;

    private void startTimer() {
        stopTimer();
        timeoutTask = timer.schedule(() -> {
            try {
                packetLossCount++;
                // Congestion control: Reduce window on timeout
                windowSize = Math.max(windowSize / 2, MIN_WINDOW_SIZE);
                // Go-Back-N: Resend all packets from base
                for (int seq = base; seq < nextSeqNum; seq++) {
                    byte[] pkt = sentPackets.get(seq);
                    if (pkt != null) {
                        sendPacket(PacketHeader.PacketType.FILE, pkt, seq);
                        packetSentCount++;
                    }
                }
                if (base < nextSeqNum) {
                    startTimer();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void stopTimer() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }

    private void sendPacket(PacketHeader.PacketType type, byte[] payload, int seqNum) throws Exception {
        PacketHeader header = new PacketHeader(
                socket.getLocalAddress(),
                socket.getLocalPort(),
                remoteIP,
                remotePort,
                type,
                seqNum, // Repurpose length field for sequence number
                CRC.calculate(payload)
        );
        byte[] headerBytes = header.toBytes();
        byte[] packet = new byte[headerBytes.length + payload.length];
        System.arraycopy(headerBytes, 0, packet, 0, headerBytes.length);
        System.arraycopy(payload, 0, packet, headerBytes.length, payload.length);

        DatagramPacket dp = new DatagramPacket(packet, packet.length, remoteIP, remotePort);
        socket.send(dp);
    }

    public void shutdown() {
        timer.shutdown();
        try {
            if (!timer.awaitTermination(10, TimeUnit.SECONDS)) {
                timer.shutdownNow();
            }
        } catch (InterruptedException e) {
            timer.shutdownNow();
        }
    }
}