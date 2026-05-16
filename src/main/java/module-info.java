module com.printmanager {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;
    requires org.apache.pdfbox;
    requires com.fasterxml.jackson.databind;
    requires java.desktop;
    requires java.net.http;
    requires org.slf4j;

    opens com.printmanager.model to com.fasterxml.jackson.databind, javafx.base;
    exports com.printmanager;
    exports com.printmanager.model;
}
