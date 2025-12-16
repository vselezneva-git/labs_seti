package socks5;

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

    private SelectionKey clientKey, remoteKey;
    private St currentState = St.GREET;

    private final ByteBuffer ctrlIn = ByteBuffer.allocate(CTRL_CAP);
    private ByteBuffer ctrlOut = null;

    private final ByteBuffer clientToRemoteBuffer = ByteBuffer.allocateDirect(BUF_CAP);
    private final ByteBuffer remoteToClientBuffer = ByteBuffer.allocateDirect(BUF_CAP);

    private boolean clientEndOfStream = false, remoteEndOfStream = false;
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
        clientKey = client.register(selector, SelectionKey.OP_READ, this);
        updateOps();
    }

    void onConnect() throws IOException {
        if (currentState != St.CONNECT) return;
        if (!remote.finishConnect()) return; //если подкл еще не готово - выходим

        InetSocketAddress bnd = (InetSocketAddress) remote.getLocalAddress();
        ctrlOut = reply((byte)0x00, bnd.getAddress(), bnd.getPort());
        currentState = St.RELAY;

        log("CONNECT OK -> %s (local bind %s)", remoteTarget, bnd);
        updateOps();
    }

    void onRead(SelectionKey key) throws IOException {
        if (key == clientKey) readClient();
        else if (key == remoteKey) readRemote();
        updateOps();
        tryClose();
    }

    void onWrite(SelectionKey key) throws IOException {
        if (key == clientKey) writeClient();
        else if (key == remoteKey) writeRemote();
        updateOps();
        tryClose();
    }

    private void readClient() throws IOException {
        if (currentState == St.GREET || currentState == St.REQ) {
            int n = client.read(ctrlIn);
            if (n == -1) { close(); return; }

            if (currentState == St.GREET) {
                if (!tryConsumeGreeting()) return;
                ctrlOut = ByteBuffer.wrap(new byte[]{VER, 0x00});
                currentState = St.REQ;
                log("SOCKS greeting OK (NO AUTH)");
                return;
            }

            if (currentState == St.REQ) {
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
                    currentState = St.RESOLVE;
                    dns.resolveA(this, req.domain);
                    log("DNS query sent for %s", req.domain);
                    updateOps();
                    return;
                }

                fail((byte)0x08);
            }
            return;
        }

        if (currentState != St.RELAY) return;

        if (clientToRemoteBuffer.remaining() == 0) return;
        int n = client.read(clientToRemoteBuffer);
        if (n == -1) {
            clientEndOfStream = true;
            log("CLIENT EOF (shutdown remote output after flush)");
            shutdownOut(remote);
        } else if (n > 0) {
            upBytes += n;
        }
    }

    private void readRemote() throws IOException {
        if (currentState != St.RELAY) return;

        if (remoteToClientBuffer.remaining() == 0) return;
        int n = remote.read(remoteToClientBuffer);
        if (n == -1) {
            remoteEndOfStream = true;
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
                if (currentState == St.FAIL_FLUSH) { close(); return; }
            }
            return;
        }
        writeAvail(client, remoteToClientBuffer);
    }

    private void writeRemote() throws IOException {
        writeAvail(remote, clientToRemoteBuffer);
    }

    void onDnsOk(InetAddress ipv4) {
        if (currentState != St.RESOLVE) return;
        log("DNS OK %s -> %s", targetStr, ipv4.getHostAddress());
        try {
            connectTo(new InetSocketAddress(ipv4, pendingPort));
        } catch (IOException e) {
            fail((byte)0x04);
        }
    }

    void onDnsFail() {
        if (currentState == St.RESOLVE) {
            log("DNS FAIL for %s", targetStr);
            fail((byte)0x04);
        }
    }

    private void connectTo(InetSocketAddress dst) throws IOException {
        remoteTarget = dst; //адрес целевого сервера
        remote = SocketChannel.open();
        remote.configureBlocking(false);
        remote.socket().setTcpNoDelay(true);

        log("CONNECT start -> %s", dst);

        boolean done = remote.connect(dst);
        remoteKey = remote.register(selector, done ? 0 : SelectionKey.OP_CONNECT, this);

        if (done) {
            InetSocketAddress bnd = (InetSocketAddress) remote.getLocalAddress();
            ctrlOut = reply((byte)0x00, bnd.getAddress(), bnd.getPort());
            currentState = St.RELAY;
            log("CONNECT OK -> %s (local bind %s)", dst, bnd);
        } else {
            currentState = St.CONNECT;
        }
        updateOps();
    }

    private void fail(byte rep) {
        ctrlOut = reply(rep, null, 0);
        currentState = St.FAIL_FLUSH;
        log("FAIL reply REP=0x%02X (%s)", rep, targetStr);
        updateOps();
    }

    private void tryClose() {
        if (currentState != St.RELAY) return;

        boolean clientToRemoteBufferEmpty = clientToRemoteBuffer.position() == 0;
        boolean remoteToClientBufferEmpty = remoteToClientBuffer.position() == 0;

        if (clientEndOfStream && remoteEndOfStream && clientToRemoteBufferEmpty && remoteToClientBufferEmpty) close();
    }

    void close() {
        log("CLOSE up=%d bytes, down=%d bytes, target=%s", upBytes, downBytes, targetStr);
        try { if (clientKey != null) clientKey.cancel(); } catch (Exception ignored) {}
        try { if (remoteKey != null) remoteKey.cancel(); } catch (Exception ignored) {}
        try { client.close(); } catch (Exception ignored) {}
        try { if (remote != null) remote.close(); } catch (Exception ignored) {}
    }

    private void updateOps() {
        if (clientKey != null && clientKey.isValid()) {
            int ops = 0;

            if (currentState == St.GREET || currentState == St.REQ) {
                ops |= SelectionKey.OP_READ;
                if (ctrlOut != null) ops |= SelectionKey.OP_WRITE;
            } else if (currentState == St.RESOLVE || currentState == St.CONNECT) {
                if (ctrlOut != null) ops |= SelectionKey.OP_WRITE;
            } else if (currentState == St.FAIL_FLUSH) {
                if (ctrlOut != null) ops |= SelectionKey.OP_WRITE;
            } else if (currentState == St.RELAY) {
                if (!clientEndOfStream && clientToRemoteBuffer.remaining() > 0) ops |= SelectionKey.OP_READ;
                if (ctrlOut != null || remoteToClientBuffer.position() > 0) ops |= SelectionKey.OP_WRITE;
            }

            if (clientKey.interestOps() != ops) clientKey.interestOps(ops);
        }

        if (remoteKey != null && remoteKey.isValid()) {
            int ops = 0;

            if (currentState == St.CONNECT) {
                ops |= SelectionKey.OP_CONNECT;
            } else if (currentState == St.RELAY) {
                if (!remoteEndOfStream && remoteToClientBuffer.remaining() > 0) ops |= SelectionKey.OP_READ;
                if (clientToRemoteBuffer.position() > 0) ops |= SelectionKey.OP_WRITE;
            }

            if (remoteKey.interestOps() != ops) remoteKey.interestOps(ops);
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