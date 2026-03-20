import java.net.*;
import java.util.Scanner;

public class ChatApp {
    private static ChatNode chatNode;

    public static void main(String[] args) {
        try {
            Scanner scanner = new Scanner(System.in);

            System.out.print("Eigene IP-Adresse: ");
            String myIpStr = scanner.nextLine();
            InetAddress myIp = InetAddress.getByName(myIpStr);

            System.out.print("Eigener Routing-Port: ");
            int routingPort = Integer.parseInt(scanner.nextLine());

            chatNode = new ChatNode(myIp, routingPort);

            boolean running = true;

            while (running) {
                printMenu();
                String choice = scanner.nextLine();

                switch (choice) {
                    case "1":
                        System.out.print("Nachbar IP: ");
                        String ip = scanner.nextLine();
                        System.out.print("Nachbar Routing-Port: ");
                        int port = Integer.parseInt(scanner.nextLine());
                        chatNode.connectNeighbor(ip, port);
                        break;

                    case "2":
                        System.out.print("Empfänger IP: ");
                        String destIp = scanner.nextLine();
                        System.out.print("Empfänger Daten-Port: ");
                        int destPort = Integer.parseInt(scanner.nextLine());
                        System.out.print("Nachricht: ");
                        String message = scanner.nextLine();
                        System.out.println("the message you want to send"+ ":" + message);
                        chatNode.sendMessage(destIp, destPort, message);
                        System.out.println("the message you sent"+ ":" + message);
                        break;

                    case "3":
                        System.out.print("Verbindung trennen IP: ");
                        String discIpStr = scanner.nextLine();
                        InetAddress addr = InetAddress.getByName(discIpStr);
                        System.out.print("Verbindung trennen Port: ");
                        int discPort = Integer.parseInt(scanner.nextLine());
                        InetSocketAddress discIP = new InetSocketAddress(addr, discPort);
                        chatNode.disconnectNeighbor(discIP);
                        break;

                    case "4":
                        chatNode.printRoutingTable();
                        break;

                    case "5":
                        printHelp();
                        break;

                    case "6":
                        System.out.print("Pfad zur Datei: ");
                        String filePath = scanner.nextLine();
                        System.out.print("Empfänger IP: ");
                        String fileDestIp = scanner.nextLine();
                        System.out.print("Empfänger Port: ");
                        int fileDestPort = Integer.parseInt(scanner.nextLine());
                        chatNode.sendFile(filePath, fileDestIp, fileDestPort);
                        break;
                    case "7":
                        running = false;
                        System.out.println("Beende...");
                        chatNode.shutdown();
                        System.exit(0);
                        break;



                    default:
                        System.out.println("Ungültige Eingabe!");
                        break;
                }
            }

            scanner.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void printMenu() {
        System.out.println("\n===== Menü =====");
        System.out.println("1) Nachbar verbinden (IP + Port)");
        System.out.println("2) Nachricht senden (IP + Port + Nachricht)");
        System.out.println("3) Verbindung trennen (IP + Port)");
        System.out.println("4) Routing-Tabelle anzeigen");
        System.out.println("5) Hilfe anzeigen");
        System.out.println("6) Datei senden (Pfad + Ziel-IP + Ziel-Port)");
        System.out.println("7) Beenden");
        System.out.print("Ihre Wahl: ");
    }

    private static void printHelp() {
        System.out.println("\nHilfe:");
        System.out.println("1) Fügt einen direkten Nachbarn zum Routing hinzu.");
        System.out.println("2) Sendet eine Nachricht an einen bestimmten Teilnehmer.");
        System.out.println("3) Trennt die Verbindung zu einem Nachbarn.");
        System.out.println("4) Zeigt die aktuelle Routing-Tabelle an.");
        System.out.println("5) Zeigt diese Hilfe an.");
        System.out.println("6) Beendet das Programm.");
    }
}
