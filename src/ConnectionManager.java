import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class ConnectionManager {
    private final DatagramSocket socket;
    private final InetAddress remoteIP;
    private final int remotePort;
    private int sequenceNumber = 0; // Manage sequence number here
    private int advertisedWindow = 10; // Example window size
    private final Map<Integer, byte[]> sentPackets = new ConcurrentHashMap<>();
    private volatile boolean connected = false;
    private volatile boolean running = true;
    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
    private final FragmentManager fragmentManager = new FragmentManager(); // Instance for fragmenting

    public ConnectionManager(DatagramSocket socket, InetAddress remoteIP, int remotePort) {
        if (socket == null) {
            throw new IllegalArgumentException("Socket cannot be null");
        }
        if (remoteIP == null) {
            throw new IllegalArgumentException("Remote IP cannot be null");
        }
        if (!(remoteIP instanceof Inet4Address)) {
            throw new IllegalArgumentException("Remote IP must be IPv4");
        }
        if (remotePort <= 0 || remotePort > 65535) {
            throw new IllegalArgumentException("Invalid remote port: " + remotePort);
        }
        this.socket = socket;
        this.remoteIP = remoteIP;
        this.remotePort = remotePort;
        System.out.println("ConnectionManager initialized for " + remoteIP.getHostAddress() + ":" + remotePort);
    }

    public void connect() {
        try {
            if (socket.isClosed()) {
                throw new IllegalStateException("Socket is closed");
            }
            if (!(socket.getLocalAddress() instanceof Inet4Address)) {
                throw new IllegalStateException("Local address must be IPv4");
            }
            System.out.println("Sending SYN to " + remoteIP.getHostAddress() + ":" + remotePort);
            sendPacket(PacketHeader.PacketType.SYN, new byte[0]);
            // Simplified handshake: assume SYN-ACK and ACK are handled in processPacket
            connected = true;
        } catch (Exception e) {
            System.err.println("Fehler beim Verbindungsaufbau zu " + remoteIP.getHostAddress() + ":" + remotePort + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void sendData(byte[] data) {
        try {
            if (!connected) {
                throw new IllegalStateException("Connection not established");
            }
            if (socket.isClosed()) {
                throw new IllegalStateException("Socket is closed");
            }
            if (data == null) {
                throw new IllegalArgumentException("Data cannot be null");
            }
            System.out.println("Fragmenting data for " + remoteIP.getHostAddress() + ":" + remotePort + ", data length: " + data.length);
            List<byte[]> fragments = fragmentManager.fragment(data); // Use instance method
            for (byte[] fragment : fragments) {
                if (sentPackets.size() >= advertisedWindow) {
                    System.out.println("Window full, waiting...");
                    Thread.sleep(100); // Simplified flow control
                    continue;
                }
                int currentSeqNum = sequenceNumber++; // Increment sequence number
                int checksum = CRC.calculate(fragment); // Use CRC.java
                PacketHeader header = new PacketHeader(
                        socket.getLocalAddress(), socket.getLocalPort(),
                        remoteIP, remotePort, PacketHeader.PacketType.MESSAGE, fragment.length, checksum);
                sentPackets.put(currentSeqNum, fragment);
                sendPacket(PacketHeader.PacketType.MESSAGE, fragment);
                timeoutExecutor.schedule(() -> {
                    if (sentPackets.containsKey(currentSeqNum)) {
                        System.out.println("Timeout for sequence " + currentSeqNum + ", retransmitting...");
                        try {
                            sendPacket(PacketHeader.PacketType.MESSAGE, fragment);
                            advertisedWindow = Math.max(1, advertisedWindow / 2);
                        } catch (Exception e) {
                            System.err.println("Fehler bei der erneuten Übertragung: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                        }
                    }
                }, 1000, TimeUnit.MILLISECONDS);
            }
            System.out.println("All fragments sent to " + remoteIP.getHostAddress() + ":" + remotePort);
        } catch (Exception e) {
            System.err.println("Fehler beim Senden der Daten: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendPacket(PacketHeader.PacketType type, byte[] data) throws Exception {
        try {
            if (type == null) {
                throw new IllegalArgumentException("Packet type cannot be null");
            }
            if (data == null) {
                throw new IllegalArgumentException("Data cannot be null");
            }
            int checksum = CRC.calculate(data); // Use CRC.java
            PacketHeader header = new PacketHeader(
                    socket.getLocalAddress(), socket.getLocalPort(),
                    remoteIP, remotePort, type, data.length, checksum);
            byte[] headerBytes = header.toBytes();
            if (headerBytes.length != PacketHeader.HEADER_SIZE) {
                throw new IllegalStateException("Header size mismatch: expected " + PacketHeader.HEADER_SIZE + ", got " + headerBytes.length);
            }
            byte[] packetData = new byte[headerBytes.length + data.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(data, 0, packetData, headerBytes.length, data.length);
            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, remoteIP, remotePort);
            socket.send(packet);
            System.out.println("Sent packet type " + type + " to " + remoteIP.getHostAddress() + ":" + remotePort + ", header size: " + headerBytes.length);
        } catch (Exception e) {
            System.err.println("Fehler beim Senden des Pakets (type: " + type + "): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw e;
        }
    }

    public void processPacket(PacketHeader header, byte[] payload) {
        try {
            if (header == null || payload == null) {
                throw new IllegalArgumentException("Header or payload cannot be null");
            }
            if (header.type == PacketHeader.PacketType.SYN) {
                System.out.println("Received SYN, sending SYN-ACK");
                sendPacket(PacketHeader.PacketType.SYN_ACK, new byte[0]);
                connected = true;
            } else if (header.type == PacketHeader.PacketType.SYN_ACK) {
                System.out.println("Received SYN-ACK, sending ACK");
                sendPacket(PacketHeader.PacketType.ACK, new byte[0]);
                connected = true;
            } else if (header.type == PacketHeader.PacketType.ACK) {
                System.out.println("Received ACK for sequence number");
                // Assume sequence number is in payload as 4-byte integer
                if (payload.length >= 4) {
                    ByteBuffer buffer = ByteBuffer.wrap(payload);
                    int seqNum = buffer.getInt();
                    sentPackets.remove(seqNum);
                    advertisedWindow++;
                }
            } else if (header.type == PacketHeader.PacketType.FIN) {
                System.out.println("Received FIN, sending FIN-ACK");
                sendPacket(PacketHeader.PacketType.FIN_ACK, new byte[0]);
                connected = false;
            } else if (header.type == PacketHeader.PacketType.FIN_ACK) {
                System.out.println("Received FIN-ACK, sending ACK");
                sendPacket(PacketHeader.PacketType.ACK, new byte[0]);
                connected = false;
            } else if (header.type == PacketHeader.PacketType.MESSAGE) {
                // Forward to FragmentManager for reassembly
                byte[] assembled = fragmentManager.processChunk(payload, header.checksum);
                if (assembled != null) {
                    System.out.println("Assembled data received: " + new String(assembled, "UTF-8"));
                    // Send ACK with sequence number (using checksum as proxy)
                    ByteBuffer ackPayload = ByteBuffer.allocate(4);
                    ackPayload.putInt(header.checksum);
                    sendPacket(PacketHeader.PacketType.ACK, ackPayload.array());
                }
            }
        } catch (Exception e) {
            System.err.println("Fehler beim Verarbeiten des Pakets: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void disconnect() {
        try {
            if (connected) {
                System.out.println("Sending FIN to " + remoteIP.getHostAddress() + ":" + remotePort);
                sendPacket(PacketHeader.PacketType.FIN, new byte[0]);
                connected = false;
            }
        } catch (Exception e) {
            System.err.println("Fehler beim Trennen der Verbindung: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public void shutdown() {
        running = false;
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutExecutor.shutdownNow();
        }
    }
}