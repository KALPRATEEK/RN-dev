import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.CRC32;

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
        if (ip == null) {
            throw new IllegalArgumentException("IP cannot be null");
        }
        if (!(ip instanceof Inet4Address)) {
            throw new IllegalArgumentException("IP must be IPv4");
        }
        if (routingPort <= 0 || routingPort > 65535) {
            throw new IllegalArgumentException("Invalid routing port: " + routingPort);
        }
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
            if (ipStr == null || ipStr.isEmpty()) {
                throw new IllegalArgumentException("IP string cannot be null or empty");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
            InetAddress ip = InetAddress.getByName(ipStr);
            if (!(ip instanceof Inet4Address)) {
                throw new IllegalArgumentException("Neighbor IP must be IPv4");
            }
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
            System.out.println("Fehler beim Verbinden des Nachbarn: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendRoutingEntryToSpecificNeighbor(InetSocketAddress neighbor) {
        try {
            if (neighbor == null) {
                throw new IllegalArgumentException("Neighbor cannot be null");
            }
            byte[] tableBytes = encodeMyRoutingEntry();
            byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

            byte[] packetData = new byte[headerBytes.length + tableBytes.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
            routingSocket.send(packet);
            System.out.println("Routing entry sent to " + neighbor);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Routing-Tabelle: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sendRoutingTableToSpecificNeighbor(InetSocketAddress neighbor) {
        try {
            if (neighbor == null) {
                throw new IllegalArgumentException("Neighbor cannot be null");
            }
            byte[] tableBytes = encodeRoutingTable();
            byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

            byte[] packetData = new byte[headerBytes.length + tableBytes.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
            routingSocket.send(packet);
            System.out.println("Routing table sent to " + neighbor);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Routing-Tabelle: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
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
        if (neighbor == null) {
            throw new IllegalArgumentException("Neighbor cannot be null");
        }
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
            if (neighbor == null) {
                throw new IllegalArgumentException("Neighbor cannot be null");
            }
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
            System.out.println("Fehler beim Senden des Poisoned Updates: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private byte[] encodeSingleRoutingEntry(RoutingEntry entry) throws Exception {
        if (entry == null) {
            throw new IllegalArgumentException("Routing entry cannot be null");
        }
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
                    entry.destIP != null ? entry.destIP.getHostAddress() : "null",
                    entry.destPort,
                    entry.nextHopIP != null ? entry.nextHopIP.getHostAddress() : "null",
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
                } catch (Exception e) {
                    System.out.println("Error in routing receiver: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        }).start();
    }

    private void processRoutingUpdate(byte[] data, int length) throws Exception {
        if (length < 14) {
            System.out.println("Routing update too short: " + length + " bytes");
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        byte[] srcIPBytes = new byte[4];
        buffer.get(srcIPBytes);
        InetAddress srcIP;
        try {
            srcIP = InetAddress.getByAddress(srcIPBytes);
            if (!(srcIP instanceof Inet4Address)) {
                System.out.println("Invalid source IP in routing update: not IPv4");
                return;
            }
        } catch (UnknownHostException e) {
            System.out.println("Invalid source IP in routing update: " + e.getMessage());
            return;
        }
        int srcPort = Short.toUnsignedInt(buffer.getShort());
        byte[] destIPBytes = new byte[4];
        buffer.get(destIPBytes);
        InetAddress destIP;
        try {
            destIP = InetAddress.getByAddress(destIPBytes);
            if (!(destIP instanceof Inet4Address)) {
                System.out.println("Invalid destination IP in routing update: not IPv4");
                return;
            }
        } catch (UnknownHostException e) {
            System.out.println("Invalid destination IP in routing update: " + e.getMessage());
            return;
        }
        int destPort = Short.toUnsignedInt(buffer.getShort());
        int tableLength = Short.toUnsignedInt(buffer.getShort());

        InetSocketAddress sender = new InetSocketAddress(srcIP, srcPort);

        // Check if sender is a direct neighbor or unknown
        String senderKey = srcIP.getHostAddress() + ":" + srcPort;
        if (routingTable.containsKey(senderKey) && !directNeighbors.contains(sender)) {
            System.out.println("Ignoring update from non-neighbor in routing table: " + senderKey);
            return;
        }

        if (length < 14 + tableLength) {
            System.out.println("Invalid routing update length: " + length + ", expected at least " + (14 + tableLength));
            return;
        }

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
            InetAddress destEntryIP;
            try {
                destEntryIP = InetAddress.getByAddress(destEntryIPBytes);
                if (!(destEntryIP instanceof Inet4Address)) {
                    System.out.println("Invalid destination IP in routing entry: not IPv4");
                    continue;
                }
            } catch (UnknownHostException e) {
                System.out.println("Invalid destination IP in routing entry: " + e.getMessage());
                continue;
            }
            int destEntryPort = Short.toUnsignedInt(entryBuffer.getShort());

            byte[] nextHopIPBytes = new byte[4];
            entryBuffer.get(nextHopIPBytes);
            InetAddress nextHopIP;
            try {
                nextHopIP = InetAddress.getByAddress(nextHopIPBytes);
                if (!(nextHopIP instanceof Inet4Address)) {
                    System.out.println("Invalid next hop IP in routing entry: not IPv4");
                    continue;
                }
            } catch (UnknownHostException e) {
                System.out.println("Invalid next hop IP in routing entry: " + e.getMessage());
                continue;
            }
            int nextHopPort = Short.toUnsignedInt(entryBuffer.getShort());

            int hopCount = Byte.toUnsignedInt(entryBuffer.get());
            byte[] nullBytes = new byte[3];
            entryBuffer.get(nullBytes);
            if (!(nullBytes[0] == 0 && nullBytes[1] == 0 && nullBytes[2] == 0)) {
                System.out.println("Invalid padding in routing entry");
                continue;
            }

            // Validate port numbers
            if (destEntryPort == 0 || nextHopPort == 0) {
                System.out.println("Invalid port in routing entry: destPort=" + destEntryPort + ", nextHopPort=" + nextHopPort);
                continue;
            }

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

                    if (packet.getLength() < PacketHeader.HEADER_SIZE) {
                        System.out.println("Received packet too short: " + packet.getLength() + " bytes");
                        continue;
                    }

                    PacketHeader header = PacketHeader.fromBytes(Arrays.copyOfRange(packet.getData(), 0, PacketHeader.HEADER_SIZE));
                    byte[] payload = Arrays.copyOfRange(packet.getData(), PacketHeader.HEADER_SIZE, packet.getLength());

                    // Verify checksum
                    if (header.checksum != CRC.calculate(payload)) {
                        System.out.println("Checksum mismatch for packet from " + header.sourceIP.getHostAddress() + ":" + header.sourcePort);
                        continue;
                    }

                    InetSocketAddress sender = new InetSocketAddress(header.sourceIP, header.sourcePort);
                    ConnectionManager cm = connections.computeIfAbsent(sender,
                            k -> {
                                System.out.println("Creating new ConnectionManager for " + sender);
                                return new ConnectionManager(dataSocket, header.sourceIP, header.sourcePort);
                            });

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
                                        System.out.println("Fehler beim Speichern der Datei: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
                    System.out.println("Error in data receiver: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void sendPacket(PacketHeader.PacketType type, byte[] data, InetAddress ip, int port) throws Exception {
        if (type == null) {
            throw new IllegalArgumentException("Packet type cannot be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("Data cannot be null");
        }
        if (ip == null) {
            throw new IllegalArgumentException("Destination IP cannot be null");
        }
        if (!(ip instanceof Inet4Address)) {
            throw new IllegalArgumentException("Destination IP must be IPv4");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid destination port: " + port);
        }
        int checksum = CRC.calculate(data);
        PacketHeader header = new PacketHeader(myIP, dataPort, ip, port, type, data.length, checksum);
        byte[] headerBytes = header.toBytes();
        if (headerBytes.length != PacketHeader.HEADER_SIZE) {
            throw new IllegalStateException("Header size mismatch: expected " + PacketHeader.HEADER_SIZE + ", got " + headerBytes.length);
        }
        byte[] packetData = new byte[headerBytes.length + data.length];
        System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
        System.arraycopy(data, 0, packetData, headerBytes.length, data.length);
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
        dataSocket.send(packet);
        System.out.println("Sent packet type " + type + " to " + ip.getHostAddress() + ":" + port);
    }

    public void sendMessage(String ipStr, int port, byte[] data) {
        try {
            if (ipStr == null || ipStr.isEmpty()) {
                throw new IllegalArgumentException("IP string cannot be null or empty");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
            if (data == null) {
                throw new IllegalArgumentException("Data cannot be null");
            }
            System.out.println("Attempting to send data to " + ipStr + ":" + (port + 1));
            if (dataSocket == null || dataSocket.isClosed()) {
                throw new IllegalStateException("Data socket is null or closed");
            }

            InetAddress destIP = InetAddress.getByName(ipStr);
            if (!(destIP instanceof Inet4Address)) {
                throw new IllegalArgumentException("Destination IP must be IPv4");
            }
            int destDataPort = port + 1; // Destination data port (n+1)
            String targetKey = destIP.getHostAddress() + ":" + port; // Use routing port (n) for routing table lookup

            // Debug: Print routing table
            System.out.println("Routing table before sending to " + targetKey + ":");
            printRoutingTable();

            // Check routing table for next hop
            RoutingEntry nextHop = routingTable.get(targetKey);
            if (nextHop == null) {
                System.out.println("No route to " + targetKey);
                return;
            }

            // Validate nextHop fields
            if (nextHop.nextHopIP == null || nextHop.nextHopPort == 0) {
                System.out.println("Invalid routing entry for " + targetKey + ": nextHopIP=" + nextHop.nextHopIP + ", nextHopPort=" + nextHop.nextHopPort);
                return;
            }

            System.out.println("Next hop found: " + nextHop.nextHopIP.getHostAddress() + ":" + (nextHop.nextHopPort + 1));

            // Establish connection to next hop's data port
            InetSocketAddress nextHopAddr = new InetSocketAddress(nextHop.nextHopIP, nextHop.nextHopPort + 1);
            ConnectionManager cm = connections.computeIfAbsent(nextHopAddr,
                    k -> {
                        System.out.println("Creating new ConnectionManager for " + nextHopAddr);
                        return new ConnectionManager(dataSocket, nextHop.nextHopIP, nextHop.nextHopPort + 1);
                    });
            String connectionKey = nextHop.nextHopIP.getHostAddress() + ":" + (nextHop.nextHopPort + 1);
            if (!establishedConnections.contains(connectionKey)) {
                System.out.println("Initiating connection to " + connectionKey);
                cm.connect();
                establishedConnections.add(connectionKey);
            }

            // Determine packet type based on data content
            PacketHeader.PacketType packetType = (data.length > 0 && isValidUTF8(data)) ?
                    PacketHeader.PacketType.MESSAGE : PacketHeader.PacketType.FILE;

            // Send data
            System.out.println("Sending data to ConnectionManager...");
            cm.sendData(data);

            System.out.println((packetType == PacketHeader.PacketType.MESSAGE ? "Nachricht" : "Datei") +
                    " gesendet an " + ipStr + ":" + destDataPort + " via " + nextHop.nextHopIP.getHostAddress() + ":" + (nextHop.nextHopPort + 1));
        } catch (Exception e) {
            System.err.println("Fehler beim Senden der Daten an " + ipStr + ":" + (port + 1) + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
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
            if (message == null) {
                throw new IllegalArgumentException("Message cannot be null");
            }
            byte[] messageBytes = message.getBytes("UTF-8");
            sendMessage(ipStr, port, messageBytes);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Nachricht: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void initiateConnection(String ipStr, int port) {
        try {
            if (ipStr == null || ipStr.isEmpty()) {
                throw new IllegalArgumentException("IP string cannot be null or empty");
            }
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid port: " + port);
            }
            InetAddress ip = InetAddress.getByName(ipStr);
            if (!(ip instanceof Inet4Address)) {
                throw new IllegalArgumentException("Destination IP must be IPv4");
            }
            int destDataPort = port + 1;
            InetSocketAddress dest = new InetSocketAddress(ip, destDataPort);
            ConnectionManager cm = connections.computeIfAbsent(dest,
                    k -> {
                        System.out.println("Creating new ConnectionManager for " + dest);
                        return new ConnectionManager(dataSocket, ip, destDataPort);
                    });
            cm.connect();
            establishedConnections.add(ip.getHostAddress() + ":" + destDataPort);
            System.out.println("Verbindungsaufbau initiiert mit " + ipStr + ":" + destDataPort);
        } catch (Exception e) {
            System.out.println("Fehler beim Verbindungsaufbau: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
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
                System.out.println("Routing table sent to " + neighbor);
            }
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Routing-Tabelle: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private byte[] encodeRoutingTable() throws Exception {
        Collection<RoutingEntry> entries = routingTable.values();
        int entrySize = 16;
        ByteBuffer buffer = ByteBuffer.allocate(entries.size() * entrySize);

        for (RoutingEntry entry : entries) {
            if (entry.destIP == null || entry.nextHopIP == null || entry.destPort == 0 || entry.nextHopPort == 0) {
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

    private byte[] createRoutingHeader(InetAddress srcIP, int srcPort, InetAddress destIP, int destPort, int tableLength) {
        if (srcIP == null || destIP == null) {
            throw new IllegalArgumentException("Source or destination IP cannot be null");
        }
        if (!(srcIP instanceof Inet4Address) || !(destIP instanceof Inet4Address)) {
            throw new IllegalArgumentException("Source and destination IPs must be IPv4");
        }
        if (srcPort <= 0 || srcPort > 65535 || destPort <= 0 || destPort > 65535) {
            throw new IllegalArgumentException("Invalid ports: srcPort=" + srcPort + ", destPort=" + destPort);
        }
        ByteBuffer buffer = ByteBuffer.allocate(14);
        buffer.put(srcIP.getAddress());
        buffer.putShort((short) srcPort);
        buffer.put(destIP.getAddress());
        buffer.putShort((short) destPort);
        buffer.putShort((short) tableLength);
        return buffer.array();
    }
}