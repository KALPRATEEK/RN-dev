import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

public class ChatNode {
    private final Map<String, RoutingEntry> routingTable = new ConcurrentHashMap<>();
    private final Set<InetSocketAddress> directNeighbors = ConcurrentHashMap.newKeySet();

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

            sendRoutingTableToSpecificNeighbor(neighbor);
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
            byte[] tableBytes = encodeRoutingEntry();
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

    private byte[] encodeRoutingEntry() throws Exception {
        int entrySize = 16;
        ByteBuffer buffer = ByteBuffer.allocate(entrySize);
        String key = myIP.getHostAddress() + ":" + routingPort;
        RoutingEntry entry = routingTable.get(key);
        buffer.put(entry.destIP.getAddress());
        buffer.putShort((short) entry.destPort);
        buffer.put(entry.nextHopIP.getAddress());
        buffer.putShort((short) entry.nextHopPort);
        buffer.put((byte) entry.hopCount);
        buffer.put(new byte[3]);
        return buffer.array();
    }


    public void disconnectNeighbor(InetSocketAddress neighbor) throws Exception {
        // Sende ein Poisoned Update an den Nachbarn
        sendPoisonedUpdate(neighbor);

        // Entferne den Nachbarn aus der Liste der direkten Nachbarn
        if (directNeighbors.remove(neighbor)) {
            System.out.println("Nachbar entfernt: " + neighbor);

            // Entferne alle Routen aus der Routing-Tabelle, die über diesen Nachbarn gingen
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

            // Entferne den direkten Eintrag für den Nachbarn selbst, falls vorhanden
            String neighborKey = neighbor.getAddress().getHostAddress() + ":" + neighbor.getPort();
            if (routingTable.remove(neighborKey) != null) {
                routingTableChanged = true;
            }

            // Sende aktualisierte Routing-Tabelle an verbleibende Nachbarn
            if (routingTableChanged) {
                sendRoutingTableToNeighbors();
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

        if (length < 14 + tableLength) return;

        int entriesCount = tableLength / 16;
        Set<String> advertisedDestinations = new HashSet<>();
        boolean isPoisonedUpdate = false;
        boolean routingTableChanged = false;

        // Verarbeite alle Einträge im Update
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

            // Prüfe, ob dies ein Poisoned Update für den Sender selbst ist
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

        // Behandle Poisoned Update
        if (isPoisonedUpdate) {
            if (directNeighbors.remove(sender)) {
                System.out.println("Nachbar entfernt: " + sender);
                // Entferne alle Routen, die über diesen Nachbarn gingen
                Iterator<Map.Entry<String, RoutingEntry>> iter = routingTable.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<String, RoutingEntry> entry = iter.next();
                    RoutingEntry re = entry.getValue();
                    if (re.nextHopIP.equals(srcIP) && re.nextHopPort == srcPort) {
                        iter.remove();
                        routingTableChanged = true;
                    }
                }
            }
        } else if (!directNeighbors.contains(sender)) {
            directNeighbors.add(sender);
            System.out.println("Neuer Nachbar hinzugefügt: " + sender);
        }

        // Entferne Routen, die nicht mehr beworben werden
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
        new Thread(() -> {
            byte[] buf = new byte[1500];
            while (running) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    dataSocket.receive(packet);
                    ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());

                    byte type = buffer.get();
                    if (type == PacketType.SYN.getValue()) {
                        sendPacket(PacketType.SYN_ACK, new byte[0], packet.getAddress(), packet.getPort());
                    } else if (type == PacketType.SYN_ACK.getValue()) {
                        String key = packet.getAddress().getHostAddress() + ":" + packet.getPort();
                        establishedConnections.add(key);
                        System.out.println("Verbindung hergestellt mit " + key);
                    } else {
                        byte[] msgBytes = new byte[buffer.remaining()];
                        buffer.get(msgBytes);
                        String msg = new String(msgBytes, "UTF-8");
                        System.out.println("Nachricht empfangen von " + packet.getAddress().getHostAddress()
                                + ":" + packet.getPort() + " -> " + msg);
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    private void sendPacket(PacketType type, byte[] data, InetAddress ip, int port) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(1 + data.length);
        buffer.put(type.getValue());
        buffer.put(data);
        byte[] packetData = buffer.array();
        DatagramPacket packet = new DatagramPacket(packetData, packetData.length, ip, port);
        dataSocket.send(packet);
    }

    public void sendMessage(String ipStr, int port, String message) {
        try {
            InetAddress destIP = InetAddress.getByName(ipStr);
            int destDataPort = port + 1;

            byte typeByte = PacketType.MESSAGE.getValue();
            byte[] messageBytes = message.getBytes("UTF-8");

            // Create byte array with 1 extra byte for the type
            byte[] packetBytes = new byte[1 + messageBytes.length];
            packetBytes[0] = typeByte;
            System.arraycopy(messageBytes, 0, packetBytes, 1, messageBytes.length);

            DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, destIP, destDataPort);
            dataSocket.send(packet);

            System.out.println("Nachricht gesendet an " + ipStr + ":" + destDataPort);
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Nachricht: " + e.getMessage());
        }
    }


    public enum PacketType {
        FILE((byte) 0),
        MESSAGE((byte) 1),
        SYN((byte) 2),
        ACK((byte) 3),
        FIN((byte) 4),
        SYN_ACK((byte) 5),
        FIN_ACK((byte) 6);

        private final byte value;

        PacketType(byte value) {
            this.value = value;
        }

        public byte getValue() {
            return value;
        }
    }

    public void initiateConnection(String ipStr, int port) {
        try {
            InetAddress ip = InetAddress.getByName(ipStr);
            sendPacket(PacketType.SYN, new byte[0], ip, port + 1);
            System.out.println("SYN gesendet an " + ipStr + ":" + (port + 1));
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