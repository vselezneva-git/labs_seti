import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.time.*;

public class Server {
    private final int port;
    private final Path uploadDir = Paths.get("uploads");
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public Server(int port) {
        this.port = port;
        createUploadDir();
    }

    private void createUploadDir() {
        try {
            Files.createDirectories(uploadDir);
            System.out.println("Директория для загрузок: " + uploadDir.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("Ошибка создания директории для загрузок: " + e.getMessage());
        }
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Сервер запущен на порту " + port);
            System.out.println("Ожидание подключений клиентов...");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                executor.submit(new ClientHandler(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("Ошибка сервера: " + e.getMessage());
        }
    }


    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final String clientInfo;
        private final Instant startTime;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientInfo = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            this.startTime = Instant.now();
            System.out.println("Клиент подключен: " + clientInfo);
        }

        @Override
        public void run() {
            try (DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {


                String fileName = dis.readUTF();
                long fileSize = dis.readLong();

                System.out.println("Получение: " + fileName + " (" + formatSize(fileSize) + ") от " + clientInfo);


                Path safeFileName = Paths.get(fileName).getFileName();
                if (safeFileName == null) {
                    throw new IOException("Некорректное имя файла");
                }
                Path filePath = uploadDir.resolve(safeFileName);


                long receivedBytes = receiveFile(dis, filePath, fileSize, fileName);

                boolean success = (receivedBytes == fileSize);
                dos.writeBoolean(success);

                if (success) {
                    System.out.println("Файл " + fileName + " успешно получен от " + clientInfo);
                } else {
                    System.err.println("Ошибка передачи от " + clientInfo +
                            ". Ожидалось: " + fileSize + ", получено: " + receivedBytes);
                    Files.deleteIfExists(filePath);
                }

            } catch (Exception e) {
                System.err.println("Ошибка с клиентом " + clientInfo + ": " + e.getMessage());
            } finally {
                try {
                    socket.close();
                    System.out.println("Клиент отключен: " + clientInfo);
                } catch (IOException e) {
                    System.err.println("Ошибка закрытия сокета: " + e.getMessage());
                }
            }
        }

        private long receiveFile(DataInputStream dis, Path filePath, long expectedSize, String fileName)
                throws IOException {
            Instant lastReport = Instant.now();
            long totalReceived = 0;
            long bytesSinceReport = 0;

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                byte[] buffer = new byte[8192];
                int bytesRead;

                while (totalReceived < expectedSize &&
                        (bytesRead = dis.read(buffer, 0,
                                (int) Math.min(buffer.length, expectedSize - totalReceived))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalReceived += bytesRead;
                    bytesSinceReport += bytesRead;


                    Instant now = Instant.now();
                    if (Duration.between(lastReport, now).toSeconds() >= 3) {
                        printSpeedStats(fileName, bytesSinceReport, totalReceived, now);
                        lastReport = now;
                        bytesSinceReport = 0;
                    }
                }


                Instant endTime = Instant.now();
                if (bytesSinceReport > 0) {
                    printSpeedStats(fileName, bytesSinceReport, totalReceived, endTime);
                }

                return totalReceived;
            }
        }

        private void printSpeedStats(String fileName, long bytesSinceReport, long totalReceived, Instant currentTime) {
            Duration sessionDuration = Duration.between(startTime, currentTime);
            double sessionSeconds = sessionDuration.toMillis() / 1000.0;

            double instantSpeed = bytesSinceReport / (3.0 * 1024 * 1024); // MB/s за 3 секунды
            double averageSpeed = totalReceived / (sessionSeconds * 1024 * 1024); // MB/s за сессию

            System.out.printf("[%s] Мгновенная скорость: %.2f MB/s, Средняя скорость: %.2f MB/s, Прогресс: %s%n",
                    fileName, instantSpeed, averageSpeed, formatSize(totalReceived));
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Использование: java Server <порт>");
            System.out.println("Пример: java Server 8080");
            return;
        }

        int port = Integer.parseInt(args[0]);
        new Server(port).start();
    }
}