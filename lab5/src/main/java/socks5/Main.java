package socks5;

public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: java -jar socks5-proxy-1.0.jar <port>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        new Loop(port).run();
    }
}
