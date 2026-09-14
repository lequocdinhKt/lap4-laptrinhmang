package com.p2pchat.p2pchat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovery Server trung tâm (Hybrid P2P).
 * Chỉ đăng ký / LIST / FIND / LOGOUT — KHÔNG relay tin nhắn hay file.
 */
public class DiscoveryServer {

    private static final int PORT = 2005;

    // username -> "ip:peerPort"
    private static final Map<String, String> peers = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        // LẬP TRÌNH MẠNG:
        // ServerSocket mở TCP port 2005 trên máy này.
        // Peer khác kết nối tới IPv4 của máy Server tại port 2005 để đăng ký / tìm peer.
        // Discovery Server chỉ giúp tìm địa chỉ; sau đó chat/file đi trực tiếp Peer ↔ Peer.
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Discovery Server dang lang nghe port " + PORT);

            while (true) {
                // LẬP TRÌNH MẠNG:
                // accept() chờ một Peer kết nối tới port 2005.
                // Đây là blocking call: thread đứng tại đây cho tới khi có kết nối TCP đến.
                Socket clientSocket = serverSocket.accept();
                System.out.println("Peer ket noi: " + clientSocket.getInetAddress().getHostAddress());

                // LẬP TRÌNH MẠNG:
                // Không xử lý accept()/read() trên cùng một vòng nếu muốn nhận nhiều Peer.
                // Mỗi kết nối chạy trên thread riêng để Server vẫn tiếp tục accept() Peer mới.
                Thread clientThread = new Thread(() -> handleClient(clientSocket));
                clientThread.start();
            }
        } catch (IOException e) {
            System.err.println("Loi Server: " + e.getMessage());
        }
    }

    private static void handleClient(Socket socket) {
        // LẬP TRÌNH MẠNG:
        // InputStream đọc dữ liệu Peer gửi tới Server.
        // OutputStream ghi phản hồi từ Server về Peer.
        // BufferedReader/PrintWriter làm việc theo dòng text (protocol dạng REGISTER|...).
        try (
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter out = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            // LẬP TRÌNH MẠNG:
            // readLine() chặn thread cho tới khi nhận đủ một dòng (kết thúc bằng \n).
            // Nếu chạy trên UI thread sẽ làm giao diện đơ — ở đây chạy trên thread riêng nên an toàn.
            while ((line = in.readLine()) != null) {
                System.out.println("Nhan: " + line);
                String response = processCommand(line, socket);
                // LẬP TRÌNH MẠNG:
                // println ghi dòng phản hồi về Peer; autoFlush=true nên không cần flush thủ công.
                out.println(response);
            }
        } catch (IOException e) {
            System.err.println("Mat ket noi Peer: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String processCommand(String line, Socket socket) {
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0) {
            return "ERROR|Empty command";
        }

        String cmd = parts[0].trim().toUpperCase();

        switch (cmd) {
            case "REGISTER" -> {
                // REGISTER|username|peerPort
                if (parts.length < 3) {
                    return "ERROR|REGISTER can username|peerPort";
                }
                String username = parts[1].trim();
                String peerPort = parts[2].trim();

                // LẬP TRÌNH MẠNG:
                // IPv4 lấy từ socket kết nối thực tế, không tin Peer tự gửi IP.
                // getInetAddress() là địa chỉ máy Peer nhìn từ phía Server trên LAN.
                String ip = socket.getInetAddress().getHostAddress();
                peers.put(username, ip + ":" + peerPort);
                System.out.println("Dang ky: " + username + " -> " + ip + ":" + peerPort);
                return "OK|REGISTERED|" + username;
            }
            case "LIST" -> {
                if (peers.isEmpty()) {
                    return "LIST|EMPTY";
                }
                StringBuilder sb = new StringBuilder("LIST");
                for (Map.Entry<String, String> e : peers.entrySet()) {
                    sb.append("|").append(e.getKey()).append("@").append(e.getValue());
                }
                return sb.toString();
            }
            case "FIND" -> {
                // FIND|username
                if (parts.length < 2) {
                    return "ERROR|FIND can username";
                }
                String target = parts[1].trim();
                String info = peers.get(target);
                if (info == null) {
                    return "ERROR|NOT_FOUND|" + target;
                }
                String[] ipPort = info.split(":", 2);
                return "PEER|" + target + "|" + ipPort[0] + "|" + ipPort[1];
            }
            case "LOGOUT" -> {
                // LOGOUT|username
                if (parts.length < 2) {
                    return "ERROR|LOGOUT can username";
                }
                String username = parts[1].trim();
                peers.remove(username);
                System.out.println("Logout: " + username);
                return "OK|LOGOUT|" + username;
            }
            default -> {
                return "ERROR|Unknown command|" + cmd;
            }
        }
    }
}
