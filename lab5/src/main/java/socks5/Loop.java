package socks5;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.Iterator;

final class Loop {
    private final Selector selector;
    private final ServerSocketChannel server;
    private final Dns dns;

    Loop(int port) throws IOException {
        selector = Selector.open();

        server = ServerSocketChannel.open();
        server.configureBlocking(false);
        server.bind(new InetSocketAddress(port));
        server.register(selector, SelectionKey.OP_ACCEPT);

        dns = new Dns(selector);
        System.out.println("DNS resolver: " + dns.resolverAddr());
        System.out.println("SOCKS5 proxy listening on port " + port);
    }

    void run() throws IOException {
        while (true) {
            selector.select();

            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                if (!key.isValid()) continue;

                try {
                    if (key.channel() == server) {
                        if ((key.readyOps() & SelectionKey.OP_ACCEPT) != 0) acceptAll();
                        continue;
                    }

                    Object att = key.attachment();
                    if (att instanceof Dns) {
                        dns.onKey(key);
                        continue;
                    }
                    if (!(att instanceof Conn conn)) continue;

                    int ops = key.readyOps();
                    if ((ops & SelectionKey.OP_CONNECT) != 0) {
                        conn.onConnect();
                        if (!key.isValid()) continue;
                    }
                    if ((ops & SelectionKey.OP_READ) != 0) {
                        conn.onRead(key);
                        if (!key.isValid()) continue;
                    }
                    if ((ops & SelectionKey.OP_WRITE) != 0) {
                        conn.onWrite(key);
                    }
                } catch (IOException e) {
                    Object att = key.attachment();
                    if (att instanceof Conn c) c.close();
                    else {
                        try { key.channel().close(); } catch (Exception ignored) {}
                    }
                }
            }
        }
    }

    private void acceptAll() throws IOException {
        while (true) {
            SocketChannel c = server.accept();
            if (c == null) return;

            c.configureBlocking(false);
            c.socket().setTcpNoDelay(true);

            InetSocketAddress from = null;
            try { from = (InetSocketAddress) c.getRemoteAddress(); } catch (Exception ignored) {}

            Conn conn = new Conn(selector, dns, c);
            conn.register();

            System.out.printf("[%s] ACCEPT %s%n", conn.tag(), from);
        }
    }
}


//добавить тайм ауты и выход из цикла
