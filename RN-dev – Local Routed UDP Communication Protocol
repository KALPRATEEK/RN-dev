Structure in View:
<img width="863" height="867" alt="image" src="https://github.com/user-attachments/assets/62cbc246-c5ac-4d5f-9b39-bef754103137" />


Runtime view:
<img width="1482" height="866" alt="image" src="https://github.com/user-attachments/assets/c555adc5-b972-4637-9b47-baf81299a7f4" />


repo Struc:
<img width="1420" height="583" alt="image" src="https://github.com/user-attachments/assets/ba44ec91-9a5a-41ca-acca-09ffb184c710" />

# RN-dev

A Java-based UDP communication project that combines custom packet handling, routing-table exchange, fragmentation/reassembly, and peer-to-peer message transfer in one small networked system.

## Overview
RN-dev is a lightweight protocol project for experimenting with how communication can work **without relying on a central platform**.  
Each node can:

- connect to neighbors
- exchange routing information
- discover reachable destinations
- establish a simple connection flow
- send messages across one or more hops
- transfer file data inside the same network

This makes the project useful both as a **computer networks learning project** and as a **local-first communication prototype**.

---

## Why this project is interesting

Most modern communication tools depend on large cloud platforms, user accounts, external servers, and provider-controlled infrastructure.
This project explores a different idea:

- communication can stay inside a **local network / lab / private environment**
- nodes can forward packets to each other directly
- routing knowledge is shared between peers
- data can move between machines without needing a big external service in the middle

That makes RN-dev interesting for:
- university networking demos
- LAN-based communication experiments
- private local data sharing in a controlled environment
- learning how routing, fragmentation, forwarding, and packet headers work in practice

---

## What the project demonstrates
RN-dev brings together multiple networking concepts in one project:
- **custom packet format**
- **distance-vector-style routing behavior**
- **periodic routing-table updates**
- **multi-hop forwarding**
- **connection-style control packets** (`SYN`, `SYN_ACK`, `ACK`, `FIN`, `FIN_ACK`)
- **fragmentation and reassembly**
- **CRC-based integrity checking**
- **message and file transfer commands from the CLI**

---

## How it works
Each node starts with:
- a **routing port** `n`
- a **data port** `n + 1`
The routing port is used for routing-table communication between neighbors.  
The data port is used for actual message and file transport.
### High-level flow
1. Start multiple nodes on different machines or terminals.
2. Connect direct neighbors.
3. Nodes exchange routing information periodically.
4. A node learns which destination is reachable through which next hop.
5. When sending data, the node:
   - looks up the route
   - opens or reuses a connection to the next hop
   - fragments the payload if needed
   - sends packets with custom headers
6. The destination node reassembles the data and displays or stores it.

---
## Project structure
- `ChatApp.java`  
  Command-line entry point and user interaction.
- `ChatNode.java`  
  Main node logic: sockets, routing table, forwarding, neighbor management, and receive/send flow.
- `ConnectionManager.java`  
  Handles connection-style packet flow and data sending.
- `FragmentManager.java`  
  Splits large payloads into chunks and reassembles them.
- `PacketHeader.java`  
  Defines the packet header and packet types.
- `RoutingEntry.java`  
  Represents entries inside the routing table.
- `RoutingTableManager.java`  
  Additional routing-table helper logic.
- `CRC.java`  
  CRC32-based integrity checking.
---

## What is shown in the terminal
During execution, the program displays useful runtime information such as:
- available commands
- neighbor connections and disconnections
- routing-table updates
- connection establishment messages
- next-hop lookup details
- packet forwarding logs
- received messages
- received file save information
- the current routing table

This makes the project easy to demonstrate during a presentation because the terminal output shows the protocol behavior step by step.
---

## Running the project with the JAR
The repository contains `lab3.jar`.
Run a node like this:
```bash
java -jar lab3.jar <IP> <RoutingPort>
