import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

public class ChatNode {
    private final Map<String, RoutingEntry> routingTable = new ConcurrentHashMap<>();
    private final Set<InetSocketAddress> directNeighbors = ConcurrentHashMap.newKeySet();
    private final Map<InetSocketAddress, ScheduledFuture<?>> neighborTimers = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> infinityTimers = new ConcurrentHashMap<>(); // New: For 90-second infinity timer
    private final ScheduledExecutorService timerExecutor = Executors.newSingleThreadScheduledExecutor();

    private final DatagramSocket routingSocket;
    private final DatagramSocket dataSocket;
    private final InetAddress myIP;
    private final int routingPort;
    private final int dataPort;
    private volatile boolean running = true;
    private final Set<String> establishedConnections = ConcurrentHashMap.newKeySet();
    FragmentManager fragmentManager;
    private final java.util.Map<Integer, Integer> dataAck = new java.util.concurrent.ConcurrentHashMap<>();

    public ChatNode(InetAddress ip, int routingPort) throws Exception {
        this.routingPort = routingPort;
        this.dataPort = routingPort + 1;
        this.routingSocket = new DatagramSocket(routingPort);
        this.dataSocket = new DatagramSocket(dataPort);
        this.myIP = ip;
        this.fragmentManager  = new FragmentManager(myIP, routingPort, dataAck);

        String key = myIP.getHostAddress() + ":" + routingPort;
        routingTable.put(key, new RoutingEntry(myIP, routingPort, myIP, routingPort, 0));

        startRoutingReceiver();
        startDataReceiver();
        startPeriodicRoutingUpdates();
    }

    private void startPeriodicRoutingUpdates() {
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            sendRoutingTableToNeighbors(null); // No excluded neighbor for periodic updates
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

            startNeighborTimer(neighbor);
            sendRoutingEntryToSpecificNeighbor(neighbor);
        } catch (Exception e) {
            System.out.println("Fehler beim Verbinden des Nachbarn: " + e.getMessage());
        }
    }

    private void startNeighborTimer(InetSocketAddress neighbor) {
        ScheduledFuture<?> existingTimer = neighborTimers.remove(neighbor);
        if (existingTimer != null) {
            existingTimer.cancel(false);
        }

        ScheduledFuture<?> timer = timerExecutor.schedule(() -> {
            String key = neighbor.getAddress().getHostAddress() + ":" + neighbor.getPort();
            RoutingEntry entry = routingTable.get(key);
            if (entry != null) {
                entry.hopCount = 16;
                System.out.println("Timer abgelaufen für Nachbar: " + neighbor + ", HopCount auf 16 gesetzt");
                directNeighbors.remove(neighbor);
                neighborTimers.remove(neighbor);
                startInfinityTimer(key); // Start 90-second timer for infinity entry
                sendRoutingTableToNeighbors(null);
            }
        }, 30, TimeUnit.SECONDS);
        neighborTimers.put(neighbor, timer);
    }

    private void startInfinityTimer(String key) {
        ScheduledFuture<?> existingTimer = infinityTimers.remove(key);
        if (existingTimer != null) {
            existingTimer.cancel(false);
        }

        ScheduledFuture<?> timer = timerExecutor.schedule(() -> {
            routingTable.remove(key);
            infinityTimers.remove(key);
            System.out.println("Infinity-Eintrag entfernt: " + key);
            sendRoutingTableToNeighbors(null);
        }, 90, TimeUnit.SECONDS);
        infinityTimers.put(key, timer);
    }

    private void sendRoutingEntryToSpecificNeighbor(InetSocketAddress neighbor) {
        try {
            byte[] tableBytes = encodeMyRoutingEntry();
            byte[] headerBytes = createRoutingHeader(myIP, routingPort, neighbor.getAddress(), neighbor.getPort(), tableBytes.length);

            byte[] packetData = new byte[headerBytes.length + tableBytes.length];
            System.arraycopy(headerBytes, 0, packetData, 0, headerBytes.length);
            System.arraycopy(tableBytes, 0, packetData, headerBytes.length, tableBytes.length);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, neighbor.getAddress(), neighbor.getPort());
            LoggerUtil.sendPacket(String.valueOf(neighbor));
            LoggerUtil.sendLength(packetData.length);
            LoggerUtil.packetData(packetData);

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
            } else {
                buffer.put(entry.destIP.getAddress());
                buffer.putShort((short) entry.destPort);
                buffer.put(entry.nextHopIP.getAddress());
                buffer.putShort((short) entry.nextHopPort);
                buffer.put((byte) entry.hopCount);
                buffer.put(new byte[3]);
            }
        }

        return buffer.array();
    }

    public void disconnectNeighbor(InetSocketAddress neighbor) throws Exception {
        ScheduledFuture<?> timer = neighborTimers.remove(neighbor);
        if (timer != null) {
            timer.cancel(false);
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
                sendRoutingTableToNeighbors(null);
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

            LoggerUtil.poisonedUpdate(neighbor);

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
            if (entry.hopCount < 16) {
                System.out.printf("Ziel: %s:%d, NextHop: %s:%d, HopCount: %d\n",
                        entry.destIP.getHostAddress(),
                        entry.destPort,
                        entry.nextHopIP.getHostAddress(),
                        entry.nextHopPort,
                        entry.hopCount);
            }
        }
    }

    public void shutdown() {
        running = false;
        neighborTimers.values().forEach(timer -> timer.cancel(false));
        infinityTimers.values().forEach(timer -> timer.cancel(false));
        timerExecutor.shutdown();
        routingSocket.close();
        dataSocket.close();
        System.out.println("Node wurde heruntergefahren.");
    }

    private void startRoutingReceiver() {
        new Thread(() -> {
            byte[] buf = new byte[1500];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    routingSocket.receive(packet);
                    processRoutingUpdate(packet.getData(), packet.getLength(), new InetSocketAddress(packet.getAddress(), packet.getPort()));
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void processRoutingUpdate(byte[] data, int length, InetSocketAddress sender) throws Exception {
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

        if (length < 14 + tableLength) return;

        String senderKey = srcIP.getHostAddress() + ":" + srcPort;
        if (routingTable.containsKey(senderKey) && !directNeighbors.contains(sender)) {
            System.out.println("Update von nicht-direktem Nachbarn " + sender + " verworfen");
            return;
        }

        if (directNeighbors.contains(sender)) {
            startNeighborTimer(sender);
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

            // Split Horizon: Ignore entry if next hop is this node
            if (nextHopIP.equals(myIP) && nextHopPort == routingPort) {
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
                    startInfinityTimer(key); // Start 90-second timer for infinity entry
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

        if (isPoisonedUpdate && entriesCount == 1) {
            if (directNeighbors.remove(sender)) {
                System.out.println("Nachbar entfernt: " + sender);
                ScheduledFuture<?> timer = neighborTimers.remove(sender);
                if (timer != null) {
                    timer.cancel(false);
                }
                Iterator<Map.Entry<String, RoutingEntry>> iter = routingTable.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<String, RoutingEntry> entry = iter.next();
                    RoutingEntry re = entry.getValue();
                    if (re.nextHopIP.equals(srcIP) && re.nextHopPort == srcPort) {
                        iter.remove();
                        startInfinityTimer(entry.getKey());
                        routingTableChanged = true;
                    }
                }
            }
        } else if (!directNeighbors.contains(sender) && entriesCount == 1) {
            directNeighbors.add(sender);
            System.out.println("Neuer Nachbar hinzugefügt: " + sender);
            startNeighborTimer(sender);
            routingTableChanged = true;
        }

        Iterator<Map.Entry<String, RoutingEntry>> iter = routingTable.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<String, RoutingEntry> entry = iter.next();
            String key = entry.getKey();
            RoutingEntry re = entry.getValue();
            if (re.nextHopIP.equals(srcIP) && re.nextHopPort == srcPort && !advertisedDestinations.contains(key)) {
                iter.remove();
                startInfinityTimer(key);
                routingTableChanged = true;
            }
        }

        if (routingTableChanged) {
            sendRoutingTableToNeighbors(sender); // Exclude sender from triggered update
        }
    }

private void startDataReceiver() {
    new Thread(() -> {
        byte[] buf = new byte[2048];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                dataSocket.receive(packet);

                byte[] data = Arrays.copyOf(packet.getData(), packet.getLength());
                PacketHeader header = PacketHeader.fromBytes(data);
                ByteBuffer buffer = ByteBuffer.wrap(data, PacketHeader.HEADER_SIZE, header.length);

                String key = header.sourceIP.getHostAddress() + ":" + header.sourcePort;
                PacketHeader.PacketType type = header.type;
                if (header.destIP.equals(myIP))
                {
                    if (type == PacketHeader.PacketType.SYN) {
                        sendPacket(PacketHeader.PacketType.SYN_ACK, new byte[0], packet.getAddress(), packet.getPort());
                        LoggerUtil.syn(key);

                    } else if (type == PacketHeader.PacketType.SYN_ACK) {
                        establishedConnections.add(key);
                        System.out.println("Verbindung hergestellt mit " + key);

                    }else if (type == PacketHeader.PacketType.DATA_ACK) {
                            synchronized (dataAck){
                                ByteBuffer ackPayload = ByteBuffer.wrap(data, PacketHeader.HEADER_SIZE, header.length);
                                int ackMsgId = Short.toUnsignedInt(ackPayload.getShort());
                                int ackFrag = ackPayload.getInt();
                                Integer current = dataAck.get(ackMsgId);
                                if (current == null || ackFrag > current) {
                                    dataAck.put(ackMsgId, ackFrag);
                                }

                            }
                    } else if (type == PacketHeader.PacketType.FIN) {
                        sendPacket(PacketHeader.PacketType.FIN_ACK, new byte[0], packet.getAddress(), packet.getPort());
                        LoggerUtil.fin(key);
                        establishedConnections.remove(key);

                    } else if (type == PacketHeader.PacketType.FIN_ACK) {
                        LoggerUtil.finAck(key);
                        establishedConnections.remove(key);

                    } else if (type == PacketHeader.PacketType.MESSAGE) {
                        // Payload ab offset 19 (Header) extrahieren
                        byte[] fragment = new byte[header.length];
                        System.arraycopy(data, PacketHeader.HEADER_SIZE, fragment, 0, header.length);

                        byte[] fullPayload = fragmentManager.processChunk(
                                fragment,
                                dataSocket,
                                packet.getAddress(),
                                packet.getPort(),
                                header.checksum
                        );

                        if (fullPayload != null) {
                            String msg = new String(fullPayload, StandardCharsets.UTF_8);
                            System.out.println("Nachricht empfangen von " + key + " -> " + msg);
                        }

                    } else if (type == PacketHeader.PacketType.FILE) {
                        // Payload ab offset 19 (Header) übergeben
                        byte[] fragment = new byte[header.length];
                        System.arraycopy(data, PacketHeader.HEADER_SIZE, fragment, 0, header.length);

                        byte[] fullPayload = fragmentManager.processChunk(
                                fragment,
                                dataSocket,
                                packet.getAddress(),
                                packet.getPort(),
                                header.checksum
                        );

                        if (fullPayload != null) {
                            ByteBuffer payloadBuffer = ByteBuffer.wrap(fullPayload);
                      //      int fileNameLen = Short.toUnsignedInt(payloadBuffer.getShort());
                       //     byte[] mesheader = new byte[10];
                         //   payloadBuffer.get(mesheader);
                            byte[] nameBytes = new byte[30];
                            payloadBuffer.get(nameBytes);
                            String fileName = new String(nameBytes, StandardCharsets.UTF_8);
                            String fileTrim = fileName.trim();
                            LoggerUtil.info("fileContent",payloadBuffer.toString());
                            byte[] fileContent = new byte[payloadBuffer.remaining()];

                            Path outputPath = Paths.get("received_" + fileTrim);
                            payloadBuffer.get(fileContent);
                            Files.write(outputPath, fileContent);
                            System.out.println("Datei empfangen und gespeichert: " + outputPath);
                        }
                    }

                }
            else {
                forwardPacket(header, data, header.destIP, header.destPort);

            }}

            catch (Exception e) {
                    System.out.println("Fehler im DataReceiver: " + e.getMessage());
                    e.printStackTrace();
                }
            }
    }).start();
}

    private void forwardPacket(PacketHeader header, byte[] data, InetAddress ip, int port) throws Exception {
        LoggerUtil.header(header.toString());
        ByteBuffer buffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE + data.length);
        buffer.put(header.toBytes());                  // 19 bytes header
        buffer.put(data);                              // payload

        byte[] packetData = buffer.array();
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
        dataSocket.send(packet);
    }
    private void sendPacket(PacketHeader.PacketType type, byte[] data, InetAddress ip, int port) throws Exception {
        PacketHeader header = new PacketHeader(
                myIP,                                   // source IP
                routingPort,                            // source Port
                ip,                                     // destination IP
                port,                                   // destination Port
                type,                                   // packet type (e.g. SYN, FIN, MESSAGE)
                data.length,                            // payload length
                CRC.calculate(data)                     // CRC über payload
        );
        LoggerUtil.header(header.toString());
        ByteBuffer buffer = ByteBuffer.allocate(PacketHeader.HEADER_SIZE + data.length);
        buffer.put(header.toBytes());                  // 19 bytes header
        buffer.put(data);                              // payload

        byte[] packetData = buffer.array();
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
        dataSocket.send(packet);
    }


/*public void sendMessage(String ipStr, int port, String message){
    try {
        InetAddress destIP = InetAddress.getByName(ipStr);
        int destDataPort = port + 1;

        // Read message content
        byte[] messageBytes = message.getBytes("UTF-8");

        // Prepend filename length and filename before content
        ByteBuffer payload = ByteBuffer.allocate(messageBytes.length);
        payload.put(messageBytes);

        // Fragment and send with Go-Back-N
        FragmentManager.FragmentedMessage fragmented = fragmentManager.fragment(payload.array());
        DatagramSocket ackSocket = new DatagramSocket();  // ACKs separat behandeln
        fragmentManager.sendWithGoBackN(fragmented.messageId(), fragmented.fragments(), ackSocket, PacketHeader.PacketType.MESSAGE, destIP, destDataPort);
        ackSocket.close();  // Nach Abschluss

        System.out.println("Nachricht gesendet an " + ipStr + ":" + destDataPort);
    } catch (Exception e) {
        System.out.println("Fehler beim Senden der Nachricht: " + e.getMessage());
    }
}*/

    public void sendMessage(String ipStr, int port, String message){
        try {
            InetAddress destIP = InetAddress.getByName(ipStr);
            int destDataPort = port + 1;

            byte[] messageBytes = message.getBytes("UTF-8");
            ByteBuffer payload = ByteBuffer.allocate(messageBytes.length);
            payload.put(messageBytes);

            FragmentManager.FragmentedMessage fragmented = fragmentManager.fragment(payload.array());

            fragmentManager.sendWithGoBackN(
                    fragmented.messageId(),
                    fragmented.fragments(),
                    dataSocket,
                    PacketHeader.PacketType.MESSAGE,
                    destIP,
                    destDataPort,
                    dataPort
            );

            System.out.println("Nachricht gesendet an " + ipStr + ":" + destDataPort);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Nachricht: " + e.getMessage());
        }
    }

    public void sendFile(String filePath, String destIpStr, int destPort) {
        try {
            InetAddress destIP = InetAddress.getByName(destIpStr);
            int destDataPort = destPort + 1;

            // Read file content
            byte[] fileData = Files.readAllBytes(Paths.get(filePath));
            String fileName = Paths.get(filePath).getFileName().toString();
            byte[] fileNameBytes = new byte[30];
            byte[] name = fileName.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(name, 0,fileNameBytes,0, name.length);

            ByteBuffer filePayload = ByteBuffer.allocate(fileNameBytes.length + fileData.length);
            filePayload.put(fileNameBytes);
            filePayload.put(fileData);

            // Fragment and send with Go-Back-N
            FragmentManager.FragmentedMessage fragmented = fragmentManager.fragment(filePayload.array());
            fragmentManager.sendWithGoBackN(fragmented.messageId(), fragmented.fragments(), dataSocket,  PacketHeader.PacketType.FILE, destIP, destDataPort,dataPort);



            System.out.println("Datei gesendet: " + fileName);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Datei: " + e.getMessage());
        }
    }

    private void sendRoutingTableToNeighbors(InetSocketAddress excludeNeighbor) {
        try {
            for (InetSocketAddress neighbor : directNeighbors) {
                if (excludeNeighbor != null && neighbor.equals(excludeNeighbor)) {
                    continue; // Skip the neighbor that triggered the update
                }
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