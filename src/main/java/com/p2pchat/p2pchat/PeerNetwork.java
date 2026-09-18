package com.p2pchat.p2pchat;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Peer client: Discovery (đăng ký/tìm) + P2P chat + P2P file.
 * Hai chức năng chính: gửi/nhận tin (MESSAGE) và gửi/nhận file (FILE_*).
 */
public class PeerNetwork {

    private static final int BUFFER = 8 * 1024;
    private static final int DISCOVERY_PORT = 2005;

    private String username;
    private int peerPort;
    private volatile boolean running = true;

    private Socket discoverySocket;
    private PrintWriter discoveryOut;
    private BufferedReader discoveryIn;

    private Socket p2pSocket;
    private DataOutputStream p2pOut;
    private DataInputStream p2pIn;
    private ServerSocket peerServer;

    // Đồng bộ FILE_REQUEST ↔ FILE_ACCEPT giữa thread gửi và p2p-reader
    private final Object fileLock = new Object();
    private volatile boolean fileAccepted;

    private Consumer<String> onMessage = s -> {};
    private Consumer<List<String>> onPeerList = list -> {};
    private Consumer<File> onFileReceived = f -> {};

    public void setOnMessage(Consumer<String> h) {
        onMessage = h != null ? h : s -> {};
    }

    public void setOnPeerList(Consumer<List<String>> h) {
        onPeerList = h != null ? h : list -> {};
    }

    public void setOnFileReceived(Consumer<File> h) {
        onFileReceived = h != null ? h : f -> {};
    }

    /** Kết nối Discovery :2005 → REGISTER → mở ServerSocket P2P → LIST. */
    public void connectDiscovery(String serverIp, String username, int peerPort) {
        this.username = username;
        this.peerPort = peerPort;
        new Thread(() -> {
            try {
                discoverySocket = new Socket(serverIp, DISCOVERY_PORT);
                discoveryOut = new PrintWriter(discoverySocket.getOutputStream(), true);
                discoveryIn = new BufferedReader(new InputStreamReader(discoverySocket.getInputStream()));

                discoveryOut.println("REGISTER|" + username + "|" + peerPort);
                onMessage.accept("REGISTER -> " + discoveryIn.readLine());

                startPeerServer();
                refreshPeerList();
            } catch (IOException e) {
                onMessage.accept("Loi Discovery: " + e.getMessage());
            }
        }, "discovery").start();
    }

    /** Lắng nghe peer khác connect tới (P2P). */
    private void startPeerServer() {
        Thread t = new Thread(() -> {
            try {
                peerServer = new ServerSocket(peerPort);
                onMessage.accept("Lang nghe P2P port " + peerPort);
                while (running) {
                    setupP2p(peerServer.accept());
                    startP2pReader();
                }
            } catch (IOException e) {
                if (running) onMessage.accept("Loi ServerSocket: " + e.getMessage());
            }
        }, "p2p-accept");
        t.setDaemon(true);
        t.start();
    }

    public void refreshPeerList() {
        new Thread(() -> {
            try {
                String resp;
                synchronized (this) {
                    if (discoveryOut == null) return;
                    discoveryOut.println("LIST");
                    resp = discoveryIn.readLine();
                }
                List<String> names = new ArrayList<>();
                if (resp != null && resp.startsWith("LIST") && !resp.equals("LIST|EMPTY")) {
                    for (String entry : resp.split("\\|")) {
                        if (entry.contains("@")) {
                            String name = entry.substring(0, entry.indexOf('@'));
                            if (!name.equals(username)) names.add(name);
                        }
                    }
                }
                onPeerList.accept(names);
            } catch (IOException e) {
                onMessage.accept("LIST loi: " + e.getMessage());
            }
        }, "list").start();
    }

    /** FIND qua Discovery rồi TCP trực tiếp tới peer. */
    public void connectPeer(String target) {
        new Thread(() -> {
            try {
                String resp;
                synchronized (this) {
                    discoveryOut.println("FIND|" + target);
                    resp = discoveryIn.readLine();
                }
                if (resp == null || !resp.startsWith("PEER|")) {
                    onMessage.accept("FIND that bai: " + resp);
                    return;
                }
                String[] p = resp.split("\\|"); 
                // Nếu resp = "PEER|alice|192.168.1.10|5000" thì
                // p[0]="PEER",
                // p[1]="alice",
                // p[2]="192.168.1.10",
                // p[3]="5000".
                setupP2p(new Socket(p[2], Integer.parseInt(p[3])));
                startP2pReader();
                onMessage.accept("Da ket noi P2P toi " + target);
            } catch (IOException e) {
                onMessage.accept("Connect Peer loi: " + e.getMessage());
            }
        }, "connect-peer").start();
    }

    private synchronized void setupP2p(Socket socket) throws IOException {
        // Nếu người dùng kết nối nhiều peer liên tiếp, mỗi lần không đóng socket cũ sẽ làm 
        // chương trình giữ nhiều kết nối mạng còn mở, dẫn đến ứng dụng chậm hoặc treo máy.
        if (p2pSocket != null && !p2pSocket.isClosed()) {
            try { p2pSocket.close(); } catch (IOException ignored) {}
        }
        p2pSocket = socket;
        // Mỗi kết nối P2P có 2 luồng: gửi (p2pOut) và nhận (p2pIn).
        // Để đảm bảo độ tin cậy và tốc độ, sử dụng BufferedOutputStream/InputStream.
        // Đọc/ghi theo khối (buffer) để tránh việc thực hiện hàng trăm/nghìn lần read/write byte.
        p2pOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        p2pIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    }

    private void startP2pReader() {
        Thread t = new Thread(() -> {
            try {
                while (running && p2pSocket != null && !p2pSocket.isClosed()) {
                    handleP2p(p2pIn.readUTF());
                }
            } catch (IOException e) {
                if (running) onMessage.accept("P2P ngat: " + e.getMessage());
            }
        }, "p2p-reader");
        t.setDaemon(true);
        t.start();
    }
//"MESSAGE|username|noidung", 
// "FILE_REQUEST|username|filename|size".
    private void handleP2p(String header) throws IOException {
        String[] p = header.split("\\|", -1);
        switch (p[0]) {
            case "MESSAGE" -> onMessage.accept("[" + p[1] + "]: " + p[2]);
            case "FILE_REQUEST" -> {
                // Tự ACCEPT rồi đọc đúng size byte
                onMessage.accept(p[1] + " gui file: " + p[2] + " (" + p[3] + " B)");
                p2pOut.writeUTF("FILE_ACCEPT|" + p[2]);
                p2pOut.flush();
                receiveFile(p[2], Long.parseLong(p[3]));
            }
            case "FILE_ACCEPT" -> {
                synchronized (fileLock) {
                    fileAccepted = true;
                    fileLock.notifyAll();
                }
            }
            default -> onMessage.accept("P2P: " + header);
        }
    }

    // ===== Chức năng 1: gửi / nhận tin nhắn =====

    public void sendMessage(String content) {
        new Thread(() -> {
            try {
                if (p2pOut == null) { onMessage.accept("Chua ket noi P2P"); return; }
                p2pOut.writeUTF("MESSAGE|" + username + "|" + content);
                p2pOut.flush();
                onMessage.accept("[Me]: " + content);
            } catch (IOException e) {
                onMessage.accept("Gui tin loi: " + e.getMessage());
            }
        }, "send-msg").start();
    }

    // ===== Chức năng 2: gửi / nhận file =====

    public void sendFile(File file) {
        new Thread(() -> {
            try {
                if (p2pOut == null) { onMessage.accept("Chua ket noi P2P"); return; }

                fileAccepted = false;
                p2pOut.writeUTF("FILE_REQUEST|" + username + "|" + file.getName() + "|" + file.length());
                p2pOut.flush();

                // Chờ FILE_ACCEPT (tối đa 30s)
                synchronized (fileLock) {
                    long end = System.currentTimeMillis() + 30_000;
                    while (!fileAccepted && System.currentTimeMillis() < end) {
                        fileLock.wait(1000);
                    }
                }
                if (!fileAccepted) {
                    onMessage.accept("Khong nhan duoc FILE_ACCEPT");
                    return;
                }

                // Stream byte thô từng 8KB
                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[BUFFER];
                    int n;
                    while ((n = fis.read(buf)) != -1) p2pOut.write(buf, 0, n);
                    p2pOut.flush();
                }
                onMessage.accept("Da gui file: " + file.getName());
            } catch (IOException | InterruptedException e) {
                onMessage.accept("Gui file loi: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "send-file").start();
    }

    private void receiveFile(String filename, long size) throws IOException {
        File out = new File("received_" + filename);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[BUFFER];
            long left = size;
            while (left > 0) {
                int n = p2pIn.read(buf, 0, (int) Math.min(buf.length, left));
                if (n == -1) throw new IOException("Ket noi dong som");
                fos.write(buf, 0, n);
                left -= n;
            }
        }
        onFileReceived.accept(out);
    }

    public void shutdown() {
        running = false;
        try {
            if (discoveryOut != null && username != null) discoveryOut.println("LOGOUT|" + username);
            if (p2pSocket != null) p2pSocket.close();
            if (peerServer != null) peerServer.close();
            if (discoverySocket != null) discoverySocket.close();
        } catch (IOException ignored) {}
    }
}
