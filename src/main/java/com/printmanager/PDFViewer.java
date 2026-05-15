package com.printmanager;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.geometry.Rectangle2D;
import javafx.collections.FXCollections;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.FontPosture;

public class PDFViewer {
    private final PDFService pdfService = new PDFService();
    
    public interface FileItemCallback {
        void add(File file, String style, String overlay);
    }

    public void show(File file, boolean isInitialAdd, FileItemCallback onResult) {
        Stage stage = new Stage();
        stage.setTitle("PDF Viewer: " + file.getName());

        VBox contentBox = new VBox(10);
        contentBox.setPadding(new Insets(10));
        
        Pane drawPane = new Pane(contentBox);
        drawPane.setStyle("-fx-background-color: white;");
        
        ScrollPane scrollPane = new ScrollPane(drawPane);
        scrollPane.setFitToWidth(true);

        TextField startField = new TextField("1"); startField.setPrefWidth(40);
        TextField endField = new TextField("1"); endField.setPrefWidth(40);
        HBox rangeBox = new HBox(5, new Label("Pages:"), startField, new Label("-"), endField);
        rangeBox.setAlignment(Pos.CENTER_LEFT);

        Button addBtn = new Button("Add to Queue");
        addBtn.setStyle("-fx-base: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        addBtn.setOnAction(e -> {
            try {
                int start = Integer.parseInt(startField.getText());
                int end = Integer.parseInt(endField.getText());
                File resultFile = pdfService.splitPages(file, start, end);
                onResult.add(resultFile, "Simplex", "");
                if (isInitialAdd) stage.close();
            } catch (Exception ex) { 
                Alert alert = new Alert(Alert.AlertType.ERROR, "Invalid page range: " + ex.getMessage());
                alert.show();
            }
        });

        Button closeBtn = new Button("Close");
        closeBtn.setOnAction(e -> stage.close());

        HBox toolBar = new HBox(10, new Region(), rangeBox, addBtn, closeBtn);
        HBox.setHgrow(toolBar.getChildren().get(0), Priority.ALWAYS);
        toolBar.setPadding(new Insets(10));
        toolBar.setAlignment(Pos.CENTER_LEFT);
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ccc; -fx-border-width: 0 0 1 0;");

        VBox layout = new VBox(toolBar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        refreshPreview(file, contentBox, endField);

        Rectangle2D bounds = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(layout, bounds.getWidth() * 0.85, bounds.getHeight() * 0.85);
        stage.setScene(scene);
        stage.show();
    }

    private void refreshPreview(File file, VBox contentBox, TextField endField) {
        contentBox.getChildren().clear();
        new Thread(() -> {
            try {
                int pages = pdfService.getPageCount(file);
                javafx.application.Platform.runLater(() -> endField.setText(String.valueOf(pages)));
                for (int i = 0; i < pages; i++) {
                    BufferedImage bimg = pdfService.renderPage(file, i, 1.2f);
                    WritableImage fxImage = SwingFXUtils.toFXImage(bimg, null);
                    javafx.application.Platform.runLater(() -> {
                        ImageView iv = new ImageView(fxImage);
                        iv.setPreserveRatio(true);
                        contentBox.getChildren().add(iv);
                    });
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }
}