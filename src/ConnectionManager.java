// ConnectionManager.java

import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class ConnectionManager {
    private static final int WINDOW_SIZE = 5; // Go-Back-N Fenstergröße
    private static final int TIMEOUT_MS = 1000;

    private final DatagramSocket socket;
    private final InetAddress remoteIP;
    private final int remotePort;

    private int nextSeqNum = 0;
    private int base = 0;

    private final Map<Integer, byte[]> sentPackets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    private volatile boolean connected = false;

    public ConnectionManager(DatagramSocket socket, InetAddress remoteIP, int remotePort) {
        this.socket = socket;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
    }

    // Verbindungsaufbau mit SYN und SYN_ACK
    public void connect() throws Exception {
        sendPacket(PacketHeader.PacketType.SYN, new byte[0]);
        while (!connected) {
            // Warte auf SYN_ACK
            Thread.sleep(100);
        }
    }

    // Verbindung beenden mit FIN und FIN_ACK
    public void disconnect() throws Exception {
        sendPacket(PacketHeader.PacketType.FIN, new byte[0]);
        while (connected) {
            // Warte auf FIN_ACK
            Thread.sleep(100);
        }
    }

    // Sende Daten zuverlässig mit Go-Back-N
    public void sendData(byte[] data) throws Exception {
        int chunkSize = 900; // Beispielgröße, um MTU zu beachten
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
            // Fenstergröße beachten
            while (nextSeqNum >= base + WINDOW_SIZE) {
                Thread.sleep(50);
            }
        }
    }

    // Empfangene ACKs verarbeiten
    public void receiveAck(int ackNum) {
        if (ackNum >= base) {
            base = ackNum + 1;
            if (base == nextSeqNum) {
                stopTimer();
            } else {
                startTimer();
            }
        }
    }

    private ScheduledFuture<?> timeoutTask;

    private void startTimer() {
        stopTimer();
        timeoutTask = timer.schedule(() -> {
            try {
                // Go-Back-N: Alle Pakete ab base neu senden
                for (int seq = base; seq < nextSeqNum; seq++) {
                    byte[] pkt = sentPackets.get(seq);
                    if (pkt != null) {
                        sendPacket(PacketHeader.PacketType.FILE, pkt);
                    }
                }
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
        // Header erzeugen und senden (vereinfachte Version)
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