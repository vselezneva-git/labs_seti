import java.io.*;
import java.net.*;
import java.nio.file.*;

public class Client {
    private final String serverHost;
    private final int serverPort;

    public Client(String serverHost, int serverPort) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
    }

    public boolean sendFile(String filePath) {
        Path path = Paths.get(filePath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            System.err.println("Файл не найден: " + filePath);
            return false;
        }

        try (Socket socket = new Socket(serverHost, serverPort);
             DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
             DataInputStream dis = new DataInputStream(socket.getInputStream());
             FileInputStream fis = new FileInputStream(path.toFile())) {

            String fileName = path.getFileName().toString();
            long fileSize = Files.size(path);

            System.out.println("Отправка: " + fileName + " (" + formatSize(fileSize) + ") на " + serverHost + ":" + serverPort);


            dos.writeUTF(fileName);
            dos.writeLong(fileSize);


            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalSent = 0;

            while ((bytesRead = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, bytesRead);
                totalSent += bytesRead;

            }

            System.out.println("\nОжидание подтверждения...");


            boolean success = dis.readBoolean();
            System.out.println(success ? "Передача успешна!" : "Ошибка передачи!");
            return success;

        } catch (IOException e) {
            System.err.println("Ошибка клиента: " + e.getMessage());
            return false;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Использование: java Client <ip_сервера> <порт> <путь_к_файлу>");
            System.out.println("Пример: java Client 192.168.1.100 8080 /home/user/file.txt");
            return;
        }

        String serverHost = args[0];
        int serverPort = Integer.parseInt(args[1]);
        String filePath = args[2];

        new Client(serverHost, serverPort).sendFile(filePath);
    }
}