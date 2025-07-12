
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;

public class SenderWindow {
    private final int windowSize;
    private final long timeoutMillis;
    private int base = 0;
    private int nextSeqNum = 0;

    private final Map<Integer, DatagramPacket> windowBuffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeoutTask;

    private final DatagramSocket socket;
    private final InetSocketAddress target;

    public SenderWindow(int windowSize, long timeoutMillis, DatagramSocket socket, InetSocketAddress target) {
        this.windowSize = windowSize;
        this.timeoutMillis = timeoutMillis;
        this.socket = socket;
        this.target = target;
    }

    public synchronized void sendPacket(byte[] data) throws Exception {
        if (nextSeqNum < base + windowSize) {
            DatagramPacket packet = new DatagramPacket(data, data.length, target);
            socket.send(packet);
            windowBuffer.put(nextSeqNum, packet);

            if (base == nextSeqNum) {
                startTimer();
            }

            nextSeqNum++;
        } else {
            System.out.println("🟡 Window full, waiting to send...");
        }
    }

    public synchronized void receiveAck(int ackNum) {
        if (ackNum >= base) {
            base = ackNum + 1;
            for (int i = base - 1; i >= 0; i--) {
                windowBuffer.remove(i);
            }

            if (base == nextSeqNum) {
                stopTimer();
            } else {
                restartTimer();
            }
        }
    }

    private void startTimer() {
        timeoutTask = scheduler.schedule(this::timeout, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void restartTimer() {
        stopTimer();
        startTimer();
    }

    private void stopTimer() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
        }
    }

    private void timeout() {
        System.out.println("⏰ Timeout — resending window...");
        try {
            for (int i = base; i < nextSeqNum; i++) {
                DatagramPacket p = windowBuffer.get(i);
                if (p != null) {
                    socket.send(p);
                }
            }
            startTimer();  // restart after resend
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
