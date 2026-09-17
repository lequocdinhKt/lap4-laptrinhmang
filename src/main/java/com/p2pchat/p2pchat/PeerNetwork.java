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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Toàn bộ lập trình mạng của Peer (Discovery + P2P chat/file).
 * Không đụng JavaFX — báo UI qua callback.
 */
public class PeerNetwork {

    private static final int BUFFER_SIZE = 8 * 1024;   
    private static final int DISCOVERY_PORT = 2005;

    private String username;
    private int peerPort;

    private Socket discoverySocket;
    private PrintWriter discoveryOut;
    private BufferedReader discoveryIn;

    private Socket p2pSocket;
    private DataOutputStream p2pOut;
    private DataInputStream p2pIn;
    private ServerSocket peerServer;

    private volatile boolean running = true;

    private final Object fileLock = new Object();
    private volatile String pendingAcceptFile;
    private volatile boolean fileAccepted;
 
    // Các callback để báo UI thông tin.    
    // onMessage: tin nhắn chat/file.
    // onStatus: trạng thái kết nối, thông báo lỗi.
    // onPeerList: danh sách peer hiện có.
    // onFileReceived: khi nhận được file từ peer khác.
    private Consumer<String> onMessage = s -> {};
    private Consumer<String> onStatus = s -> {};
    private Consumer<List<String>> onPeerList = list -> {};
    private Consumer<File> onFileReceived = f -> {};

    // Các setter để đặt các callback (callback pattern).
    // messageHandler: hàm xử lý tin nhắn chat/file.
    // statusHandler: hàm xử lý trạng thái kết nối, thông báo lỗi.
    // peerListHandler: hàm xử lý danh sách peer hiện có.
    // fileReceivedHandler: hàm xử lý khi nhận được file từ peer khác.
    public void setOnMessage(Consumer<String> messageHandler) {
        this.onMessage = messageHandler != null ? messageHandler : message -> {};
    }

    public void setOnStatus(Consumer<String> statusHandler) {
        this.onStatus = statusHandler != null ? statusHandler : status -> {};
    }

    public void setOnPeerList(Consumer<List<String>> peerListHandler) {
        this.onPeerList = peerListHandler != null ? peerListHandler : peers -> {};
    }

    public void setOnFileReceived(Consumer<File> fileReceivedHandler) {
        this.onFileReceived = fileReceivedHandler != null ? fileReceivedHandler : file -> {};
    }

    public void connectDiscovery(String serverIp, String username, int peerPort) {
        this.username = username;
        this.peerPort = peerPort;

        // LẬP TRÌNH MẠNG:
        // Socket / readLine là blocking — chạy background thread, không chặn UI.
        new Thread(() -> {
            try {
                // LẬP TRÌNH MẠNG:
                // Socket TCP tới Discovery Server (ip + port 2005).
                discoverySocket = new Socket(serverIp, DISCOVERY_PORT);

                // LẬP TRÌNH MẠNG:
                // OutputStream: Peer -> Server. InputStream: Server -> Peer.
                // Tạo PrintWriter để gửi dữ liệu từ Peer (client) tới Discovery Server qua mạng:
                // - discoverySocket.getOutputStream() lấy OutputStream gắn với socket TCP tới server, cho phép ghi dữ liệu (gửi đi).
                // - Tham số 'true' bật chế độ autoFlush: mỗi lần gọi println, dữ liệu sẽ được đẩy ngay xuống mạng (không bị giữ trong bộ nhớ đệm chờ flush thủ công).
                // => Khi gọi discoveryOut.println(...), chuỗi sẽ được gửi lập tức tới server qua socket.
                discoveryOut = new PrintWriter(discoverySocket.getOutputStream(), true); 
                discoveryIn = new BufferedReader(new InputStreamReader(discoverySocket.getInputStream()));

                discoveryOut.println("REGISTER|" + username + "|" + peerPort);
                // LẬP TRÌNH MẠNG:
                // readLine() chờ một dòng phản hồi từ Server (blocking).
                String resp = discoveryIn.readLine();
                onStatus.accept("Discovery: " + resp);
                onMessage.accept("REGISTER -> " + resp);

                startPeerServer();
                refreshPeerListInternal();
            } catch (IOException e) {
                onStatus.accept("Loi: " + e.getMessage());
            }
        }, "discovery-connect").start();
    }

    private void startPeerServer() {
        Thread acceptThread = new Thread(() -> {
            try {
                // LẬP TRÌNH MẠNG:
                // ServerSocket mở TCP peerPort trên Peer này.
                // Peer khác dùng IPv4 + port này để kết nối trực tiếp (P2P).
                peerServer = new ServerSocket(peerPort);
                onMessage.accept("Lang nghe P2P port " + peerPort);

                while (running) {
                    // LẬP TRÌNH MẠNG:
                    // accept() chờ Peer khác kết nối — blocking call.
                    // Chạy thread riêng để không chặn thread khác.
                    Socket incoming = peerServer.accept();
                    setupP2p(incoming);
                    startP2pReader();
                    onMessage.accept("Peer ket noi toi tu " + incoming.getInetAddress().getHostAddress());
                }
            } catch (IOException e) {
                if (running) {
                    onMessage.accept("Loi ServerSocket: " + e.getMessage());
                }
            }
        }, "p2p-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void refreshPeerList() {
        new Thread(this::refreshPeerListInternal, "list").start();
    }

    private void refreshPeerListInternal() {
        try {
            String resp;
            synchronized (this) {
                if (discoveryOut == null) {
                    return;
                }
                discoveryOut.println("LIST");
                // LẬP TRÌNH MẠNG:
                // Đọc phản hồi LIST từ Discovery Server.
                resp = discoveryIn.readLine();
            }

            List<String> names = new ArrayList<>();
            if (resp != null && resp.startsWith("LIST") && !resp.equals("LIST|EMPTY")) {
                String[] parts = resp.split("\\|", -1);
                for (int i = 1; i < parts.length; i++) {
                    String entry = parts[i];
                    if (entry.contains("@")) {
                        String name = entry.substring(0, entry.indexOf('@'));
                        if (!name.equals(username)) {
                            names.add(name);
                        }
                    }
                }
            }
            onPeerList.accept(names);
            onMessage.accept("LIST -> " + resp);
        } catch (IOException e) {
            onMessage.accept("LIST loi: " + e.getMessage());
        }
    }

    public void connectPeer(String targetUsername) {
        new Thread(() -> {
            try {
                String resp;
                synchronized (this) {
                    discoveryOut.println("FIND|" + targetUsername);
                    resp = discoveryIn.readLine();
                }
                onMessage.accept("FIND -> " + resp);
                if (resp == null || !resp.startsWith("PEER|")) {
                    return;
                }
                String[] p = resp.split("\\|", -1);
                String ip = p[2];
                int port = Integer.parseInt(p[3]);

                // LẬP TRÌNH MẠNG:
                // Tạo kết nối TCP trực tiếp tới Peer đích.
                // ip = máy đích, port = ServerSocket đang lắng nghe trên máy đó.
                // Discovery Server không tham gia chat/file sau bước này.
                Socket socket = new Socket(ip, port);
                setupP2p(socket);
                startP2pReader();
                onMessage.accept("Da ket noi P2P toi " + targetUsername);
            } catch (IOException e) {
                onMessage.accept("Connect Peer loi: " + e.getMessage());
            }
        }, "connect-peer").start();
    }

    private synchronized void setupP2p(Socket socket) throws IOException {
        if (p2pSocket != null && !p2pSocket.isClosed()) {
            try {
                p2pSocket.close();
            } catch (IOException ignored) {
            }
        }
        p2pSocket = socket;

        // LẬP TRÌNH MẠNG:
        // DataOutputStream/DataInputStream: writeUTF/readUTF cho text, write/read cho file.
        p2pOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        p2pIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    private void startP2pReader() {
        Thread reader = new Thread(() -> {
            try {
                while (running && p2pSocket != null && !p2pSocket.isClosed()) {
                    // LẬP TRÌNH MẠNG:
                    // readUTF() chờ dữ liệu từ Peer — blocking, phải ở background thread.
                    String header = p2pIn.readUTF();
                    handleP2p(header);
                }
            } catch (IOException e) {
                if (running) {
                    onMessage.accept("P2P ngat: " + e.getMessage());
                }
            }
        }, "p2p-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void handleP2p(String header) throws IOException {
        String[] parts = header.split("\\|", -1);
        switch (parts[0]) {
            case "MESSAGE" -> {
                String sender = parts.length > 1 ? parts[1] : "?";
                String content = parts.length > 2 ? parts[2] : "";
                onMessage.accept("[" + sender + "]: " + content);
            }
            case "FILE_REQUEST" -> {
                String sender = parts[1];
                String filename = parts[2];
                long size = Long.parseLong(parts[3]);
                onMessage.accept(sender + " gui file: " + filename + " (" + size + " B)");

                // LẬP TRÌNH MẠNG:
                // writeUTF gửi FILE_ACCEPT; flush đẩy buffer xuống socket ngay.
                p2pOut.writeUTF("FILE_ACCEPT|" + filename);
                p2pOut.flush();
                receiveFile(filename, size);
            }
            case "FILE_ACCEPT" -> {
                String fname = parts.length > 1 ? parts[1] : "";
                notifyFileAccept(fname);
                onMessage.accept("Peer chap nhan file: " + fname);
            }
            case "FILE_REJECT" -> onMessage.accept(
                    "Peer tu choi file: " + (parts.length > 1 ? parts[1] : ""));
            default -> onMessage.accept("P2P: " + header);
        }
    }

    public void sendMessage(String content) {
        new Thread(() -> {
            try {
                if (p2pOut == null) {
                    onMessage.accept("Chua ket noi P2P");
                    return;
                }
                // LẬP TRÌNH MẠNG:
                // writeUTF gửi MESSAGE trực tiếp Peer → Peer qua TCP.
                // flush() đẩy dữ liệu trong buffer xuống mạng.
                p2pOut.writeUTF("MESSAGE|" + username + "|" + content);
                p2pOut.flush();
                onMessage.accept("[Me]: " + content);
            } catch (IOException e) {
                onMessage.accept("Gui tin loi: " + e.getMessage());
            }
        }, "send-msg").start();
    }

    public void sendFile(File file) {
        new Thread(() -> {
            try {
                if (p2pOut == null) {
                    onMessage.accept("Chua ket noi P2P");
                    return;
                }
                p2pOut.writeUTF("FILE_REQUEST|" + username + "|" + file.getName() + "|" + file.length());
                p2pOut.flush();
                waitForFileAccept(file.getName());
                if (!fileAccepted) {
                    onMessage.accept("Khong nhan duoc FILE_ACCEPT");
                    return;
                }
                streamFileBytes(file);
                onMessage.accept("Da gui file: " + file.getName());
            } catch (IOException e) {
                onMessage.accept("Gui file loi: " + e.getMessage());
            }
        }, "send-file").start();
    }

    private void waitForFileAccept(String filename) {
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
            pendingAcceptFile = null;
        }
    }

    private void notifyFileAccept(String filename) {
        synchronized (fileLock) {
            if (filename.equals(pendingAcceptFile)) {
                fileAccepted = true;
                fileLock.notifyAll();
            }
        }
    }

    private void streamFileBytes(File file) throws IOException {
        // LẬP TRÌNH MẠNG:
        // Không dùng Files.readAllBytes() — file lớn sẽ đầy RAM.
        // Đọc/ghi từng buffer 8 KB qua TCP.
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = fis.read(buffer)) != -1) {
                // LẬP TRÌNH MẠNG:
                // write() gửi n byte thô qua TCP tới Peer nhận.
                p2pOut.write(buffer, 0, n);
            }
            p2pOut.flush();
        }
    }

    private void receiveFile(String filename, long size) throws IOException {
        File outFile = new File("received_" + filename);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = size;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                // LẬP TRÌNH MẠNG:
                // read() đọc byte từ TCP — blocking cho tới khi có dữ liệu hoặc đóng kết nối.
                int n = p2pIn.read(buffer, 0, toRead);
                if (n == -1) {
                    throw new IOException("Ket noi dong som");
                }
                fos.write(buffer, 0, n);
                remaining -= n;
            }
        }
        onFileReceived.accept(outFile);
    }

    public void shutdown() {
        running = false;
        try {
            if (discoveryOut != null && username != null) {
                discoveryOut.println("LOGOUT|" + username);
            }
            if (p2pSocket != null) {
                p2pSocket.close();
            }
            if (peerServer != null) {
                peerServer.close();
            }
            if (discoverySocket != null) {
                discoverySocket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
