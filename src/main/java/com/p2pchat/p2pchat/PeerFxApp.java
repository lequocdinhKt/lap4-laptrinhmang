package com.p2pchat.p2pchat;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;

/**
 * JavaFX Client cho mo hinh Client-Server.
 *
 * Client:
 * - Ket noi Server port 2005
 * - REGISTER username
 * - Lay danh sach user online
 * - Chon user de chat
 * - Gui tin nhan qua Server
 * - Gui file qua Server
 */
public class PeerFxApp extends Application {

    private static final int SERVER_PORT = 2005;

    private final PeerNetwork network = new PeerNetwork();

    private TextArea chatArea;
    private ListView<String> userList;

    private Label statusLabel;
    private Label chatTargetLabel;
    private Label fileLabel;

    private TextField serverIpField;
    private TextField usernameField;
    private TextField messageField;

    private Button connectBtn;
    private Button refreshBtn;
    private Button selectUserBtn;
    private Button sendBtn;
    private Button sendFileBtn;

    private Stage primaryStage;

    @Override
    public void start(Stage stage) {

        this.primaryStage = stage;

        // =========================================================
        // SERVER
        // =========================================================

        serverIpField = new TextField("127.0.0.1");
        serverIpField.setPromptText("IP Server");
        serverIpField.setPrefWidth(140);

        usernameField = new TextField();
        usernameField.setPromptText("Ten dang nhap");
        usernameField.setPrefWidth(130);

        Label portLabel = new Label("Port: " + SERVER_PORT);
        portLabel.setStyle("-fx-text-fill: gray;");

        connectBtn = new Button("Ket noi Server");

        // =========================================================
        // CHAT
        // =========================================================

        chatArea = new TextArea();
        chatArea.setEditable(false);
        chatArea.setWrapText(true);

        messageField = new TextField();
        messageField.setPromptText("Nhap tin nhan...");
        messageField.setDisable(true);

        sendBtn = new Button("Gui tin");
        sendBtn.setDisable(true);

        sendFileBtn = new Button("Gui file");
        sendFileBtn.setDisable(true);

        // =========================================================
        // USER LIST
        // =========================================================

        userList = new ListView<>();

        refreshBtn = new Button("Lam moi");
        refreshBtn.setDisable(true);

        selectUserBtn = new Button("Chon nguoi chat");
        selectUserBtn.setDisable(true);

        // =========================================================
        // STATUS
        // =========================================================

        statusLabel = new Label(
                "Trang thai: Chua ket noi Server"
        );

        chatTargetLabel = new Label(
                "Dang chat voi: (chua chon)"
        );

        fileLabel = new Label(
                "File nhan: (chua co)"
        );

        // =========================================================
        // CALLBACK TU PEER NETWORK
        // =========================================================

        network.setOnMessage(message ->
                Platform.runLater(() -> handleNetworkMessage(message))
        );

        network.setOnPeerList(list ->
                Platform.runLater(() -> {

                    userList.getItems().setAll(list);

                    if (list.isEmpty()) {
                        chatArea.appendText(
                                "[SYSTEM] Khong co user nao khac online.\n"
                        );
                    }
                })
        );

        network.setOnFileReceived(file ->
                Platform.runLater(() -> {

                    chatArea.appendText(
                            "[FILE] Da nhan: "
                                    + file.getAbsolutePath()
                                    + "\n"
                    );

                    fileLabel.setText(
                            "File nhan: " + file.getName()
                    );
                })
        );

        // =========================================================
        // CONNECT SERVER
        // =========================================================

        connectBtn.setOnAction(event -> connectServer());

        // =========================================================
        // REFRESH USER LIST
        // =========================================================

        refreshBtn.setOnAction(event -> {

            chatArea.appendText(
                    "[SYSTEM] Dang cap nhat danh sach user...\n"
            );

            network.refreshPeerList();
        });

        // =========================================================
        // SELECT USER
        // =========================================================

        selectUserBtn.setOnAction(event -> selectChatTarget());

        // Double click user
        userList.setOnMouseClicked(event -> {

            if (event.getClickCount() == 2) {
                selectChatTarget();
            }
        });

        // =========================================================
        // SEND MESSAGE
        // =========================================================

        sendBtn.setOnAction(event -> sendTextMessage());

        messageField.setOnAction(event -> sendTextMessage());

        // =========================================================
        // SEND FILE
        // =========================================================

        sendFileBtn.setOnAction(event -> selectAndSendFile());

        // =========================================================
        // TOP
        // =========================================================

        HBox topBar = new HBox(
                10,
                new Label("Server IP:"),
                serverIpField,
                portLabel,
                new Label("Username:"),
                usernameField,
                connectBtn
        );

        topBar.setPadding(new Insets(10));
        topBar.setAlignment(Pos.CENTER_LEFT);

        // =========================================================
        // LEFT
        // =========================================================

        Label usersTitle = new Label("Nguoi dung online");
        usersTitle.setStyle("-fx-font-weight: bold;");

        VBox leftPanel = new VBox(
                8,
                usersTitle,
                userList,
                refreshBtn,
                selectUserBtn
        );

        leftPanel.setPadding(new Insets(10));
        leftPanel.setPrefWidth(220);

        VBox.setVgrow(
                userList,
                Priority.ALWAYS
        );

        // =========================================================
        // INFO
        // =========================================================

        VBox infoBar = new VBox(
                5,
                statusLabel,
                chatTargetLabel,
                fileLabel
        );

        infoBar.setPadding(
                new Insets(0, 0, 5, 0)
        );

        // =========================================================
        // MESSAGE BAR
        // =========================================================

        HBox messageBar = new HBox(
                8,
                messageField,
                sendBtn,
                sendFileBtn
        );

        messageBar.setAlignment(
                Pos.CENTER_LEFT
        );

        // QUAN TRONG:
        // setHgrow phai goi tu HBox
        HBox.setHgrow(
                messageField,
                Priority.ALWAYS
        );

        // =========================================================
        // CENTER
        // =========================================================

        VBox centerPanel = new VBox(
                8,
                infoBar,
                chatArea,
                messageBar
        );

        centerPanel.setPadding(
                new Insets(10)
        );

        VBox.setVgrow(
                chatArea,
                Priority.ALWAYS
        );

        // =========================================================
        // ROOT
        // =========================================================

        BorderPane root = new BorderPane();

        root.setTop(topBar);
        root.setLeft(leftPanel);
        root.setCenter(centerPanel);

        root.setStyle(
                "-fx-font-size: 13px;"
        );

        // =========================================================
        // STAGE
        // =========================================================

        Scene scene = new Scene(
                root,
                900,
                550
        );

        stage.setTitle(
                "Client-Server Chat - Port " + SERVER_PORT
        );

        stage.setScene(scene);

        stage.setMinWidth(750);
        stage.setMinHeight(450);

        stage.setOnCloseRequest(event -> {

            network.shutdown();

            Platform.exit();
        });

        stage.show();
    }

    // =============================================================
    // CONNECT SERVER
    // =============================================================

    private void connectServer() {

        String serverIp = serverIpField
                .getText()
                .trim();

        String username = usernameField
                .getText()
                .trim();

        if (serverIp.isEmpty()) {

            appendSystem(
                    "Vui long nhap IP Server."
            );

            return;
        }

        if (username.isEmpty()) {

            appendSystem(
                    "Vui long nhap Username."
            );

            return;
        }

        statusLabel.setText(
                "Trang thai: Dang ket noi "
                        + serverIp
                        + ":"
                        + SERVER_PORT
                        + "..."
        );

        connectBtn.setDisable(true);
        serverIpField.setDisable(true);
        usernameField.setDisable(true);

        network.connectServer(
                serverIp,
                username
        );
    }

    // =============================================================
    // HANDLE NETWORK MESSAGE
    // =============================================================

    private void handleNetworkMessage(String msg) {

        chatArea.appendText(
                msg + "\n"
        );

        // ---------------------------------------------------------
        // REGISTER SUCCESS
        // ---------------------------------------------------------

        if (msg.startsWith(
                "REGISTER -> OK|REGISTERED"
        )) {

            statusLabel.setText(
                    "Trang thai: Da ket noi Server"
                            + " | Port "
                            + SERVER_PORT
            );

            refreshBtn.setDisable(false);
            selectUserBtn.setDisable(false);

            network.refreshPeerList();

            return;
        }

        // ---------------------------------------------------------
        // REGISTER ERROR
        // ---------------------------------------------------------

        if (msg.startsWith(
                "REGISTER -> ERROR"
        )) {

            statusLabel.setText(
                    "Trang thai: Dang nhap that bai"
            );

            resetConnectionUI();

            return;
        }

        // ---------------------------------------------------------
        // LOST CONNECTION
        // ---------------------------------------------------------

        if (msg.startsWith(
                "Mat ket noi server"
        )
                ||
                msg.startsWith(
                        "Loi ket noi server"
                )) {

            statusLabel.setText(
                    "Trang thai: Mat ket noi Server"
            );

            resetConnectionUI();

            return;
        }

        // ---------------------------------------------------------
        // CONNECT USER ERROR
        // ---------------------------------------------------------

        if (msg.startsWith(
                "Connect loi:"
        )) {

            chatTargetLabel.setText(
                    "Dang chat voi: (chua chon)"
            );

            disableChat();
        }
    }

    // =============================================================
    // SELECT CHAT TARGET
    // =============================================================

    private void selectChatTarget() {

        String target =
                userList
                        .getSelectionModel()
                        .getSelectedItem();

        if (target == null) {

            appendSystem(
                    "Hay chon mot nguoi dung."
            );

            return;
        }

        network.connectPeer(target);

        chatTargetLabel.setText(
                "Dang chat voi: " + target
        );

        sendBtn.setDisable(false);
        sendFileBtn.setDisable(false);
        messageField.setDisable(false);

        messageField.requestFocus();

        appendSystem(
                "Da chon "
                        + target
                        + " de chat."
        );
    }

    // =============================================================
    // SEND MESSAGE
    // =============================================================

    private void sendTextMessage() {

        if (messageField.isDisabled()) {
            return;
        }

        String text =
                messageField
                        .getText()
                        .trim();

        if (text.isEmpty()) {
            return;
        }

        messageField.clear();

        network.sendMessage(text);

        messageField.requestFocus();
    }

    // =============================================================
    // SEND FILE
    // =============================================================

    private void selectAndSendFile() {

        FileChooser chooser =
                new FileChooser();

        chooser.setTitle(
                "Chon file gui"
        );

        File file =
                chooser.showOpenDialog(
                        primaryStage
                );

        if (file == null) {
            return;
        }

        appendSystem(
                "Dang gui file: "
                        + file.getName()
                        + " ("
                        + file.length()
                        + " bytes)"
        );

        network.sendFile(file);
    }

    // =============================================================
    // UI HELPERS
    // =============================================================

    private void appendSystem(String message) {

        chatArea.appendText(
                "[SYSTEM] "
                        + message
                        + "\n"
        );
    }

    private void disableChat() {

        sendBtn.setDisable(true);
        sendFileBtn.setDisable(true);
        messageField.setDisable(true);

        messageField.clear();
    }

    private void resetConnectionUI() {

        connectBtn.setDisable(false);

        usernameField.setDisable(false);
        serverIpField.setDisable(false);

        refreshBtn.setDisable(true);
        selectUserBtn.setDisable(true);

        userList.getItems().clear();

        chatTargetLabel.setText(
                "Dang chat voi: (chua chon)"
        );

        disableChat();
    }

    // =============================================================
    // MAIN
    // =============================================================

    public static void main(String[] args) {
        launch(args);
    }
}