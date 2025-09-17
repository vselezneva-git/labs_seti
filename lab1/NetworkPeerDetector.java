import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class NetworkPeerDetector {

    private static final int NETWORK_PORT = 8888;
    private static final int BEAT_INTERVAL_MS = 1000;
    private static final int PEER_TIMEOUT_MS = 5000;
    private static final String BEAT_SIGNAL = "BEAT:";

    private final InetAddress multicastGroup;
    private final NetworkInterface networkInterface;
    private final String instanceId;
    private final ConcurrentMap<String, Long> peerLastSeen;
    private final ScheduledExecutorService scheduler;
    private MulticastSocket multicastSocket;
    private volatile boolean isActive;

    public NetworkPeerDetector(String groupIp) throws IOException {
        this.multicastGroup = InetAddress.getByName(groupIp);
        this.networkInterface = determineAppropriateInterface();
        this.instanceId = generateInstanceIdentifier();
        this.peerLastSeen = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(3);
        this.isActive = true;
        initializeSocket();
        System.out.println("Started detector");
    }

    private NetworkInterface determineAppropriateInterface() throws SocketException {
        List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        for (NetworkInterface iface : interfaces) {
            try {
                if (!iface.isUp() || iface.isLoopback() || !iface.supportsMulticast()) continue;
                List<InetAddress> addresses = Collections.list(iface.getInetAddresses());
                for (InetAddress addr : addresses) {
                    if (addr.getClass().equals(multicastGroup.getClass())) {
                        return iface;
                    }
                }
            } catch (SocketException e) {
                continue;
            }
        }
        return null;
    }

    private String generateInstanceIdentifier() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private void initializeSocket() throws IOException {
        if (networkInterface == null) {
            throw new IOException("No suitable network interface found for multicast.");
        }
        multicastSocket = new MulticastSocket(NETWORK_PORT);
        multicastSocket.setReuseAddress(true);
        multicastSocket.setNetworkInterface(networkInterface);
        multicastSocket.setTimeToLive(1);
        multicastSocket.joinGroup(new InetSocketAddress(multicastGroup, NETWORK_PORT), networkInterface);
    }

    public void beginDetection() {
        scheduler.scheduleAtFixedRate(this::transmitPresence, 0, BEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.execute(this::listenForPeers);
        scheduler.scheduleAtFixedRate(this::pruneInactivePeers, 1, 1, TimeUnit.SECONDS);
    }

    private void transmitPresence() {
        try {
            String presenceMessage = BEAT_SIGNAL + instanceId;
            byte[] messageData = presenceMessage.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(messageData, messageData.length, multicastGroup, NETWORK_PORT);
            multicastSocket.send(packet);
        } catch (IOException e) {
            if (isActive) {
                System.err.println("Transmission error: " + e.getMessage());
            }
        }
    }

    private void listenForPeers() {
        byte[] receptionBuffer = new byte[1024];

        while (isActive) {
            try {
                DatagramPacket incomingPacket = new DatagramPacket(receptionBuffer, receptionBuffer.length);
                multicastSocket.receive(incomingPacket);

                processIncomingMessage(incomingPacket);
            } catch (IOException e) {
                if (isActive) {
                    System.err.println("Reception error: " + e.getMessage());
                }
            }
        }
    }

    private void processIncomingMessage(DatagramPacket packet) {
        String messageContent = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8);

        if (messageContent.startsWith(BEAT_SIGNAL)) {
            String peerIdentifier = messageContent.substring(BEAT_SIGNAL.length());
            if (!instanceId.equals(peerIdentifier)) {
                String peerAddress = packet.getAddress().getHostAddress();
                peerLastSeen.put(peerAddress, System.currentTimeMillis());
                displayActivePeers();
            }
        }
    }

    private void pruneInactivePeers() {
        long currentTimestamp = System.currentTimeMillis();
        boolean changesMade = false;

        Iterator<Map.Entry<String, Long>> iterator = peerLastSeen.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (currentTimestamp - entry.getValue() > PEER_TIMEOUT_MS) {
                iterator.remove();
                changesMade = true;
            }
        }

        if (changesMade) {
            displayActivePeers();
        }
    }

    private void displayActivePeers() {
        List<String> activePeers = new ArrayList<>(peerLastSeen.keySet());
        Collections.sort(activePeers);
        System.out.println("Detected peers: " + activePeers);
    }

    public void terminate() {
        isActive = false;
        scheduler.shutdown();

        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (multicastSocket != null && !multicastSocket.isClosed()) {
            try {
                multicastSocket.leaveGroup(new InetSocketAddress(multicastGroup, NETWORK_PORT), networkInterface);
            } catch (IOException e) {
            }
            multicastSocket.close();
        }
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("Required: java NetworkPeerDetector <multicast_group_address>");
            System.exit(1);
        }

        try {
            NetworkPeerDetector detector = new NetworkPeerDetector(arguments[0]);
            Runtime.getRuntime().addShutdownHook(new Thread(detector::terminate));
            detector.beginDetection();
        } catch (IOException e) {
            System.err.println("Initialization failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
