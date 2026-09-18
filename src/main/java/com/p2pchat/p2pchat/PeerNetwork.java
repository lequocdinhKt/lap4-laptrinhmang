package com.p2pchat.p2pchat;

import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Chat client (Client-Server): ket noi server trung tam, gui/nhan tin va file qua relay.
 */
public class PeerNetwork {

    private static final int BUFFER = 8 * 1024;
    private static final int SERVER_PORT = 2005;

    private String username;
    private volatile String currentTarget;
    private volatile boolean running = true;
    private volatile boolean inFileTransfer = false;

    private Socket serverSocket;
    private DataOutputStream serverOut;
    private DataInputStream serverIn;

    private final Object ioLock = new Object();
    private final Object responseLock = new Object();
    private final Object fileLock = new Object();
    private volatile String pendingResponse;
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

    /** Ket noi server :2005 -> REGISTER -> LIST. */
    public void connectServer(String serverIp, String username) {
        this.username = username;
        new Thread(() -> {
            try {
                serverSocket = new Socket(serverIp, SERVER_PORT);
                serverOut = new DataOutputStream(new BufferedOutputStream(serverSocket.getOutputStream()));
                serverIn = new DataInputStream(new BufferedInputStream(serverSocket.getInputStream()));

                startServerReader();

                String resp = sendCommandExpectResponse("REGISTER|" + username);
                onMessage.accept("REGISTER -> " + resp);

                if (resp == null || !resp.startsWith("OK|REGISTERED")) return;

                refreshPeerList();
            } catch (IOException | InterruptedException e) {
                onMessage.accept("Loi ket noi server: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "connect-server").start();
    }

    private void startServerReader() {
        Thread t = new Thread(() -> {
            try {
                while (running && serverSocket != null && !serverSocket.isClosed()) {
                    String header;
                    synchronized (ioLock) {
                        while (inFileTransfer && running) ioLock.wait(200);
                        if (!running) break;
                        header = serverIn.readUTF();
                    }
                    dispatchServerMessage(header);
                }
            } catch (IOException | InterruptedException e) {
                if (running) onMessage.accept("Mat ket noi server: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "server-reader");
        t.setDaemon(true);
        t.start();
    }

    private void dispatchServerMessage(String header) throws IOException, InterruptedException {
        if (isCommandResponse(header)) {
            synchronized (responseLock) {
                pendingResponse = header;
                responseLock.notifyAll();
            }
            return;
        }

        String[] p = header.split("\\|", -1);
        switch (p[0]) {
            case "MESSAGE" -> onMessage.accept("[" + p[1] + "]: " + p[2]);
            case "FILE_REQUEST" -> {
                onMessage.accept(p[1] + " gui file: " + p[2] + " (" + p[3] + " B)");
                synchronized (ioLock) {
                    serverOut.writeUTF("FILE_ACCEPT|" + p[2]);
                    serverOut.flush();
                    inFileTransfer = true;
                    try {
                        receiveFile(p[2], Long.parseLong(p[3]));
                    } finally {
                        inFileTransfer = false;
                        ioLock.notifyAll();
                    }
                }
            }
            case "FILE_ACCEPT" -> {
                synchronized (fileLock) {
                    fileAccepted = true;
                    fileLock.notifyAll();
                }
            }
            default -> onMessage.accept("Server: " + header);
        }
    }

    private static boolean isCommandResponse(String header) {
        return header.startsWith("OK|") || header.startsWith("LIST") || header.startsWith("ERROR");
    }

    public void refreshPeerList() {
        new Thread(() -> {
            try {
                String resp = sendCommandExpectResponse("LIST");
                List<String> names = new ArrayList<>();
                if (resp != null && resp.startsWith("LIST") && !resp.equals("LIST|EMPTY")) {
                    for (String entry : resp.split("\\|")) {
                        if ("LIST".equals(entry) || "EMPTY".equals(entry)) continue;
                        String name = entry.contains("@")
                                ? entry.substring(0, entry.indexOf('@'))
                                : entry;
                        if (!name.equals(username)) names.add(name);
                    }
                }
                onPeerList.accept(names);
            } catch (IOException | InterruptedException e) {
                onMessage.accept("LIST loi: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "list").start();
    }

    /** Chon nguoi chat (client-server: khong can TCP truc tiep). */
    public void connectPeer(String target) {
        currentTarget = target;
        new Thread(() -> {
            try {
                String resp = sendCommandExpectResponse("CONNECT|" + target);
                onMessage.accept("Da chon chat voi " + target + " (" + resp + ")");
            } catch (IOException | InterruptedException e) {
                onMessage.accept("Connect loi: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "connect-peer").start();
    }

    public void sendMessage(String content) {
        new Thread(() -> {
            try {
                if (currentTarget == null) {
                    onMessage.accept("Chua chon nguoi chat");
                    return;
                }
                sendCommandFireAndForget("MESSAGE|" + currentTarget + "|" + content);
                onMessage.accept("[Me]: " + content);
            } catch (IOException e) {
                onMessage.accept("Gui tin loi: " + e.getMessage());
            }
        }, "send-msg").start();
    }

    public void sendFile(File file) {
        new Thread(() -> {
            try {
                if (currentTarget == null) {
                    onMessage.accept("Chua chon nguoi chat");
                    return;
                }

                fileAccepted = false;
                sendCommandFireAndForget("FILE_REQUEST|" + currentTarget + "|" + file.getName() + "|" + file.length());

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

                synchronized (ioLock) {
                    inFileTransfer = true;
                    try (FileInputStream fis = new FileInputStream(file)) {
                        byte[] buf = new byte[BUFFER];
                        int n;
                        while ((n = fis.read(buf)) != -1) serverOut.write(buf, 0, n);
                        serverOut.flush();
                    } finally {
                        inFileTransfer = false;
                        ioLock.notifyAll();
                    }
                }
                onMessage.accept("Da gui file: " + file.getName());
            } catch (IOException | InterruptedException e) {
                onMessage.accept("Gui file loi: " + e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }, "send-file").start();
    }

    private String sendCommandExpectResponse(String command) throws IOException, InterruptedException {
        synchronized (responseLock) {
            pendingResponse = null;
            sendCommandFireAndForget(command);

            long end = System.currentTimeMillis() + 10_000;
            while (pendingResponse == null && System.currentTimeMillis() < end) {
                responseLock.wait(500);
            }
            if (pendingResponse == null) throw new IOException("Timeout cho phan hoi server");
            return pendingResponse;
        }
    }

    private void sendCommandFireAndForget(String command) throws IOException {
        synchronized (ioLock) {
            if (serverOut == null) throw new IOException("Chua ket noi server");
            while (inFileTransfer) {
                try {
                    ioLock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Bi gian doan khi gui lenh");
                }
            }
            serverOut.writeUTF(command);
            serverOut.flush();
        }
    }

    private void receiveFile(String filename, long size) throws IOException {
        File out = new File("received_" + filename);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[BUFFER];
            long left = size;
            while (left > 0) {
                int n = serverIn.read(buf, 0, (int) Math.min(buf.length, left));
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
            if (serverOut != null && username != null) {
                sendCommandFireAndForget("LOGOUT|" + username);
            }
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }
}
