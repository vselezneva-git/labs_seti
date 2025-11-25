package socks;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.atomic.AtomicInteger;

final class Conn {
    private static final byte VER = 0x05;
    private static final int CTRL_CAP = 512;
    private static final int BUF_CAP  = 32 * 1024;

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private enum St { GREET, REQ, RESOLVE, CONNECT, RELAY, FAIL_FLUSH }

    private final int id = SEQ.getAndIncrement();
    private final Selector selector;
    private final Dns dns;

    private final SocketChannel client;
    private SocketChannel remote;

    private SelectionKey ck, rk;
    private St st = St.GREET;

    private final ByteBuffer ctrlIn = ByteBuffer.allocate(CTRL_CAP);
    private ByteBuffer ctrlOut = null;

    private final ByteBuffer c2r = ByteBuffer.allocateDirect(BUF_CAP);
    private final ByteBuffer r2c = ByteBuffer.allocateDirect(BUF_CAP);

    private boolean cEof = false, rEof = false;
    private int pendingPort;

    private long upBytes = 0;
    private long downBytes = 0;

    private String targetStr = "?";
    private InetSocketAddress remoteTarget = null;

    Conn(Selector selector, Dns dns, SocketChannel client) {
        this.selector = selector;
        this.dns = dns;
        this.client = client;
    }

    String tag() { return "C#" + id; }

    private void log(String fmt, Object... args) {
        Object[] all = new Object[args.length + 1];
        all[0] = tag();
        System.arraycopy(args, 0, all, 1, args.length);
        System.out.printf("[%s] " + fmt + "%n", all);
    }

    void register() throws IOException {
        ck = client.register(selector, SelectionKey.OP_READ, this);
        updateOps();
    }

    void onConnect() throws IOException {
        if (st != St.CONNECT) return;
        if (!remote.finishConnect()) return;

        InetSocketAddress bnd = (InetSocketAddress) remote.getLocalAddress();
        ctrlOut = reply((byte)0x00, bnd.getAddress(), bnd.getPort());
        st = St.RELAY;

        log("CONNECT OK -> %s (local bind %s)", remoteTarget, bnd);
        updateOps();
    }

    void onRead(SelectionKey key) throws IOException {
        if (key == ck) readClient();
        else if (key == rk) readRemote();
        updateOps();
        tryClose();
    }

    void onWrite(SelectionKey key) throws IOException {
        if (key == ck) writeClient();
        else if (key == rk) writeRemote();
        updateOps();
        tryClose();
    }

    private void readClient() throws IOException {
        if (st == St.GREET || st == St.REQ) {
            int n = client.read(ctrlIn);
            if (n == -1) { close(); return; }

            if (st == St.GREET) {
                if (!tryConsumeGreeting()) return;
                ctrlOut = ByteBuffer.wrap(new byte[]{VER, 0x00});
                st = St.REQ;
                log("SOCKS greeting OK (NO AUTH)");
                return;
            }

            if (st == St.REQ) {
                Req req = tryConsumeRequest();
                if (req == null) return;

                if (req.cmd != 0x01) { fail((byte)0x07); return; }

                if (req.atyp == 0x01) {
                    targetStr = req.ipv4.getHostAddress() + ":" + req.port;
                    log("REQUEST CONNECT %s", targetStr);
                    connectTo(new InetSocketAddress(req.ipv4, req.port));
                    return;
                }

                if (req.atyp == 0x03) {
                    targetStr = req.domain + ":" + req.port;
                    log("REQUEST CONNECT %s (need DNS A)", targetStr);
                    pendingPort = req.port;
                    st = St.RESOLVE;
                    dns.resolveA(this, req.domain);
                    log("DNS query sent for %s", req.domain);
                    updateOps();
                    return;
                }

                fail((byte)0x08);
            }
            return;
        }

        if (st != St.RELAY) return;

        if (c2r.remaining() == 0) return;
        int n = client.read(c2r);
        if (n == -1) {
            cEof = true;
            log("CLIENT EOF (shutdown remote output after flush)");
            shutdownOut(remote);
        } else if (n > 0) {
            upBytes += n;
        }
    }

    private void readRemote() throws IOException {
        if (st != St.RELAY) return;

        if (r2c.remaining() == 0) return;
        int n = remote.read(r2c);
        if (n == -1) {
            rEof = true;
            log("REMOTE EOF (shutdown client output after flush)");
            shutdownOut(client);
        } else if (n > 0) {
            downBytes += n;
        }
    }

    private void writeClient() throws IOException {
        if (ctrlOut != null) {
            client.write(ctrlOut);
            if (!ctrlOut.hasRemaining()) {
                ctrlOut = null;
                if (st == St.FAIL_FLUSH) { close(); return; }
            }
            return;
        }
        writeAvail(client, r2c);
    }

    private void writeRemote() throws IOException {
        writeAvail(remote, c2r);
    }

    void onDnsOk(InetAddress ipv4) {
        if (st != St.RESOLVE) return;
        log("DNS OK %s -> %s", targetStr, ipv4.getHostAddress());
        try {
            connectTo(new InetSocketAddress(ipv4, pendingPort));
        } catch (IOException e) {
            fail((byte)0x04);
        }
    }

    void onDnsFail() {
        if (st == St.RESOLVE) {
            log("DNS FAIL for %s", targetStr);
            fail((byte)0x04);
        }
    }

    private void connectTo(InetSocketAddress dst) throws IOException {
        remoteTarget = dst;
        remote = SocketChannel.open();
        remote.configureBlocking(false);
        remote.socket().setTcpNoDelay(true);

        log("CONNECT start -> %s", dst);

        boolean done = remote.connect(dst);
        rk = remote.register(selector, done ? 0 : SelectionKey.OP_CONNECT, this);

        if (done) {
            InetSocketAddress bnd = (InetSocketAddress) remote.getLocalAddress();
            ctrlOut = reply((byte)0x00, bnd.getAddress(), bnd.getPort());
            st = St.RELAY;
            log("CONNECT OK -> %s (local bind %s)", dst, bnd);
        } else {
            st = St.CONNECT;
        }
        updateOps();
    }

    private void fail(byte rep) {
        ctrlOut = reply(rep, null, 0);
        st = St.FAIL_FLUSH;
        log("FAIL reply REP=0x%02X (%s)", rep, targetStr);
        updateOps();
    }

    private void tryClose() {
        if (st != St.RELAY) return;

        boolean c2rEmpty = c2r.position() == 0;
        boolean r2cEmpty = r2c.position() == 0;

        if (cEof && rEof && c2rEmpty && r2cEmpty) close();
    }

    void close() {
        log("CLOSE up=%d bytes, down=%d bytes, target=%s", upBytes, downBytes, targetStr);
        try { if (ck != null) ck.cancel(); } catch (Exception ignored) {}
        try { if (rk != null) rk.cancel(); } catch (Exception ignored) {}
        try { client.close(); } catch (Exception ignored) {}
        try { if (remote != null) remote.close(); } catch (Exception ignored) {}
    }

    private void updateOps() {
        if (ck != null && ck.isValid()) {
            int ops = 0;

            if (st == St.GREET || st == St.REQ) {
                ops |= SelectionKey.OP_READ;
                if (ctrlOut != null) ops |= SelectionKey.OP_WRITE;
            } else if (st == St.RESOLVE || st == St.CONNECT) {
                if (ctrlOut != null) ops |= SelectionKey.OP_WRITE;
            } else if (st == St.FAIL_FLUSH) {
                if (ctrlOut != null) ops |= SelectionKey.OP_WRITE;
            } else if (st == St.RELAY) {
                if (!cEof && c2r.remaining() > 0) ops |= SelectionKey.OP_READ;
                if (ctrlOut != null || r2c.position() > 0) ops |= SelectionKey.OP_WRITE;
            }

            if (ck.interestOps() != ops) ck.interestOps(ops);
        }

        if (rk != null && rk.isValid()) {
            int ops = 0;

            if (st == St.CONNECT) {
                ops |= SelectionKey.OP_CONNECT;
            } else if (st == St.RELAY) {
                if (!rEof && r2c.remaining() > 0) ops |= SelectionKey.OP_READ;
                if (c2r.position() > 0) ops |= SelectionKey.OP_WRITE;
            }

            if (rk.interestOps() != ops) rk.interestOps(ops);
        }
    }

    private boolean tryConsumeGreeting() {
        ctrlIn.flip();
        try {
            if (ctrlIn.remaining() < 2) return false;
            byte v = ctrlIn.get();
            int nMethods = Byte.toUnsignedInt(ctrlIn.get());
            if (v != VER) { fail((byte)0x01); return true; }
            if (ctrlIn.remaining() < nMethods) return false;
            ctrlIn.position(ctrlIn.position() + nMethods);
            return true;
        } finally {
            ctrlIn.compact();
        }
    }

    private Req tryConsumeRequest() {
        ctrlIn.flip();
        try {
            if (ctrlIn.remaining() < 4) return null;
            byte v = ctrlIn.get();
            byte cmd = ctrlIn.get();
            byte rsv = ctrlIn.get();
            byte atyp = ctrlIn.get();
            if (v != VER || rsv != 0x00) { fail((byte)0x01); return new Req((byte)0, (byte)0, null, null, 0); }

            if (atyp == 0x01) {
                if (ctrlIn.remaining() < 4 + 2) return null;
                byte[] ip = new byte[4];
                ctrlIn.get(ip);
                int port = ((ctrlIn.get() & 0xFF) << 8) | (ctrlIn.get() & 0xFF);
                InetAddress a;
                try { a = InetAddress.getByAddress(ip); }
                catch (Exception e) { fail((byte)0x08); return new Req((byte)0,(byte)0,null,null,0); }
                return new Req(cmd, atyp, a, null, port);
            }

            if (atyp == 0x03) {
                if (ctrlIn.remaining() < 1) return null;
                int len = ctrlIn.get() & 0xFF;
                if (len == 0 || len > 253) { fail((byte)0x08); return new Req((byte)0,(byte)0,null,null,0); }
                if (ctrlIn.remaining() < len + 2) return null;
                byte[] name = new byte[len];
                ctrlIn.get(name);
                int port = ((ctrlIn.get() & 0xFF) << 8) | (ctrlIn.get() & 0xFF);
                String domain = new String(name);
                return new Req(cmd, atyp, null, domain, port);
            }

            fail((byte)0x08);
            return new Req((byte)0,(byte)0,null,null,0);
        } finally {
            ctrlIn.compact();
        }
    }

    private static final class Req {
        final byte cmd, atyp;
        final InetAddress ipv4;
        final String domain;
        final int port;
        Req(byte cmd, byte atyp, InetAddress ipv4, String domain, int port) {
            this.cmd = cmd; this.atyp = atyp; this.ipv4 = ipv4; this.domain = domain; this.port = port;
        }
    }

    private static ByteBuffer reply(byte rep, InetAddress bndAddr, int bndPort) {
        byte[] addr = (bndAddr != null) ? bndAddr.getAddress() : new byte[]{0,0,0,0};
        byte[] out = new byte[10];
        out[0] = VER;
        out[1] = rep;
        out[2] = 0x00;
        out[3] = 0x01;
        System.arraycopy(addr, 0, out, 4, 4);
        out[8] = (byte)((bndPort >>> 8) & 0xFF);
        out[9] = (byte)(bndPort & 0xFF);
        return ByteBuffer.wrap(out);
    }

    private static void writeAvail(SocketChannel dst, ByteBuffer buf) throws IOException {
        if (buf.position() == 0) return;
        buf.flip();
        dst.write(buf);
        buf.compact();
    }

    private static void shutdownOut(SocketChannel ch) {
        if (ch == null) return;
        try { ch.shutdownOutput(); } catch (Exception ignored) {}
    }
}
