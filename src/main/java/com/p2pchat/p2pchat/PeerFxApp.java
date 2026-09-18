package com.p2pchat.p2pchat;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

/**
 * UI JavaFX — mạng nằm trong PeerNetwork (chat + file).
 */
public class PeerFxApp extends Application {

    private final PeerNetwork network = new PeerNetwork();
    private TextArea chatArea;
    private ListView<String> peerList;
    private Label fileLabel;

    @Override
    public void start(Stage stage) {
        TextField serverIp = new TextField("127.0.0.1");
        TextField username = new TextField();
        TextField peerPort = new TextField("6001");
        TextField messageField = new TextField();
        chatArea = new TextArea();
        chatArea.setEditable(false);
        peerList = new ListView<>();
        fileLabel = new Label("File: (chua nhan)");

        // Thread mạng → cập nhật UI phải qua Platform.runLater
        network.setOnMessage(msg -> Platform.runLater(() -> chatArea.appendText(msg + "\n")));
        network.setOnPeerList(list -> Platform.runLater(() -> peerList.getItems().setAll(list)));
        network.setOnFileReceived(f -> Platform.runLater(() -> {
            chatArea.appendText("Nhan file: " + f.getAbsolutePath() + "\n");
            fileLabel.setText("File: " + f.getAbsolutePath());
        }));

        Button connectBtn = new Button("Connect");
        connectBtn.setOnAction(e -> {
            try {
                int port = Integer.parseInt(peerPort.getText().trim());
                String user = username.getText().trim();
                if (user.isEmpty()) { chatArea.appendText("Nhap username\n"); return; }
                network.connectDiscovery(serverIp.getText().trim(), user, port);
            } catch (NumberFormatException ex) {
                chatArea.appendText("Peer port khong hop le\n");
            }
        });

        Button refreshBtn = new Button("Refresh LIST");
        refreshBtn.setOnAction(e -> network.refreshPeerList());

        Button connectPeerBtn = new Button("Connect Peer");
        connectPeerBtn.setOnAction(e -> {
            String target = peerList.getSelectionModel().getSelectedItem();
            if (target == null) chatArea.appendText("Chon mot peer\n");
            else network.connectPeer(target);
        });

        Button sendBtn = new Button("Send");
        sendBtn.setOnAction(e -> {
            String text = messageField.getText();
            if (!text.isEmpty()) {
                messageField.clear();
                network.sendMessage(text);
            }
        });

        Button sendFileBtn = new Button("Send File");
        sendFileBtn.setOnAction(e -> {
            var file = new FileChooser().showOpenDialog(stage);
            if (file != null) network.sendFile(file);
        });

        HBox top = new HBox(8,
                new Label("Server IP"), serverIp,
                new Label("User"), username,
                new Label("Port"), peerPort, connectBtn);
        top.setPadding(new Insets(8));

        VBox left = new VBox(8, new Label("Peers"), peerList, refreshBtn, connectPeerBtn);
        left.setPadding(new Insets(8));
        left.setPrefWidth(200);

        HBox bottom = new HBox(8, messageField, sendBtn, sendFileBtn);
        messageField.setPrefWidth(400);
        bottom.setPadding(new Insets(8));

        VBox center = new VBox(8, chatArea, fileLabel, bottom);
        center.setPadding(new Insets(8));
        VBox.setVgrow(chatArea, Priority.ALWAYS);

        BorderPane root = new BorderPane(center, top, null, null, left);
        stage.setTitle("P2P Chat");
        stage.setScene(new Scene(root, 800, 500));
        stage.setOnCloseRequest(e -> network.shutdown());
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
