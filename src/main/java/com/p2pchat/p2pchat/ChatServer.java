package com.p2pchat.p2pchat;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;

/**
 * Chat Server trung tam (Client-Server).
 * Quan ly dang ky / LIST / relay tin nhan va file giua cac client.
 */
public class ChatServer {

    private static final int PORT = 2005;
    private static final int BUFFER = 8 * 1024;

    private static final Map<String, ClientSession> clients = new ConcurrentHashMap<>();
    private static final Map<String, SynchronousQueue<Void>> pendingFileAccepts = new ConcurrentHashMap<>();

    public static void main(String[] args) {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Chat Server dang lang nghe port " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client ket noi: " + clientSocket.getInetAddress().getHostAddress());
                Thread clientThread = new Thread(() -> handleClient(clientSocket));
                clientThread.start();
            }
        } catch (IOException e) {
            System.err.println("Loi Server: " + e.getMessage());
        }
    }

    private static void handleClient(Socket socket) {
        ClientSession session;
        try {
            session = new ClientSession(socket);
        } catch (IOException e) {
            System.err.println("Loi khoi tao client: " + e.getMessage());
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            String header;
            while ((header = session.in.readUTF()) != null) {
                System.out.println("Nhan tu " + session.username + ": " + header);
                if (!processCommand(session, header)) break;
            }
        } catch (IOException e) {
            System.err.println("Mat ket noi client: " + e.getMessage());
        } finally {
            if (session.username != null) {
                clients.remove(session.username);
                System.out.println("Offline: " + session.username);
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static boolean processCommand(ClientSession session, String header) throws IOException {
        String[] parts = header.split("\\|", -1);
        if (parts.length == 0) return true;

        switch (parts[0]) {
            case "REGISTER" -> {
                if (parts.length < 2) {
                    session.out.writeUTF("ERROR|REGISTER can username");
                    session.out.flush();
                    return true;
                }
                String username = parts[1].trim();
                if (clients.containsKey(username)) {
                    session.out.writeUTF("ERROR|USERNAME_TAKEN|" + username);
                    session.out.flush();
                    return false;
                }
                session.username = username;
                clients.put(username, session);
                System.out.println("Dang ky: " + username);
                session.out.writeUTF("OK|REGISTERED|" + username);
                session.out.flush();
            }
            case "LIST" -> {
                StringBuilder sb = new StringBuilder("LIST");
                for (String user : clients.keySet()) {
                    sb.append("|").append(user);
                }
                session.out.writeUTF(sb.length() == 4 ? "LIST|EMPTY" : sb.toString());
                session.out.flush();
            }
            case "CONNECT" -> {
                if (parts.length < 2) {
                    session.out.writeUTF("ERROR|CONNECT can target");
                } else {
                    session.out.writeUTF("OK|CONNECTED|" + parts[1].trim());
                }
                session.out.flush();
            }
            case "MESSAGE" -> {
                if (parts.length < 3 || session.username == null) {
                    session.out.writeUTF("ERROR|MESSAGE can target|content");
                    session.out.flush();
                    return true;
                }
                String target = parts[1].trim();
                String content = parts[2];
                ClientSession targetSession = clients.get(target);
                if (targetSession == null) {
                    session.out.writeUTF("ERROR|NOT_ONLINE|" + target);
                    session.out.flush();
                } else {
                    targetSession.out.writeUTF("MESSAGE|" + session.username + "|" + content);
                    targetSession.out.flush();
                }
            }
            case "FILE_REQUEST" -> {
                if (parts.length < 4 || session.username == null) {
                    session.out.writeUTF("ERROR|FILE_REQUEST can target|filename|size");
                    session.out.flush();
                    return true;
                }
                relayFileRequest(session, parts[1].trim(), parts[2], Long.parseLong(parts[3]));
            }
            case "FILE_ACCEPT" -> {
                if (parts.length < 2) return true;
                String key = session.username + "|" + parts[1];
                SynchronousQueue<Void> queue = pendingFileAccepts.get(key);
                if (queue != null) queue.offer(null);
            }
            case "LOGOUT" -> {
                if (session.username != null) {
                    clients.remove(session.username);
                    System.out.println("Logout: " + session.username);
                    session.out.writeUTF("OK|LOGOUT|" + session.username);
                    session.out.flush();
                }
                return false;
            }
            default -> {
                session.out.writeUTF("ERROR|Unknown command|" + parts[0]);
                session.out.flush();
            }
        }
        return true;
    }

    private static void relayFileRequest(ClientSession sender, String target, String filename, long size)
            throws IOException {
        ClientSession receiver = clients.get(target);
        if (receiver == null) {
            sender.out.writeUTF("ERROR|NOT_ONLINE|" + target);
            sender.out.flush();
            return;
        }

        String acceptKey = target + "|" + filename;
        SynchronousQueue<Void> acceptQueue = new SynchronousQueue<>();
        pendingFileAccepts.put(acceptKey, acceptQueue);

        try {
            receiver.out.writeUTF("FILE_REQUEST|" + sender.username + "|" + filename + "|" + size);
            receiver.out.flush();

            acceptQueue.take();

            sender.out.writeUTF("FILE_ACCEPT|" + filename);
            sender.out.flush();

            relayBytes(sender.in, receiver.out, size);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sender.out.writeUTF("ERROR|FILE_TRANSFER_INTERRUPTED");
            sender.out.flush();
        } finally {
            pendingFileAccepts.remove(acceptKey);
        }
    }

    private static void relayBytes(DataInputStream in, DataOutputStream out, long size) throws IOException {
        byte[] buf = new byte[BUFFER];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n == -1) throw new IOException("Ket noi dong som");
            out.write(buf, 0, n);
            left -= n;
        }
        out.flush();
    }

    private static final class ClientSession {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        String username;

        ClientSession(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }
    }
}
