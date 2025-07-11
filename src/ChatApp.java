
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Scanner;
import javax.swing.JFileChooser;
import java.io.File;
import java.io.FileInputStream;

public class ChatApp {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.out.println("Usage: java ChatApp <IP> <RoutingPort>");
            return;
        }

        InetAddress ip = InetAddress.getByName(args[0]);
        int routingPort = Integer.parseInt(args[1]);
        ChatNode node = new ChatNode(ip, routingPort);
        Scanner scanner = new Scanner(System.in);

        System.out.println("ChatApp gestartet. Verfügbare Befehle:");
        System.out.println("1 <IP> <Port> - Nachbar verbinden");
        System.out.println("2 <IP> <Port> - Nachbar trennen");
        System.out.println("3 <IP> <Port> - Verbindung initiieren");
        System.out.println("4 <IP> <Port> <Nachricht> - Nachricht senden");
        System.out.println("5 <IP> <Port> <Dateipfad> - Datei senden");
        System.out.println("6 - Routing-Tabelle anzeigen");
        System.out.println("7 - Beenden");

        while (true) {
            System.out.print("Befehl eingeben: ");
            String input = scanner.nextLine().trim();
            String[] parts = input.split(" ", 4);

            try {
                switch (parts[0]) {
                    case "1":
                        if (parts.length < 3) {
                            System.out.println("Usage: 1 <IP> <Port>");
                            break;
                        }
                        node.connectNeighbor(parts[1], Integer.parseInt(parts[2]));
                        break;

                    case "2":
                        if (parts.length < 3) {
                            System.out.println("Usage: 2 <IP> <Port>");
                            break;
                        }
                        node.disconnectNeighbor(new InetSocketAddress(
                                InetAddress.getByName(parts[1]), Integer.parseInt(parts[2])));
                        break;

                    case "3":
                        if (parts.length < 3) {
                            System.out.println("Usage: 3 <IP> <Port>");
                            break;
                        }
                        node.initiateConnection(parts[1], Integer.parseInt(parts[2]));
                        break;

                    case "4":
                        if (parts.length < 4) {
                            System.out.println("Usage: 4 <IP> <Port> <Nachricht>");
                            break;
                        }
                        node.sendMessage(parts[1], Integer.parseInt(parts[2]), parts[3]);
                        break;

                    case "5":
                        if (parts.length < 4) {
                            System.out.println("Usage: 5 <IP> <Port> <Dateipfad>");
                            break;
                        }
                        String destIp = parts[1];
                        int destPort = Integer.parseInt(parts[2]);
                        String filePath = parts[3];
                        sendFile(node, destIp, destPort, filePath);
                        break;

                    case "5g": // GUI-based file selection
                        if (parts.length < 3) {
                            System.out.println("Usage: 5g <IP> <Port>");
                            break;
                        }
                        destIp = parts[1];
                        destPort = Integer.parseInt(parts[2]);
                        sendFileWithGUI(node, destIp, destPort);
                        break;

                    case "6":
                        node.printRoutingTable();
                        break;

                    case "7":
                        node.shutdown();
                        scanner.close();
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Unbekannter Befehl: " + parts[0]);
                }
            } catch (Exception e) {
                System.out.println("Fehler: " + e.getMessage());
            }
        }
    }

    private static void sendFile(ChatNode node, String ip, int port, String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Datei nicht gefunden oder ungültig: " + filePath);
            return;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] fileData = fis.readAllBytes();
            node.sendMessage(ip, port, fileData);
            System.out.println("Datei gesendet: " + filePath + " an " + ip + ":" + (port + 1));
        } catch (Exception e) {
            System.out.println("Fehler beim Senden der Datei: " + e.getMessage());
        }
    }

    private static void sendFileWithGUI(ChatNode node, String ip, int port) throws Exception {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home")));
        int result = fileChooser.showOpenDialog(null);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] fileData = fis.readAllBytes();
                node.sendMessage(ip, port, fileData);
                System.out.println("Datei gesendet: " + file.getAbsolutePath() + " an " + ip + ":" + (port + 1));
            } catch (Exception e) {
                System.out.println("Fehler beim Senden der Datei: " + e.getMessage());
            }
        } else {
            System.out.println("Keine Datei ausgewählt.");
        }
    }
}