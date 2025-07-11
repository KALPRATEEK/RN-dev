import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class ChatNode {
    private final Map<String, RoutingEntry> routingTable = new ConcurrentHashMap<>();
    private final Set<InetSocketAddress> directNeighbors = ConcurrentHashMap.newKeySet();
    private final Map<InetSocketAddress, ConnectionManager> connections = new ConcurrentHashMap<>();

    private final DatagramSocket routingSocket;
    private final DatagramSocket dataSocket;
    private final InetAddress myIP;
    private final int routingPort;
    private final int dataPort;
    private volatile boolean running = true;
    private final Set<String> establishedConnections = ConcurrentHashMap.newKeySet();

    public ChatNode(InetAddress ip, int routingPort) throws Exception {
        this.routingPort = routingPort;
        this.dataPort = routingPort + 1;
        this.routingSocket = new DatagramSocket(routingPort);
        this.dataSocket = new DatagramSocket(dataPort);
        this.myIP = ip;

        String key = myIP.getHostAddress() + ":" + routingPort;
        routingTable.put(key, new RoutingEntry(myIP, routingPort, myIP, routingPort, 0));

        startRoutingReceiver();
        startDataReceiver();
        startPeriodicRoutingUpdates();
    }

    private void startPeriodicRoutingUpdates() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            sendRoutingTableToNeighbors();
        }, 0, 10, TimeUnit.SECONDS);
    }

    public void connectNeighbor(String ipStr, int port) {
        try {
            InetAddress ip = InetAddress.getByName(ipStr);
            InetSocketAddress neighbor = new InetSocketAddress(ip, port);

            if (port % 2 != 0) {
                System.out.println("Routing-Port muss eine gerade Zahl sein!");
                return;
            }

            if (directNeighbors.contains(neighbor)) {
                System.out.println("Nachbar ist bereits verbunden.");
                return;
            }

            directNeighbors.add(neighbor);
            String key = ip.getHostAddress() + ":" + port;
            routingTable.put(key, new RoutingEntry(ip, port, ip, port, 1));
            System.out.println("Nachbar hinzugefügt: " + key);

            sendRoutingEntryToSpecificNeighbor(neighbor);
            for (InetSocketAddress other : directNeighbors) {
                if (!other.equals(neighbor)) {
                    sendRoutingTableToSpecificNeighbor(other);
                }
            }
        } catch (Exception e) {
            System.out.println("Fehler beim Verbinden des Nachbarn: " + e.getMessage());
        }
    }

    private void sendRoutingEntryToSpecificNeighbor(InetSocketAddress neighbor) {
        try {
            byte[] tableBytes = encodeMyRoutingEntry();
            byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

            byte[] packetData = new byte[headerBytes.length + tableBytes.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
            routingSocket.send(packet);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Routing-Tabelle: " + e.getMessage());
        }
    }

    private void sendRoutingTableToSpecificNeighbor(InetSocketAddress neighbor) {
        try {
            byte[] tableBytes = encodeRoutingTable();
            byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

            byte[] packetData = new byte[headerBytes.length + tableBytes.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
            routingSocket.send(packet);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Routing-Tabelle: " + e.getMessage());
        }
    }

    private byte[] encodeMyRoutingEntry() throws Exception {
        Collection<RoutingEntry> entries = new ArrayList<>();
        String key = myIP.getHostAddress() + ":" + routingPort;
        entries.add(routingTable.get(key));
        ByteBuffer buffer = ByteBuffer.allocate(16);

        for (RoutingEntry entry : entries) {
            if (entry.destIP == null || entry.destPort == 0 || entry.nextHopIP == null || entry.nextHopPort == 0) {
                continue;
            }
            buffer.put(entry.destIP.getAddress());
            buffer.putShort((short) entry.destPort);
            buffer.put(entry.nextHopIP.getAddress());
            buffer.putShort((short) entry.nextHopPort);
            buffer.put((byte) entry.hopCount);
            buffer.put(new byte[3]);
        }
        return buffer.array();
    }

    public void disconnectNeighbor(InetSocketAddress neighbor) throws Exception {
        sendPoisonedUpdate(neighbor);
        if (directNeighbors.remove(neighbor)) {
            System.out.println("Nachbar entfernt: " + neighbor);
            Iterator<Map.Entry<String, RoutingEntry>> iter = routingTable.entrySet().iterator();
            boolean routingTableChanged = false;
            while (iter.hasNext()) {
                Map.Entry<String, RoutingEntry> entry = iter.next();
                RoutingEntry re = entry.getValue();
                if (re.nextHopIP.equals(neighbor.getAddress()) && re.nextHopPort == neighbor.getPort()) {
                    iter.remove();
                    routingTableChanged = true;
                }
            }
            String neighborKey = neighbor.getAddress().getHostAddress() + ":" + neighbor.getPort();
            if (routingTable.remove(neighborKey) != null) {
                routingTableChanged = true;
            }
            if (routingTableChanged) {
                sendRoutingTableToNeighbors();
            }
            // Disconnect any active connections
            ConnectionManager cm = connections.remove(neighbor);
            if (cm != null) {
                cm.disconnect();
                cm.shutdown();
            }
        }
    }

    private void sendPoisonedUpdate(InetSocketAddress neighbor) {
        try {
            RoutingEntry poisonedEntry = new RoutingEntry(myIP, routingPort, myIP, routingPort, 16);
            byte[] tableBytes = encodeSingleRoutingEntry(poisonedEntry);
            byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

            byte[] packetData = new byte[headerBytes.length + tableBytes.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
            routingSocket.send(packet);

            System.out.println("Poisoned Update gesendet an " + neighbor);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden des Poisoned Updates: " + e.getMessage());
        }
    }

    private byte[] encodeSingleRoutingEntry(RoutingEntry entry) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.put(entry.destIP.getAddress());
        buffer.putShort((short) entry.destPort);
        buffer.put(entry.nextHopIP.getAddress());
        buffer.putShort((short) entry.nextHopPort);
        buffer.put((byte) entry.hopCount);
        buffer.put(new byte[3]);
        return buffer.array();
    }

    public void printRoutingTable() {
        System.out.println("\nAktuelle Routing-Tabelle:");
        for (RoutingEntry entry : routingTable.values()) {
            System.out.printf("Ziel: %s:%d, NextHop: %s:%d, HopCount: %d\n",
                    entry.destIP.getHostAddress(),
                    entry.destPort,
                    entry.nextHopIP.getHostAddress(),
                    entry.nextHopPort,
                    entry.hopCount);
        }
    }

    public void shutdown() {
        running = false;
        routingSocket.close();
        dataSocket.close();
        connections.values().forEach(ConnectionManager::shutdown);
        System.out.println("Node wurde heruntergefahren.");
    }

    private void startRoutingReceiver() {
        new Thread(() -> {
            byte[] buf = new byte[1500];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    routingSocket.receive(packet);
                    processRoutingUpdate(packet.getData(), packet.getLength());
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void processRoutingUpdate(byte[] data, int length) throws Exception {
        if (length < 14) return;

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        byte[] srcIPBytes = new byte[4];
        buffer.get(srcIPBytes);
        InetAddress srcIP = InetAddress.getByAddress(srcIPBytes);
        int srcPort = Short.toUnsignedInt(buffer.getShort());
        byte[] destIPBytes = new byte[4];
        buffer.get(destIPBytes);
        InetAddress destIP = InetAddress.getByAddress(destIPBytes);
        int destPort = Short.toUnsignedInt(buffer.getShort());
        int tableLength = Short.toUnsignedInt(buffer.getShort());

        InetSocketAddress sender = new InetSocketAddress(srcIP, srcPort);

        // Check if sender is a direct neighbor or unknown
        String senderKey = srcIP.getHostAddress() + ":" + srcPort;
        if (routingTable.containsKey(senderKey) && !directNeighbors.contains(sender)) {
            System.out.println("Ignoring update from non-neighbor in routing table: " + senderKey);
            return;
        }

        if (length < 14 + tableLength) return;

        int entriesCount = tableLength / 16;
        Set<String> advertisedDestinations = new HashSet<>();
        boolean isPoisonedUpdate = false;
        boolean routingTableChanged = false;

        for (int i = 0; i < entriesCount; i++) {
            byte[] entryBytes = new byte[16];
            buffer.get(entryBytes);
            ByteBuffer entryBuffer = ByteBuffer.wrap(entryBytes);

            byte[] destEntryIPBytes = new byte[4];
            entryBuffer.get(destEntryIPBytes);
            InetAddress destEntryIP = InetAddress.getByAddress(destEntryIPBytes);
            int destEntryPort = Short.toUnsignedInt(entryBuffer.getShort());

            byte[] nextHopIPBytes = new byte[4];
            entryBuffer.get(nextHopIPBytes);
            InetAddress nextHopIP = InetAddress.getByAddress(nextHopIPBytes);
            int nextHopPort = Short.toUnsignedInt(entryBuffer.getShort());

            int hopCount = Byte.toUnsignedInt(entryBuffer.get());
            byte[] nullBytes = new byte[3];
            entryBuffer.get(nullBytes);
            if (!(nullBytes[0] == 0 && nullBytes[1] == 0 && nullBytes[2] == 0)) return;

            String key = destEntryIP.getHostAddress() + ":" + destEntryPort;
            advertisedDestinations.add(key);

            if (destEntryIP.equals(srcIP) && destEntryPort == srcPort && hopCount == 16) {
                isPoisonedUpdate = true;
            }

            RoutingEntry currentEntry = routingTable.get(key);

            if (hopCount == 16) {
                if (currentEntry != null && currentEntry.nextHopIP.equals(srcIP) && currentEntry.nextHopPort == srcPort) {
                    routingTable.remove(key);
                    routingTableChanged = true;
                }
            } else {
                int newHopCount = hopCount + 1;
                if (currentEntry == null && newHopCount < 16) {
                    routingTable.put(key, new RoutingEntry(destEntryIP, destEntryPort, srcIP, srcPort, newHopCount));
                    routingTableChanged = true;
                } else if (currentEntry != null) {
                    if (newHopCount < currentEntry.hopCount) {
                        currentEntry.hopCount = newHopCount;
                        currentEntry.nextHopIP = srcIP;
                        currentEntry.nextHopPort = srcPort;
                        routingTableChanged = true;
                    } else if (srcIP.equals(currentEntry.nextHopIP) && srcPort == currentEntry.nextHopPort) {
                        if (newHopCount != currentEntry.hopCount) {
                            currentEntry.hopCount = newHopCount;
                            routingTableChanged = true;
                        }
                    }
                }
            }
        }

        if (isPoisonedUpdate) {
            if (directNeighbors.remove(sender)) {
                System.out.println("Nachbar entfernt: " + sender);
                Iterator<Map.Entry<String, RoutingEntry>> iter = routingTable.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<String, RoutingEntry> entry = iter.next();
                    RoutingEntry re = entry.getValue();
                    if (re.nextHopIP.equals(srcIP) && re.nextHopPort == srcPort) {
                        iter.remove();
                        routingTableChanged = true;
                    }
                }
                connections.remove(sender);
            }
        } else if (!directNeighbors.contains(sender)) {
            directNeighbors.add(sender);
            System.out.println("Neuer Nachbar hinzugefügt: " + sender);
        }

        Iterator<Map.Entry<String, RoutingEntry>> iter = routingTable.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, RoutingEntry> entry = iter.next();
            String key = entry.getKey();
            RoutingEntry re = entry.getValue();
            if (re.nextHopIP.equals(srcIP) && re.nextHopPort == srcPort && !advertisedDestinations.contains(key)) {
                iter.remove();
                routingTableChanged = true;
            }
        }

        if (routingTableChanged) {
            sendRoutingTableToNeighbors();
        }
    }

    private void startDataReceiver() {
        FragmentManager fm = new FragmentManager();
        new Thread(() -> {
            byte[] buf = new byte[1500];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    dataSocket.receive(packet);
                    ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());

                    PacketHeader header = PacketHeader.fromBytes(Arrays.copyOfRange(packet.getData(), 0, PacketHeader.HEADER_SIZE));
                    byte[] payload = Arrays.copyOfRange(packet.getData(), PacketHeader.HEADER_SIZE, packet.getLength());

                    // Verify checksum
                    if (header.checksum != CRC.calculate(payload)) {
                        System.out.println("Checksum mismatch for packet from " + header.sourceIP + ":" + header.sourcePort);
                        continue;
                    }

                    InetSocketAddress sender = new InetSocketAddress(header.sourceIP, header.sourcePort);
                    ConnectionManager cm = connections.computeIfAbsent(sender,
                            k -> new ConnectionManager(dataSocket, header.sourceIP, header.sourcePort));

                    if (header.type == PacketHeader.PacketType.SYN) {
                        cm.processPacket(header, payload);
                    } else if (header.type == PacketHeader.PacketType.SYN_ACK) {
                        cm.processPacket(header, payload);
                        establishedConnections.add(header.sourceIP.getHostAddress() + ":" + header.sourcePort);
                        System.out.println("Verbindung hergestellt mit " + header.sourceIP.getHostAddress() + ":" + header.sourcePort);
                    } else if (header.type == PacketHeader.PacketType.ACK || header.type == PacketHeader.PacketType.FIN || header.type == PacketHeader.PacketType.FIN_ACK) {
                        cm.processPacket(header, payload);
                    } else if (header.type == PacketHeader.PacketType.MESSAGE || header.type == PacketHeader.PacketType.FILE) {
                        // Check if this node is the final destination
                        String destKey = myIP.getHostAddress() + ":" + dataPort;
                        if (header.destIP.equals(myIP) && header.destPort == dataPort) {
                            byte[] assembled = fm.processChunk(payload, header.checksum);
                            if (assembled != null) {
                                if (header.type == PacketHeader.PacketType.MESSAGE) {
                                    // Handle text message
                                    String msg = new String(assembled, "UTF-8");
                                    System.out.println("Nachricht empfangen von " + header.sourceIP.getHostAddress()
                                            + ":" + header.sourcePort + " -> " + msg);
                                } else {
                                    // Handle file: Save to disk
                                    String fileName = "received_file_" + System.currentTimeMillis();
                                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(fileName)) {
                                        fos.write(assembled);
                                        System.out.println("Datei empfangen von " + header.sourceIP.getHostAddress()
                                                + ":" + header.sourcePort + " -> Gespeichert als " + fileName);
                                    } catch (java.io.IOException e) {
                                        System.out.println("Fehler beim Speichern der Datei: " + e.getMessage());
                                    }
                                }
                                // Send ACK for received message/file
                                cm.processPacket(new PacketHeader(myIP, dataPort, header.sourceIP, header.sourcePort,
                                        PacketHeader.PacketType.ACK, header.length, 0), new byte[0]);
                            }
                        } else {
                            // Forward to next hop
                            String targetKey = header.destIP.getHostAddress() + ":" + (header.destPort - 1); // Use routing port
                            RoutingEntry nextHop = routingTable.get(targetKey);
                            if (nextHop != null) {
                                DatagramPacket forwardPacket = new DatagramPacket(
                                        packet.getData(), packet.getLength(),
                                        nextHop.nextHopIP, nextHop.nextHopPort + 1); // Forward to next hop's data port
                                dataSocket.send(forwardPacket);
                                System.out.println("Forwarded packet to " + nextHop.nextHopIP + ":" + (nextHop.nextHopPort + 1));
                            } else {
                                System.out.println("No route to " + targetKey);
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println("Error in data receiver: " + e.getMessage());
                }
            }
        }).start();
    }

    private void sendPacket(PacketHeader.PacketType type, byte[] data, InetAddress ip, int port) throws Exception {
        PacketHeader header = new PacketHeader(myIP, dataPort, ip, port, type, data.length, CRC.calculate(data));
        byte[] headerBytes = header.toBytes();
        byte[] packetData = new byte[headerBytes.length + data.length];
        System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
        System.arraycopy(data, 0, packetData, headerBytes.length, data.length);
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
        dataSocket.send(packet);
    }

    public void sendMessage(String ipStr, int port, byte[] data) {
        try {
            InetAddress destIP = InetAddress.getByName(ipStr);
            int destDataPort = port + 1; // Destination data port (n+1)
            String targetKey = destIP.getHostAddress() + ":" + port; // Use routing port (n) for routing table lookup

            // Check routing table for next hop
            RoutingEntry nextHop = routingTable.get(targetKey);
            if (nextHop == null) {
                System.out.println("No route to " + targetKey);
                return;
            }

            // Establish connection to next hop's data port
            InetSocketAddress nextHopAddr = new InetSocketAddress(nextHop.nextHopIP, nextHop.nextHopPort + 1);
            ConnectionManager cm = connections.computeIfAbsent(nextHopAddr,
                    k -> new ConnectionManager(dataSocket, nextHop.nextHopIP, nextHop.nextHopPort + 1));
            if (!establishedConnections.contains(nextHop.nextHopIP.getHostAddress() + ":" + (nextHop.nextHopPort + 1))) {
                cm.connect();
                establishedConnections.add(nextHop.nextHopIP.getHostAddress() + ":" + (nextHop.nextHopPort + 1));
            }

            // Determine packet type based on data content
            PacketHeader.PacketType packetType = (data.length > 0 && isValidUTF8(data)) ?
                    PacketHeader.PacketType.MESSAGE : PacketHeader.PacketType.FILE;

            // Send data
            cm.sendData(data);

            System.out.println((packetType == PacketHeader.PacketType.MESSAGE ? "Nachricht" : "Datei") +
                    " gesendet an " + ipStr + ":" + destDataPort + " via " + nextHop.nextHopIP + ":" + (nextHop.nextHopPort + 1));
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Daten: " + e.getMessage());
        }
    }

    // Helper method to check if data is valid UTF-8 (for message vs. file distinction)
    private boolean isValidUTF8(byte[] data) {
        try {
            new String(data, "UTF-8");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Overload for text messages
    public void sendMessage(String ipStr, int port, String message) {
        try {
            byte[] messageBytes = message.getBytes("UTF-8");
            sendMessage(ipStr, port, messageBytes);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Nachricht: " + e.getMessage());
        }
    }

    public void initiateConnection(String ipStr, int port) {
        try {
            InetAddress ip = InetAddress.getByName(ipStr);
            int destDataPort = port + 1;
            InetSocketAddress dest = new InetSocketAddress(ip, destDataPort);
            ConnectionManager cm = connections.computeIfAbsent(dest,
                    k -> new ConnectionManager(dataSocket, ip, destDataPort));
            cm.connect();
            establishedConnections.add(ip.getHostAddress() + ":" + destDataPort);
            System.out.println("Verbindungsaufbau initiiert mit " + ipStr + ":" + destDataPort);
        } catch (Exception e) {
            System.out.println("Fehler beim Verbindungsaufbau: " + e.getMessage());
        }
    }

    private void sendRoutingTableToNeighbors() {
        try {
            for (InetSocketAddress neighbor : directNeighbors) {
                byte[] tableBytes = encodeRoutingTable();
                byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

                byte[] packetData = new byte[headerBytes.length + tableBytes.length];
                System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
                System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
                routingSocket.send(packet);
            }
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Routing-Tabelle: " + e.getMessage());
        }
    }

    private byte[] encodeRoutingTable() throws Exception {
        Collection<RoutingEntry> entries = routingTable.values();
        int entrySize = 16;
        ByteBuffer buffer = ByteBuffer.allocate(entries.size() * entrySize);

        for (RoutingEntry entry : entries) {
            buffer.put(entry.destIP.getAddress());
            buffer.putShort((short) entry.destPort);
            buffer.put(entry.nextHopIP.getAddress());
            buffer.putShort((short) entry.nextHopPort);
            buffer.put((byte) entry.hopCount);
            buffer.put(new byte[3]);
        }
        return buffer.array();
    }

    private byte[] createRoutingHeader(InetAddress srcIP, int srcPort, InetAddress destIP, int destPort, int tableLength) {
        ByteBuffer buffer = ByteBuffer.allocate(14);
        buffer.put(srcIP.getAddress());
        buffer.putShort((short) srcPort);
        buffer.put(destIP.getAddress());
        buffer.putShort((short) destPort);
        buffer.putShort((short) tableLength);
        return buffer.array();
    }
}