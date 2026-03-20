import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ConnectionManager {
    private static int WINDOW_SIZE = 5; // Initial Go-Back-N window size
    private static final int MIN_WINDOW_SIZE = 1;
    private static final int MAX_WINDOW_SIZE = 10;
    private static final int TIMEOUT_MS = 1000;
    private static final double PACKET_LOSS_THRESHOLD = 0.2; // 20% loss triggers window reduction
    private static final int LOSS_CALCULATION_INTERVAL = 10; // Calculate loss every 10 packets

    private final DatagramSocket socket;
    private final InetAddress remoteIP;
    private final int remotePort;

    private int nextSeqNum = 0;
    private int base = 0;
    private int packetsSent = 0;
    private int packetsLost = 0;

    private final Map<Integer, byte[]> sentPackets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean connected = false;

    public ConnectionManager(DatagramSocket socket, InetAddress remoteIP, int remotePort) {
        this.socket = socket;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
    }

    public void connect() throws Exception {
        sendPacket(PacketHeader.PacketType.SYN, new byte[0]);
        while (!connected) {
            Thread.sleep(100);
        }
    }

    public void disconnect() throws Exception {
        sendPacket(PacketHeader.PacketType.FIN, new byte[0]);
        while (connected) {
            Thread.sleep(100);
        }
    }

    public void sendData(byte[] data) throws Exception {
        int chunkSize = 900;
        int totalChunks = (data.length + chunkSize - 1) / chunkSize;

        for (int i = 0; i < totalChunks; i++) {
            int start = i * chunkSize;
            int end = Math.min(start + chunkSize, data.length);
            byte[] chunk = Arrays.copyOfRange(data, start, end);
            sendPacket(PacketHeader.PacketType.FILE, chunk);
            sentPackets.put(nextSeqNum, chunk);
            if (base == nextSeqNum) {
                startTimer();
            }
            nextSeqNum++;
            packetsSent++;
            while (nextSeqNum >= base + WINDOW_SIZE) {
                Thread.sleep(50);
            }
            updateWindowSize(); // Adjust window size based on packet loss
        }
    }

    public void receiveAck(int ackNum) {
        if (ackNum >= base) {
            for (int i = base; i <= ackNum; i++) {
                sentPackets.remove(i);
            }
            base = ackNum + 1;
            if (base == nextSeqNum) {
                stopTimer();
            } else {
                startTimer();
            }
        }
    }

    private void updateWindowSize() {
        if (packetsSent >= LOSS_CALCULATION_INTERVAL) {
            double lossRate = (double) packetsLost / packetsSent;
            if (lossRate > PACKET_LOSS_THRESHOLD && WINDOW_SIZE > MIN_WINDOW_SIZE) {
                WINDOW_SIZE = Math.max(MIN_WINDOW_SIZE, WINDOW_SIZE / 2);
                System.out.println("Packet loss detected, reducing window size to: " + WINDOW_SIZE);
            } else if (lossRate < PACKET_LOSS_THRESHOLD / 2 && WINDOW_SIZE < MAX_WINDOW_SIZE) {
                WINDOW_SIZE = Math.min(MAX_WINDOW_SIZE, WINDOW_SIZE + 1);
                System.out.println("Stable connection, increasing window size to: " + WINDOW_SIZE);
            }
            packetsSent = 0;
            packetsLost = 0;
        }
    }

    private ScheduledFuture<?> timeoutTask;

    private void startTimer() {
        stopTimer();
        timeoutTask = timer.schedule(() -> {
            try {
                packetsLost += nextSeqNum - base;
                for (int seq = base; seq < nextSeqNum; seq++) {
                    byte[] pkt = sentPackets.get(seq);
                    if (pkt != null) {
                        sendPacket(PacketHeader.PacketType.FILE, pkt);
                    }
                }
                updateWindowSize();
                startTimer();
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

    private void sendPacket(PacketHeader.PacketType type, byte[] payload) throws Exception {
        PacketHeader header = new PacketHeader(
                socket.getLocalAddress(),
                socket.getLocalPort(),
                remoteIP,
                remotePort,
                type,
                payload.length,
                CRC.calculate(payload)
        );
        byte[] headerBytes = header.toBytes();
        byte[] packet = new byte[headerBytes.length + payload.length];
        System.arraycopy(headerBytes, 0, packet, 0, headerBytes.length);
        System.arraycopy(payload, 0, packet, headerBytes.length, payload.length);

        DatagramPacket dp = new DatagramPacket(packet, packet.length, remoteIP, remotePort);
        socket.send(dp);
    }
}