package com.p2pchat.p2pchat;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

/**
 * JavaFX UI only — mọi Socket/ServerSocket/stream nằm trong PeerNetwork.
 */
public class PeerFxApp extends Application {

    private TextField serverIpField;
    private TextField usernameField;
    private TextField peerPortField;
    private TextArea chatArea;
    private TextField messageField;
    private ListView<String> peerList;
    private Label statusLabel;
    private Label fileLabel;

    private final PeerNetwork network = new PeerNetwork();

    @Override
    public void start(Stage stage) {
        serverIpField = new TextField("127.0.0.1");
        usernameField = new TextField();
        peerPortField = new TextField("6001");
        messageField = new TextField();
        chatArea = new TextArea();
        chatArea.setEditable(false);
        peerList = new ListView<>();
        statusLabel = new Label("Chua ket noi Discovery Server");
        fileLabel = new Label("File: (chua nhan)");

        // Callback từ network thread → cập nhật UI trên JavaFX Application Thread.
        // Platform.runLater: không được sửa control JavaFX từ thread mạng.
        network.setOnMessage(msg -> Platform.runLater(() -> appendChat(msg)));
        network.setOnStatus(s -> Platform.runLater(() -> statusLabel.setText(s)));
        network.setOnPeerList(list -> Platform.runLater(() -> peerList.getItems().setAll(list)));
        network.setOnFileReceived(f -> Platform.runLater(() -> {
            appendChat("Nhan file: " + f.getAbsolutePath());
            fileLabel.setText("File: " + f.getAbsolutePath());
        }));

        Button connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> connectDiscovery());

        Button refreshBtn = new Button("Refresh LIST");
        refreshBtn.setOnAction(e -> network.refreshPeerList());

        Button connectPeerBtn = new Button("Connect Peer");
        connectPeerBtn.setOnAction(e -> connectSelectedPeer());

        Button sendBtn = new Button("Send");
        sendBtn.setOnAction(e -> sendChat());

        Button sendFileBtn = new Button("Send File");
        sendFileBtn.setOnAction(e -> chooseAndSendFile(stage));

        HBox top = new HBox(8,
                new Label("Server IP"), serverIpField,
                new Label("User"), usernameField,
                new Label("Peer Port"), peerPortField,
                connectBtn);
        top.setPadding(new Insets(8));

        VBox left = new VBox(8, new Label("Peers online"), peerList, refreshBtn, connectPeerBtn);
        left.setPadding(new Insets(8));
        left.setPrefWidth(220);

        HBox bottom = new HBox(8, messageField, sendBtn, sendFileBtn);
        messageField.setPrefWidth(400);
        bottom.setPadding(new Insets(8));

        VBox center = new VBox(8, chatArea, fileLabel, bottom);
        center.setPadding(new Insets(8));
        VBox.setVgrow(chatArea, javafx.scene.layout.Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(new VBox(top, statusLabel));
        root.setLeft(left);
        root.setCenter(center);

        stage.setTitle("P2P Chat");
        stage.setScene(new Scene(root, 800, 500));
        stage.setOnCloseRequest(e -> network.shutdown());
        stage.show();
    }

    private void connectDiscovery() {
        String username = usernameField.getText().trim();
        String ip = serverIpField.getText().trim();
        int peerPort;
        try {
            peerPort = Integer.parseInt(peerPortField.getText().trim());
        } catch (NumberFormatException ex) {
            appendChat("Peer port khong hop le");
            return;
        }
        if (username.isEmpty()) {
            appendChat("Nhap username");
            return;
        }
        network.connectDiscovery(ip, username, peerPort);
    }

    private void connectSelectedPeer() {
        String target = peerList.getSelectionModel().getSelectedItem();
        if (target == null) {
            appendChat("Chon mot peer trong danh sach");
            return;
        }
        network.connectPeer(target);
    }

    private void sendChat() {
        String content = messageField.getText();
        if (content.isEmpty()) {
            return;
        }
        messageField.clear();
        network.sendMessage(content);
    }

    private void chooseAndSendFile(Stage stage) {
        FileChooser chooser = new FileChooser();
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        network.sendFile(file);
    }

    private void appendChat(String line) {
        chatArea.appendText(line + "\n");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
