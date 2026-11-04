package com.printmanager.reports.ui;

import com.printmanager.reports.model.ReportPack;
import com.printmanager.reports.model.ReportPackItem;
import com.printmanager.reports.model.ReportType;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.Printer;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;
import java.util.function.Consumer;

public class PackEditorModal {

    public static void show(Stage owner, ReportPack packToEdit, Consumer<ReportPack> onSave, Runnable onDelete) {
        boolean isNew = (packToEdit == null);
        ReportPack workingPack = isNew ?
                new ReportPack("pack_" + System.currentTimeMillis(), "New Custom Pack", "Custom configured report set", "Default", new ArrayList<>())
                : packToEdit.copy();

        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(isNew ? "Create New Report Pack" : "Edit Report Pack - " + workingPack.getName());

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: #f8fafc;");

        Label title = new Label(isNew ? "➕ Define New Report Pack" : "⚙️ Edit Report Pack");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        // Pack Metadata
        GridPane metaGrid = new GridPane();
        metaGrid.setHgap(10);
        metaGrid.setVgap(10);

        TextField nameField = new TextField(workingPack.getName());
        nameField.setPrefWidth(280);

        TextField descField = new TextField(workingPack.getDescription());
        descField.setPrefWidth(280);

        ComboBox<String> printerCombo = new ComboBox<>();
        printerCombo.getItems().add("Default");
        for (Printer p : Printer.getAllPrinters()) {
            printerCombo.getItems().add(p.getName());
        }
        printerCombo.setValue(workingPack.getTargetPrinter() != null ? workingPack.getTargetPrinter() : "Default");

        metaGrid.add(new Label("Pack Name:"), 0, 0);
        metaGrid.add(nameField, 1, 0);
        metaGrid.add(new Label("Description:"), 0, 1);
        metaGrid.add(descField, 1, 1);
        metaGrid.add(new Label("Target Printer:"), 0, 2);
        metaGrid.add(printerCombo, 1, 2);

        Label reportsHeader = new Label("Select Reports to Include & Configure Settings:");
        reportsHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #334155;");

        // Map existing items
        Map<String, ReportPackItem> itemMap = new HashMap<>();
        for (ReportPackItem item : workingPack.getItems()) {
            itemMap.put(item.getReportId(), item);
        }

        VBox reportsList = new VBox(8);
        reportsList.setPadding(new Insets(10));
        reportsList.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 6; -fx-background-radius: 6;");

        List<RowController> controllers = new ArrayList<>();

        for (ReportType type : ReportType.values()) {
            ReportPackItem existing = itemMap.get(type.getId());
            boolean isChecked = (existing != null && existing.isEnabled());
            int copies = (existing != null) ? existing.getCopies() : 1;
            boolean isDuplex = (existing != null) ? existing.isDuplex() : type.isDefaultDuplex();

            HBox row = new HBox(12);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(4, 8, 4, 8));

            CheckBox cb = new CheckBox(type.getDisplayName());
            cb.setSelected(isChecked);
            cb.setStyle("-fx-font-weight: bold; -fx-font-size: 11pt;");
            HBox.setHgrow(cb, Priority.ALWAYS);

            Label scopeBadge = new Label(type.getScope());
            scopeBadge.setStyle("-fx-background-color: #e2e8f0; -fx-padding: 2 6; -fx-border-radius: 4; -fx-font-size: 9pt;");

            Spinner<Integer> copySpinner = new Spinner<>(1, 50, copies);
            copySpinner.setPrefWidth(70);
            copySpinner.setDisable(!isChecked);

            ComboBox<String> duplexCombo = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex"));
            duplexCombo.setValue(isDuplex ? "Duplex" : "Simplex");
            duplexCombo.setDisable(!isChecked);

            cb.selectedProperty().addListener((obs, oldV, newV) -> {
                copySpinner.setDisable(!newV);
                duplexCombo.setDisable(!newV);
            });

            row.getChildren().addAll(cb, scopeBadge, new Label("Copies:"), copySpinner, duplexCombo);
            reportsList.getChildren().add(row);

            controllers.add(new RowController(type, cb, copySpinner, duplexCombo));
        }

        ScrollPane scrollPane = new ScrollPane(reportsList);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(320);

        // Buttons
        HBox buttonBar = new HBox(12);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("Cancel");
        cancelBtn.setOnAction(e -> stage.close());

        Button saveBtn = new Button("💾 Save Pack");
        saveBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 18;");
        saveBtn.setOnAction(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                new Alert(Alert.AlertType.WARNING, "Please enter a Pack Name.").show();
                return;
            }

            workingPack.setName(name);
            workingPack.setDescription(descField.getText().trim());
            workingPack.setTargetPrinter(printerCombo.getValue());

            List<ReportPackItem> newItems = new ArrayList<>();
            for (RowController rc : controllers) {
                if (rc.checkBox.isSelected()) {
                    boolean duplex = "Duplex".equalsIgnoreCase(rc.duplexCombo.getValue());
                    int c = rc.copySpinner.getValue();
                    newItems.add(new ReportPackItem(rc.type.getId(), true, c, duplex));
                }
            }

            if (newItems.isEmpty()) {
                new Alert(Alert.AlertType.WARNING, "Please select at least one report for this pack.").show();
                return;
            }

            workingPack.setItems(newItems);
            if (onSave != null) onSave.accept(workingPack);
            stage.close();
        });

        if (!isNew && onDelete != null) {
            Button deleteBtn = new Button("🗑️ Delete Pack");
            deleteBtn.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold;");
            deleteBtn.setOnAction(e -> {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Are you sure you want to delete pack '" + workingPack.getName() + "'?", ButtonType.YES, ButtonType.NO);
                confirm.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.YES) {
                        onDelete.run();
                        stage.close();
                    }
                });
            });
            buttonBar.getChildren().add(deleteBtn);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        buttonBar.getChildren().addAll(spacer, cancelBtn, saveBtn);

        root.getChildren().addAll(title, metaGrid, reportsHeader, scrollPane, buttonBar);

        Scene scene = new Scene(root, 680, 560);
        stage.setScene(scene);
        stage.show();
    }

    private static class RowController {
        final ReportType type;
        final CheckBox checkBox;
        final Spinner<Integer> copySpinner;
        final ComboBox<String> duplexCombo;

        RowController(ReportType type, CheckBox checkBox, Spinner<Integer> copySpinner, ComboBox<String> duplexCombo) {
            this.type = type;
            this.checkBox = checkBox;
            this.copySpinner = copySpinner;
            this.duplexCombo = duplexCombo;
        }
    }
}
