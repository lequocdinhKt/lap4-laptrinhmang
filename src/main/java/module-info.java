module com.p2pchat.p2pchat {
    requires javafx.controls;
    requires javafx.fxml;


    opens com.p2pchat.p2pchat to javafx.fxml;
    exports com.p2pchat.p2pchat;
}