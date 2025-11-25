package socks;

import org.xbill.DNS.*;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

final class Dns {
    private final DatagramChannel udp;
    private final SelectionKey key;
    private final InetSocketAddress resolver;

    private final Map<Integer, Conn> pending = new HashMap<>();
    private final ArrayDeque<ByteBuffer> out = new ArrayDeque<>();
    private final Random rnd = new Random();

    Dns(Selector selector) throws IOException {
        resolver = pickResolver();

        udp = DatagramChannel.open(StandardProtocolFamily.INET);
        udp.configureBlocking(false);
        udp.connect(resolver);

        key = udp.register(selector, SelectionKey.OP_READ, this);
    }

    String resolverAddr() { return resolver.toString(); }

    void resolveA(Conn who, String domain) throws IOException {
        int id;
        do { id = rnd.nextInt(0x10000); } while (pending.containsKey(id));

        Name name = Name.fromString(domain.endsWith(".") ? domain : domain + ".");
        org.xbill.DNS.Record q = org.xbill.DNS.Record.newRecord(name, Type.A, DClass.IN);
        Message m = Message.newQuery(q);
        m.getHeader().setID(id);

        pending.put(id, who);
        out.add(ByteBuffer.wrap(m.toWire()));
        updateOps();
    }

    void onKey(SelectionKey k) throws IOException {
        if (k.isWritable()) writeAll();
        if (k.isReadable()) readOnce();
    }

    private void writeAll() throws IOException {
        while (!out.isEmpty()) {
            ByteBuffer b = out.peek();
            int n = udp.write(b);
            if (n == 0) break;
            if (!b.hasRemaining()) out.poll();
        }
        updateOps();
    }

    private void readOnce() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(2048);
        int n = udp.read(buf);
        if (n <= 0) return;

        byte[] data = Arrays.copyOf(buf.array(), n);
        Message resp;
        try { resp = new Message(data); }
        catch (IOException e) { return; }

        Conn c = pending.remove(resp.getHeader().getID());
        if (c == null) return;

        InetAddress a = firstA(resp);
        if (a == null) c.onDnsFail();
        else c.onDnsOk(a);
    }

    private void updateOps() {
        int ops = SelectionKey.OP_READ | (out.isEmpty() ? 0 : SelectionKey.OP_WRITE);
        if (key.isValid() && key.interestOps() != ops) key.interestOps(ops);
    }

    private static InetAddress firstA(Message resp) {
        if (resp.getRcode() != Rcode.NOERROR) return null;
        for (org.xbill.DNS.Record r : resp.getSectionArray(Section.ANSWER)) {
            if (r instanceof ARecord ar) return ar.getAddress();
        }
        return null;
    }

    private static InetSocketAddress pickResolver() {
        try {
            ResolverConfig cfg = ResolverConfig.getCurrentConfig();
            List<InetSocketAddress> servers = cfg.servers();
            if (servers != null && !servers.isEmpty()) {
                InetSocketAddress s = servers.get(0);
                InetAddress ip = s.getAddress();
                return ip != null ? new InetSocketAddress(ip, 53) : new InetSocketAddress(s.getHostString(), 53);
            }
        } catch (Exception ignored) {}
        return new InetSocketAddress("8.8.8.8", 53);
    }
}
