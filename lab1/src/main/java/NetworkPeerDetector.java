import com.google.common.primitives.Bytes;
import com.google.common.primitives.Shorts;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class NetworkPeerDetector {

    private static final int BEAT_INTERVAL_MS = 1000;
    private static final int PEER_TIMEOUT_MS = 5000;

    private final InetAddress multicastGroup;
    private final int networkPort;
    private final NetworkInterface networkInterface;
    private final boolean isIPv6;
    private final String instanceId;
    private final String userMessage;

    private final ConcurrentMap<String, PeerInfo> peers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(3);
    private MulticastSocket socket;
    private volatile boolean isActive = true;

    private static final byte MSG_TYPE_BEAT = 0;
    private static final byte MSG_TYPE_DISCONNECT = 1;

    private static class PeerInfo {
        volatile long lastSeen;
        volatile String ip;
        volatile String message;

        PeerInfo(String ip, String message, long seen) {
            this.ip = ip;
            this.message = message;
            this.lastSeen = seen;
        }
    }

    public NetworkPeerDetector(String groupIp, int port, String interfaceName, String message) throws IOException {
        this.multicastGroup = InetAddress.getByName(groupIp);
        this.networkPort = port;
        this.isIPv6 = multicastGroup instanceof Inet6Address;
        this.instanceId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        this.userMessage = message;

        if (interfaceName == null || interfaceName.isEmpty() || "auto".equals(interfaceName)) {
            this.networkInterface = findBestNetworkInterface();
            System.out.println("Auto-selected network interface: " + networkInterface.getName());
        } else {
            NetworkInterface nif = NetworkInterface.getByName(interfaceName);
            if (nif == null) throw new IOException("Network interface not found: " + interfaceName);
            this.networkInterface = nif;
        }

        printInterfaces();
        initializeSocket();

        System.out.println("Started detector. group=" + multicastGroup.getHostAddress() +
                " port=" + port + " iface=" + networkInterface.getName() + " ipv6=" + isIPv6 +
                " id=" + instanceId + " message=" + message);
    }


    private NetworkInterface findBestNetworkInterface() throws SocketException {
        List<NetworkInterface> candidates = new ArrayList<>();

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            try {
                if (isSuitableInterface(ni)) {
                    candidates.add(ni);
                }
            } catch (SocketException e) {
                continue;
            }
        }

        NetworkInterface selected = selectBestInterface(candidates);

        if (selected == null) {
            throw new SocketException("No suitable network interface found for multicast");
        }

        return selected;
    }


    private boolean isSuitableInterface(NetworkInterface ni) throws SocketException {
        if (!ni.isUp() || !ni.supportsMulticast() || ni.isLoopback()) {
            return false;
        }

        Enumeration<InetAddress> addresses = ni.getInetAddresses();
        boolean hasSuitableAddress = false;

        while (addresses.hasMoreElements()) {
            InetAddress addr = addresses.nextElement();
            if (!isIPv6 && addr instanceof Inet4Address) {
                if (!addr.isLinkLocalAddress() && !addr.isLoopbackAddress()) {
                    hasSuitableAddress = true;
                    break;
                }
            }

            else if (isIPv6 && addr instanceof Inet6Address) {
                if (!addr.isLinkLocalAddress() && !addr.isLoopbackAddress()) {
                    hasSuitableAddress = true;
                    break;
                }
            }
        }

        return hasSuitableAddress;
    }


    private NetworkInterface selectBestInterface(List<NetworkInterface> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        NetworkInterface best = null;
        int bestScore = -1;

        for (NetworkInterface ni : candidates) {
            String name = ni.getName().toLowerCase();
            int score = 0;


            if (name.startsWith("eth") || name.startsWith("enp") || name.startsWith("ens")) {
                score += 30;
            } else if (name.startsWith("wlan") || name.startsWith("wifi")) {
                score += 20;
            } else {
                score += 10;
            }


            if (score > bestScore) {
                bestScore = score;
                best = ni;
            }
        }

        return best;
    }

    private void printInterfaces() {
        System.out.println("Available network interfaces:");
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                try {
                    String status = ni.isUp() ? "UP" : "DOWN";
                    String multicast = ni.supportsMulticast() ? "MULTICAST" : "NO_MCAST";
                    String loopback = ni.isLoopback() ? "LOOPBACK" : "";
                    String current = (ni.equals(networkInterface)) ? " [SELECTED]" : "";

                    System.out.printf("IF: %-8s %-6s %-10s %-10s%s\n",
                            ni.getName(), status, multicast, loopback, current);

                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress addr = addrs.nextElement();
                        System.out.println("  addr: " + addr.getHostAddress());
                    }
                } catch (Exception e) {
                    System.out.println("IF: " + ni.getName() + " (error getting info)");
                }
            }
        } catch (SocketException e) {
            System.err.println("Failed to list interfaces: " + e.getMessage());
        }
    }

    private void initializeSocket() throws IOException {
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);

        if (isIPv6) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("::"), networkPort));
        } else {
            socket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), networkPort));
        }

        socket.setNetworkInterface(networkInterface);
        socket.setTimeToLive(1);
        socket.setLoopbackMode(false);

        SocketAddress group = new InetSocketAddress(multicastGroup, networkPort);
        socket.joinGroup(group, networkInterface);
    }

    public void beginDetection() {
        scheduler.scheduleAtFixedRate(() -> transmitPacket(MSG_TYPE_BEAT), 0, BEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.execute(this::listenLoop);
        scheduler.scheduleAtFixedRate(this::pruneInactive, 1, 1, TimeUnit.SECONDS);
    }

    private byte[] serializePacket(byte type, String message) {
        byte[] msg = message.getBytes(StandardCharsets.UTF_8);
        if (msg.length > 500) msg = Arrays.copyOf(msg, 500);
        byte[] len = Shorts.toByteArray((short) msg.length);
        return Bytes.concat(new byte[]{type}, len, msg);
    }

    private void transmitPacket(byte type) {
        try {
            String composed = instanceId + ":" + userMessage;
            byte[] data = serializePacket(type, composed);
            DatagramPacket pkt = new DatagramPacket(data, data.length, multicastGroup, networkPort);
            socket.send(pkt);
        } catch (IOException e) {
            if (isActive) System.err.println("Transmit error: " + e.getMessage());
        }
    }

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        while (isActive) {
            try {
                DatagramPacket pkt = new DatagramPacket(buffer, buffer.length);
                socket.receive(pkt);
                handlePacket(pkt);
            } catch (IOException e) {
                if (isActive) System.err.println("Receive error: " + e.getMessage());
            }
        }
    }

    private void handlePacket(DatagramPacket pkt) {
        byte[] data = Arrays.copyOf(pkt.getData(), pkt.getLength());
        if (data.length < 3) return;

        byte type = data[0];
        short len = Shorts.fromByteArray(Arrays.copyOfRange(data, 1, 3));
        if (len < 0 || len > 500 || data.length < 3 + len) return;

        String payload = new String(data, 3, len, StandardCharsets.UTF_8);
        String remoteIp = pkt.getAddress().getHostAddress();

        String[] parts = payload.split(":", 2);
        if (parts.length < 1) return;
        String peerId = parts[0];
        String peerMsg = parts.length > 1 ? parts[1] : "";

        if (peerId.equals(this.instanceId)) {
            return;
        }

        if (type == MSG_TYPE_BEAT) {
            long now = System.currentTimeMillis();
            peers.compute(peerId, (k, v) -> {
                if (v == null) {
                    System.out.println("New peer discovered: " + peerId + " from " + remoteIp + " msg=" + peerMsg);
                    return new PeerInfo(remoteIp, peerMsg, now);
                }
                v.ip = remoteIp;
                v.message = peerMsg;
                v.lastSeen = now;
                return v;
            });
            displayIfChanged();
        } else if (type == MSG_TYPE_DISCONNECT) {
            peers.remove(peerId);
            displayIfChanged();
            System.out.println("Peer disconnected: id=" + peerId + " ip=" + remoteIp);
        }
    }

    private volatile String lastPrinted = "";

    private void displayIfChanged() {
        Map<String, List<String>> ipToIds = new TreeMap<>();
        for (Map.Entry<String, PeerInfo> e : peers.entrySet()) {
            String id = e.getKey();
            String ip = e.getValue().ip != null ? e.getValue().ip : "unknown";
            ipToIds.computeIfAbsent(ip, k -> new ArrayList<>()).add(id);
        }

        List<String> display = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : ipToIds.entrySet()) {
            String ip = e.getKey();
            List<String> ids = e.getValue();
            if (ids.size() == 1) {
                display.add(ip);
            } else {
                for (String id : ids) {
                    display.add(id + "@" + ip);
                }
            }
        }

        Collections.sort(display);
        String out = "Active peers (" + display.size() + "): " + display;
        if (!out.equals(lastPrinted)) {
            System.out.println(out);
            lastPrinted = out;
        }
    }

    private void pruneInactive() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Map.Entry<String, PeerInfo>> it = peers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, PeerInfo> e = it.next();
            if (now - e.getValue().lastSeen > PEER_TIMEOUT_MS) {
                System.out.println("Peer timeout: " + e.getKey() + " from " + e.getValue().ip);
                it.remove();
                changed = true;
            }
        }
        if (changed) displayIfChanged();
    }

    public void terminate() {
        isActive = false;
        transmitPacket(MSG_TYPE_DISCONNECT);
        scheduler.shutdownNow();

        if (socket != null && !socket.isClosed()) {
            try {
                socket.leaveGroup(new InetSocketAddress(multicastGroup, networkPort), networkInterface);
            } catch (IOException ignored) {}
            socket.close();
        }
    }

    public static void main(String[] args) {
        if (args.length < 3 || args.length > 4) {
            System.err.println("Usage: java NetworkPeerDetector <group_ip> <port> <message> [interface_name]");
            System.err.println("       java NetworkPeerDetector 224.0.0.1 8888 \"my message\" eth0");
            System.err.println("       java NetworkPeerDetector 224.0.0.1 8888 \"my message\"     (auto-select)");
            System.err.println("       java NetworkPeerDetector 224.0.0.1 8888 \"my message\" auto (auto-select)");
            System.exit(1);
        }

        try {
            String group = args[0];
            int port = Integer.parseInt(args[1]);
            String msg = args[2];
            String iface = (args.length == 4) ? args[3] : null;

            NetworkPeerDetector det = new NetworkPeerDetector(group, port, iface, msg);
            Runtime.getRuntime().addShutdownHook(new Thread(det::terminate));
            det.beginDetection();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}