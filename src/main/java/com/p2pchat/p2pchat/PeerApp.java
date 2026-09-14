package com.p2pchat.p2pchat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Scanner;

/**
 * Peer console: đăng ký Discovery Server, FIND peer, chat + file TCP trực tiếp P2P.
 */
public class PeerApp {

    private static final int BUFFER_SIZE = 8 * 1024; // 8 KB

    private static String username;
    private static int peerPort;
    private static Socket discoverySocket;
    private static PrintWriter discoveryOut;
    private static BufferedReader discoveryIn;

    private static Socket p2pSocket;
    private static DataOutputStream p2pOut;
    private static DataInputStream p2pIn;

    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        System.out.print("Username: ");
        username = sc.nextLine().trim();

        System.out.print("Peer port (vd 6001): ");
        peerPort = Integer.parseInt(sc.nextLine().trim());

        System.out.print("Discovery Server IP (vd 127.0.0.1 hoac 192.168.x.x): ");
        String serverIp = sc.nextLine().trim();

        connectDiscovery(serverIp, 5000);
        register();
        startPeerServer();

        System.out.println();
        System.out.println("Lenh: list | find <user> | connect <user> | msg <noi dung> | file <duong_dan> | quit");

        while (running) {
            System.out.print("> ");
            if (!sc.hasNextLine()) {
                break;
            }
            String line = sc.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.equalsIgnoreCase("list")) {
                sendDiscovery("LIST");
                System.out.println(discoveryIn.readLine());
            } else if (line.toLowerCase().startsWith("find ")) {
                String target = line.substring(5).trim();
                sendDiscovery("FIND|" + target);
                System.out.println(discoveryIn.readLine());
            } else if (line.toLowerCase().startsWith("connect ")) {
                String target = line.substring(8).trim();
                connectToPeer(target);
            } else if (line.toLowerCase().startsWith("msg ")) {
                String content = line.substring(4);
                sendMessage(content);
            } else if (line.toLowerCase().startsWith("file ")) {
                String path = line.substring(5).trim();
                sendFile(path);
            } else if (line.equalsIgnoreCase("quit")) {
                logoutAndExit();
            } else {
                System.out.println("Lenh khong hop le.");
            }
        }
    }

    private static void connectDiscovery(String ip, int port) throws IOException {
        // LẬP TRÌNH MẠNG:
        // Socket tạo kết nối TCP tới Discovery Server.
        // ip xác định máy Server; port 5000 xác định chương trình Discovery trên máy đó.
        // Handshake TCP hoàn tất thì mới gửi REGISTER / LIST / FIND.
        discoverySocket = new Socket(ip, port);

        // LẬP TRÌNH MẠNG:
        // OutputStream: Peer -> Server. InputStream: Server -> Peer.
        // PrintWriter/BufferedReader làm việc theo dòng text.
        discoveryOut = new PrintWriter(discoverySocket.getOutputStream(), true);
        discoveryIn = new BufferedReader(new InputStreamReader(discoverySocket.getInputStream()));
        System.out.println("Da ket noi Discovery Server " + ip + ":" + port);
    }

    private static void register() throws IOException {
        // REGISTER|username|peerPort — Server tự lấy IPv4 từ socket
        sendDiscovery("REGISTER|" + username + "|" + peerPort);
        String resp = discoveryIn.readLine();
        System.out.println("Server: " + resp);
    }

    private static void sendDiscovery(String cmd) {
        // LẬP TRÌNH MẠNG:
        // println ghi một dòng lệnh lên TCP tới Discovery Server (auto flush).
        discoveryOut.println(cmd);
    }

    private static void startPeerServer() {
        // LẬP TRÌNH MẠNG:
        // Trong P2P, mỗi Peer vừa chủ động kết nối ra ngoài, vừa nhận kết nối từ Peer khác.
        // ServerSocket mở TCP peerPort trên máy này để Peer khác connect vào.
        Thread acceptThread = new Thread(() -> {
            try (ServerSocket peerServer = new ServerSocket(peerPort)) {
                System.out.println("Peer dang lang nghe P2P port " + peerPort);

                while (running) {
                    // LẬP TRÌNH MẠNG:
                    // accept() chờ Peer khác kết nối tới peerPort — blocking call.
                    // Chạy trên thread riêng để không chặn console input của main thread.
                    Socket incoming = peerServer.accept();
                    System.out.println("Peer khac ket noi tu: " + incoming.getInetAddress().getHostAddress());
                    setupP2p(incoming);
                    startP2pReader();
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Loi Peer ServerSocket: " + e.getMessage());
                }
            }
        }, "p2p-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private static void connectToPeer(String targetUsername) throws IOException {
        sendDiscovery("FIND|" + targetUsername);
        String resp = discoveryIn.readLine();
        System.out.println("Server: " + resp);

        // PEER|username|ip|port
        if (resp == null || !resp.startsWith("PEER|")) {
            System.out.println("Khong tim thay peer.");
            return;
        }
        String[] p = resp.split("\\|", -1);
        String ip = p[2];
        int port = Integer.parseInt(p[3]);

        // LẬP TRÌNH MẠNG:
        // Peer hiện tại tạo kết nối TCP trực tiếp tới Peer đích.
        // ip là IPv4 máy đích; port là peerPort mà Peer đó đang ServerSocket lắng nghe.
        // Discovery Server KHÔNG tham gia chat/file sau bước này.
        Socket socket = new Socket(ip, port);
        System.out.println("Da ket noi P2P toi " + targetUsername + " @ " + ip + ":" + port);
        setupP2p(socket);
        startP2pReader();
    }

    private static synchronized void setupP2p(Socket socket) throws IOException {
        if (p2pSocket != null && !p2pSocket.isClosed()) {
            try {
                p2pSocket.close();
            } catch (IOException ignored) {
            }
        }
        p2pSocket = socket;

        // LẬP TRÌNH MẠNG:
        // DataOutputStream/DataInputStream: ghi/đọc kiểu có độ dài (writeUTF/readUTF)
        // và ghi/đọc byte thô cho file (write/read).
        p2pOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        p2pIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    private static void startP2pReader() {
        // LẬP TRÌNH MẠNG:
        // readUTF()/read() là blocking — không chạy trên main/UI thread.
        // Thread riêng nhận tin nhắn/file liên tục trong khi main vẫn nhận lệnh console.
        Thread reader = new Thread(() -> {
            try {
                while (running && p2pSocket != null && !p2pSocket.isClosed()) {
                    // LẬP TRÌNH MẠNG:
                    // readUTF() đọc một chuỗi UTF có độ dài do writeUTF() gửi kèm.
                    // Thread đứng chờ cho tới khi có dữ liệu hoặc kết nối đóng.
                    String header = p2pIn.readUTF();
                    handleP2pMessage(header);
                }
            } catch (IOException e) {
                if (running) {
                    System.out.println("P2P ngat ket noi: " + e.getMessage());
                }
            }
        }, "p2p-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private static void handleP2pMessage(String header) throws IOException {
        String[] parts = header.split("\\|", -1);
        String type = parts[0];

        switch (type) {
            case "MESSAGE" -> {
                // MESSAGE|sender|content
                String sender = parts.length > 1 ? parts[1] : "?";
                String content = parts.length > 2 ? parts[2] : "";
                System.out.println("\n[" + sender + "]: " + content);
                System.out.print("> ");
            }
            case "FILE_REQUEST" -> {
                // FILE_REQUEST|sender|filename|size
                String sender = parts[1];
                String filename = parts[2];
                long size = Long.parseLong(parts[3]);
                System.out.println("\n" + sender + " muon gui file: " + filename + " (" + size + " bytes)");
                System.out.println("Tu dong chap nhan file...");

                // LẬP TRÌNH MẠNG:
                // FILE_ACCEPT báo Peer gửi bắt đầu stream bytes trên cùng TCP connection.
                p2pOut.writeUTF("FILE_ACCEPT|" + filename);
                p2pOut.flush();
                receiveFile(filename, size);
                System.out.print("> ");
            }
            case "FILE_ACCEPT" -> {
                String fname = parts.length > 1 ? parts[1] : "";
                System.out.println("Peer chap nhan file: " + fname);
                notifyFileAccept(fname);
            }
            case "FILE_REJECT" -> {
                System.out.println("Peer tu choi file: " + (parts.length > 1 ? parts[1] : ""));
            }
            default -> System.out.println("Header P2P khong ro: " + header);
        }
    }

    private static void sendMessage(String content) throws IOException {
        if (p2pOut == null) {
            System.out.println("Chua ket noi P2P. Dung: connect <user>");
            return;
        }
        // LẬP TRÌNH MẠNG:
        // writeUTF ghi chuỗi MESSAGE|... trực tiếp Peer → Peer qua TCP.
        // flush() đẩy dữ liệu buffered xuống mạng ngay, không chờ buffer đầy.
        p2pOut.writeUTF("MESSAGE|" + username + "|" + content);
        p2pOut.flush();
        System.out.println("Da gui: " + content);
    }

    private static void sendFile(String path) throws IOException {
        if (p2pOut == null) {
            System.out.println("Chua ket noi P2P. Dung: connect <user>");
            return;
        }
        File file = new File(path);
        if (!file.isFile()) {
            System.out.println("Khong tim thay file: " + path);
            return;
        }

        long size = file.length();
        String filename = file.getName();

        // LẬP TRÌNH MẠNG:
        // Gửi FILE_REQUEST trước; đợi FILE_ACCEPT (từ p2p-reader) rồi mới stream bytes.
        p2pOut.writeUTF("FILE_REQUEST|" + username + "|" + filename + "|" + size);
        p2pOut.flush();

        waitForFileAccept(filename);
        if (!fileAccepted) {
            return;
        }
        streamFileBytes(file);
        System.out.println("Da gui xong file: " + filename);
    }

    private static final Object fileLock = new Object();
    private static volatile String pendingAcceptFile;
    private static volatile boolean fileAccepted;

    private static void waitForFileAccept(String filename) {
        synchronized (fileLock) {
            pendingAcceptFile = filename;
            fileAccepted = false;
            long deadline = System.currentTimeMillis() + 30_000;
            while (!fileAccepted && System.currentTimeMillis() < deadline) {
                try {
                    fileLock.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!fileAccepted) {
                System.out.println("Het thoi gian cho FILE_ACCEPT.");
            }
            pendingAcceptFile = null;
        }
    }

    private static void notifyFileAccept(String filename) {
        synchronized (fileLock) {
            if (filename.equals(pendingAcceptFile)) {
                fileAccepted = true;
                fileLock.notifyAll();
            }
        }
    }

    private static void streamFileBytes(File file) throws IOException {
        // LẬP TRÌNH MẠNG:
        // Không dùng Files.readAllBytes() cho cả file: file lớn sẽ chiếm hết RAM.
        // Đọc từng buffer 8 KB rồi write() xuống OutputStream — tiết kiệm bộ nhớ,
        // dữ liệu chảy dần qua TCP tới Peer nhận.
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                // LẬP TRÌNH MẠNG:
                // write(buffer, 0, n) gửi n byte thô qua TCP tới Peer kia.
                p2pOut.write(buffer, 0, n);
            }
            p2pOut.flush();
        }
    }

    private static void receiveFile(String filename, long size) throws IOException {
        File outFile = new File("received_" + filename);
        // LẬP TRÌNH MẠNG:
        // Nhận đúng 'size' byte từ InputStream, ghi dần ra đĩa theo buffer 8 KB.
        // Không gom cả file vào một mảng byte trong RAM.
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                // LẬP TRÌNH MẠNG:
                // read(buffer, 0, toRead) đọc tối đa toRead byte từ TCP — có thể ít hơn.
                // Blocking cho tới khi có ít nhất 1 byte hoặc kết nối đóng.
                int n = p2pIn.read(buffer, 0, toRead);
                if (n == -1) {
                    throw new IOException("Ket noi dong truoc khi nhan du file");
                }
                fos.write(buffer, 0, n);
                remaining -= n;
            }
        }
        System.out.println("Da nhan file: " + outFile.getAbsolutePath());
    }

    private static void logoutAndExit() throws IOException {
        running = false;
        try {
            sendDiscovery("LOGOUT|" + username);
            if (discoveryIn != null) {
                System.out.println(discoveryIn.readLine());
            }
        } catch (Exception ignored) {
        }
        if (p2pSocket != null) {
            p2pSocket.close();
        }
        if (discoverySocket != null) {
            discoverySocket.close();
        }
        System.out.println("Bye.");
        System.exit(0);
    }
}
