package com.printmanager.reports.ui;

import com.printmanager.ActivityLogger;
import com.printmanager.reports.model.ExamFlowDataSet;
import com.printmanager.reports.model.ReportPack;
import com.printmanager.reports.model.ReportPackItem;
import com.printmanager.reports.model.ReportType;
import com.printmanager.reports.service.ActiveSessionResolver;
import com.printmanager.reports.service.ExamFlowDataBridge;
import com.printmanager.reports.service.ExamFlowReportPrintService;
import com.printmanager.reports.service.ExamFlowReportRenderer;
import com.printmanager.reports.service.ReportPackManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.print.Printer;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

public class ReportsModal {

    private final Stage stage;
    private final ActivityLogger activityLogger;
    private final ReportPackManager packManager;
    private final ExamFlowReportPrintService printService;
    private ExamFlowDataSet dataSet;
    private String selectedSessionKey;

    private Label activeSessionBadge;
    private ComboBox<String> sessionSelect;
    private ComboBox<String> printerSelect;
    private VBox packsContainer;
    private Label statusLabel;
    private Runnable syncCallback;

    public ReportsModal(Stage owner, ExamFlowDataSet dataSet, ActivityLogger activityLogger, Runnable syncCallback) {
        this.activityLogger = activityLogger != null ? activityLogger : ActivityLogger.getInstance();
        this.packManager = new ReportPackManager();
        this.printService = new ExamFlowReportPrintService(this.activityLogger);
        this.dataSet = (dataSet != null) ? dataSet : ExamFlowDataBridge.getCurrentDataSet();
        this.syncCallback = syncCallback;

        this.stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("ExamFlow Reports Printing Module (1-Click Reports)");

        // Initial active session auto-detection
        resolveInitialSession();

        Scene scene = new Scene(createMainLayout(), 1100, 720);
        stage.setScene(scene);
    }

    public void show() {
        stage.show();
        stage.toFront();
    }

    public void updateDataSet(ExamFlowDataSet newDataSet) {
        this.dataSet = newDataSet;
        resolveInitialSession();
        refreshUi();
    }

    private void resolveInitialSession() {
        List<String> sessions = dataSet.getAllSessions();
        if (selectedSessionKey == null || !sessions.contains(selectedSessionKey)) {
            selectedSessionKey = ActiveSessionResolver.resolveActiveSession(sessions);
        }
    }

    private Parent createMainLayout() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f1f5f9;");

        // 1. TOP HEADER BAR
        VBox topHeader = new VBox(10);
        topHeader.setPadding(new Insets(15, 20, 15, 20));
        topHeader.setStyle("-fx-background-color: #1e293b; -fx-border-color: #0f172a; -fx-border-width: 0 0 1 0;");

        HBox titleRow = new HBox(15);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label("📊 ExamFlow Reports Printing");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: white;");

        activeSessionBadge = new Label();
        updateActiveSessionBadgeText();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button syncBtn = new Button("🔄 Sync ExamFlow");
        syncBtn.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        syncBtn.setOnAction(e -> {
            if (syncCallback != null) {
                statusLabel.setText("Triggering sync with ExamFlow browser...");
                syncCallback.run();
            }
        });

        Button loadBackupBtn = new Button("📂 Load Backup JSON");
        loadBackupBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-cursor: hand;");
        loadBackupBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select ExamFlow Full Backup JSON");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
            File file = fc.showOpenDialog(stage);
            if (file != null) {
                try {
                    ExamFlowDataSet ds = ExamFlowDataBridge.loadFromFile(file);
                    updateDataSet(ds);
                    statusLabel.setText("Loaded " + file.getName() + " successfully ✔");
                } catch (Exception ex) {
                    new Alert(Alert.AlertType.ERROR, "Failed to load backup: " + ex.getMessage()).show();
                }
            }
        });

        titleRow.getChildren().addAll(titleLabel, activeSessionBadge, spacer, syncBtn, loadBackupBtn);

        // Control strip: Session selector & Default Printer
        HBox controlRow = new HBox(15);
        controlRow.setAlignment(Pos.CENTER_LEFT);

        Label sessionLbl = new Label("Selected Session:");
        sessionLbl.setStyle("-fx-text-fill: #cbd5e1; -fx-font-weight: bold;");

        sessionSelect = new ComboBox<>();
        sessionSelect.setPrefWidth(260);
        refreshSessionDropdown();
        sessionSelect.setOnAction(e -> {
            String val = sessionSelect.getValue();
            if (val != null && !val.equals(selectedSessionKey)) {
                selectedSessionKey = val;
                updateActiveSessionBadgeText();
                statusLabel.setText("Switched to session: " + selectedSessionKey);
            }
        });

        Label printerLbl = new Label("Default Printer:");
        printerLbl.setStyle("-fx-text-fill: #cbd5e1; -fx-font-weight: bold;");

        printerSelect = new ComboBox<>();
        printerSelect.setPrefWidth(280);
        for (Printer p : Printer.getAllPrinters()) {
            printerSelect.getItems().add(p.getName());
        }
        Printer defP = Printer.getDefaultPrinter();
        if (defP != null) printerSelect.setValue(defP.getName());
        else if (!printerSelect.getItems().isEmpty()) printerSelect.setValue(printerSelect.getItems().get(0));

        controlRow.getChildren().addAll(sessionLbl, sessionSelect, printerLbl, printerSelect);
        topHeader.getChildren().addAll(titleRow, controlRow);
        root.setTop(topHeader);

        // 2. CENTER CONTENT (Packs + Reports)
        VBox centerContent = new VBox(20);
        centerContent.setPadding(new Insets(20));

        // SECTION A: 1-Click Packs Shelf
        HBox packHeaderRow = new HBox(12);
        packHeaderRow.setAlignment(Pos.CENTER_LEFT);

        Label packsTitle = new Label("📦 1-Click Administrative Packs");
        packsTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Label packsSubtitle = new Label("(Customizable multi-report batch routines)");
        packsSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Region pSpacer = new Region();
        HBox.setHgrow(pSpacer, Priority.ALWAYS);

        Button newPackBtn = new Button("➕ Define New Pack");
        newPackBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        newPackBtn.setOnAction(e -> PackEditorModal.show(stage, null, newPack -> {
            packManager.savePack(newPack);
            refreshPacksShelf();
            statusLabel.setText("Saved new pack: " + newPack.getName() + " ✔");
        }, null));

        Button resetPacksBtn = new Button("↺ Reset Packs");
        resetPacksBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #64748b; -fx-cursor: hand; -fx-border-color: #cbd5e1; -fx-border-radius: 4;");
        resetPacksBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Reset all packs to university defaults?", ButtonType.YES, ButtonType.NO);
            confirm.showAndWait().ifPresent(res -> {
                if (res == ButtonType.YES) {
                    packManager.resetToDefaults();
                    refreshPacksShelf();
                    statusLabel.setText("Packs reset to standard defaults.");
                }
            });
        });

        packHeaderRow.getChildren().addAll(packsTitle, packsSubtitle, pSpacer, newPackBtn, resetPacksBtn);

        packsContainer = new VBox(10);
        refreshPacksShelf();

        VBox packsBox = new VBox(10, packHeaderRow, packsContainer);
        packsBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        // SECTION B: Individual Reports Catalog
        Label catalogTitle = new Label("📑 Individual Reports Catalog (1-Click Print & Preview)");
        catalogTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        VBox reportsGrid = createReportsCatalogGrid();
        VBox catalogBox = new VBox(10, catalogTitle, reportsGrid);
        catalogBox.setStyle("-fx-background-color: white; -fx-padding: 15; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        centerContent.getChildren().addAll(packsBox, catalogBox);

        ScrollPane scrollPane = new ScrollPane(centerContent);
        scrollPane.setFitToWidth(true);
        root.setCenter(scrollPane);

        // 3. BOTTOM STATUS BAR
        HBox bottomBar = new HBox(15);
        bottomBar.setAlignment(Pos.CENTER_LEFT);
        bottomBar.setPadding(new Insets(10, 20, 10, 20));
        bottomBar.setStyle("-fx-background-color: #e2e8f0; -fx-border-color: #cbd5e1; -fx-border-width: 1 0 0 0;");

        statusLabel = new Label("Ready. Auto-detected active session: " + (selectedSessionKey != null ? selectedSessionKey : "None"));
        statusLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #334155;");

        bottomBar.getChildren().add(statusLabel);
        root.setBottom(bottomBar);

        return root;
    }

    private void updateActiveSessionBadgeText() {
        if (activeSessionBadge != null) {
            if (selectedSessionKey != null && !selectedSessionKey.isEmpty()) {
                activeSessionBadge.setText("⚡ " + selectedSessionKey);
                activeSessionBadge.setStyle("-fx-background-color: #065f46; -fx-text-fill: #6ee7b7; -fx-padding: 4 10; -fx-border-radius: 12; -fx-background-radius: 12; -fx-font-weight: bold;");
            } else {
                activeSessionBadge.setText("No Session Active");
                activeSessionBadge.setStyle("-fx-background-color: #991b1b; -fx-text-fill: #fecaca; -fx-padding: 4 10; -fx-border-radius: 12; -fx-background-radius: 12;");
            }
        }
    }

    private void refreshSessionDropdown() {
        if (sessionSelect != null) {
            List<String> sessions = dataSet.getAllSessions();
            sessionSelect.setItems(FXCollections.observableArrayList(sessions));
            if (selectedSessionKey != null && sessions.contains(selectedSessionKey)) {
                sessionSelect.setValue(selectedSessionKey);
            } else if (!sessions.isEmpty()) {
                sessionSelect.setValue(sessions.get(0));
                selectedSessionKey = sessions.get(0);
                updateActiveSessionBadgeText();
            }
        }
    }

    private void refreshPacksShelf() {
        packsContainer.getChildren().clear();
        List<ReportPack> packs = packManager.getPacks();

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);

        int col = 0;
        int row = 0;

        for (ReportPack pack : packs) {
            VBox card = new VBox(8);
            card.setPadding(new Insets(12));
            card.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #cbd5e1; -fx-border-radius: 6; -fx-background-radius: 6;");
            card.setPrefWidth(320);

            HBox cardHeader = new HBox(8);
            cardHeader.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(pack.getName());
            nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #0f172a;");
            HBox.setHgrow(nameLabel, Priority.ALWAYS);

            long activeCount = pack.getItems().stream().filter(ReportPackItem::isEnabled).count();
            Label countBadge = new Label(activeCount + " reports");
            countBadge.setStyle("-fx-background-color: #e2e8f0; -fx-font-size: 10px; -fx-padding: 2 6; -fx-border-radius: 4;");

            cardHeader.getChildren().addAll(nameLabel, countBadge);

            Label descLabel = new Label(pack.getDescription());
            descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
            descLabel.setWrapText(true);
            descLabel.setMinHeight(30);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button printPackBtn = new Button("🖨️ 1-Click Print");
            printPackBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            printPackBtn.setOnAction(e -> {
                if (selectedSessionKey == null) {
                    new Alert(Alert.AlertType.WARNING, "Please select an exam session first!").show();
                    return;
                }
                statusLabel.setText("Dispatching Pack '" + pack.getName() + "'...");
                printService.printPack(pack, dataSet, selectedSessionKey, printerSelect.getValue(), msg -> {
                    Platform.runLater(() -> statusLabel.setText(msg));
                });
            });

            Button editPackBtn = new Button("⚙️ Edit");
            editPackBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #475569; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-cursor: hand;");
            editPackBtn.setOnAction(e -> PackEditorModal.show(stage, pack, updated -> {
                packManager.savePack(updated);
                refreshPacksShelf();
                statusLabel.setText("Updated pack: " + updated.getName() + " ✔");
            }, () -> {
                packManager.deletePack(pack.getId());
                refreshPacksShelf();
                statusLabel.setText("Deleted pack: " + pack.getName());
            }));

            actions.getChildren().addAll(editPackBtn, printPackBtn);
            card.getChildren().addAll(cardHeader, descLabel, actions);

            grid.add(card, col, row);
            col++;
            if (col >= 3) {
                col = 0;
                row++;
            }
        }
        packsContainer.getChildren().add(grid);
    }

    private VBox createReportsCatalogGrid() {
        VBox container = new VBox(10);

        for (ReportType type : ReportType.values()) {
            HBox row = new HBox(15);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(8, 12, 8, 12));
            row.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 6; -fx-background-radius: 6;");

            VBox infoBox = new VBox(2);
            HBox titleLine = new HBox(10);
            titleLine.setAlignment(Pos.CENTER_LEFT);

            Label titleLabel = new Label(type.getDisplayName());
            titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #0f172a;");

            Label scopeBadge = new Label(type.getScope());
            scopeBadge.setStyle("-fx-background-color: #e2e8f0; -fx-font-size: 10px; -fx-padding: 2 6; -fx-border-radius: 4;");

            Label duplexBadge = new Label(type.isDefaultDuplex() ? "📄📄 DUPLEX" : "📄 SIMPLEX");
            duplexBadge.setStyle(type.isDefaultDuplex() ?
                    "-fx-background-color: #dcfce7; -fx-text-fill: #15803d; -fx-font-weight: bold; -fx-font-size: 10px; -fx-padding: 2 6; -fx-border-radius: 4;" :
                    "-fx-background-color: #dbeafe; -fx-text-fill: #1d4ed8; -fx-font-weight: bold; -fx-font-size: 10px; -fx-padding: 2 6; -fx-border-radius: 4;");

            titleLine.getChildren().addAll(titleLabel, scopeBadge, duplexBadge);

            Label descLabel = new Label(type.getDescription());
            descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");

            infoBox.getChildren().addAll(titleLine, descLabel);
            HBox.setHgrow(infoBox, Priority.ALWAYS);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);

            Button previewBtn = new Button("👁️ Preview");
            previewBtn.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-cursor: hand;");
            previewBtn.setOnAction(e -> showReportPreview(type));

            Button printBtn = new Button("🖨️ Print");
            printBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            printBtn.setOnAction(e -> {
                if (selectedSessionKey == null && type != ReportType.SCRIBE_MASTER) {
                    new Alert(Alert.AlertType.WARNING, "Please select an exam session first!").show();
                    return;
                }
                statusLabel.setText("Printing " + type.getDisplayName() + "...");
                printService.printReport(type, dataSet, selectedSessionKey, printerSelect.getValue(),
                        type.isDefaultDuplex(), 1, msg -> Platform.runLater(() -> statusLabel.setText(msg)));
            });

            Button saveBtn = new Button("💾 Save HTML");
            saveBtn.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-cursor: hand;");
            saveBtn.setOnAction(e -> {
                FileChooser fc = new FileChooser();
                fc.setTitle("Save " + type.getDisplayName());
                fc.setInitialFileName(type.getId() + "_" + (selectedSessionKey != null ? selectedSessionKey.replace('|', '_').replace(':', '-') : "report") + ".html");
                File f = fc.showSaveDialog(stage);
                if (f != null) {
                    try {
                        printService.exportReportToFile(type, dataSet, selectedSessionKey, f);
                        statusLabel.setText("Saved " + f.getName() + " ✔");
                    } catch (Exception ex) {
                        new Alert(Alert.AlertType.ERROR, "Failed to save: " + ex.getMessage()).show();
                    }
                }
            });

            actions.getChildren().addAll(previewBtn, saveBtn, printBtn);
            row.getChildren().addAll(infoBox, actions);
            container.getChildren().add(row);
        }
        return container;
    }

    private void showReportPreview(ReportType type) {
        String html = ExamFlowReportRenderer.renderReport(type, dataSet, selectedSessionKey);

        Stage previewStage = new Stage();
        previewStage.initOwner(stage);
        previewStage.initModality(Modality.NONE);
        previewStage.setTitle("Preview: " + type.getDisplayName() + " - " + selectedSessionKey);

        BorderPane pRoot = new BorderPane();
        WebView webView = new WebView();
        webView.getEngine().loadContent(html);
        pRoot.setCenter(webView);

        HBox pTop = new HBox(12);
        pTop.setPadding(new Insets(10));
        pTop.setAlignment(Pos.CENTER_LEFT);
        pTop.setStyle("-fx-background-color: #1e293b;");

        Label pTitle = new Label(type.getDisplayName() + " (" + (type.isDefaultDuplex() ? "Duplex" : "Simplex") + ")");
        pTitle.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        Region pSpacer = new Region();
        HBox.setHgrow(pSpacer, Priority.ALWAYS);

        Button pPrintBtn = new Button("🖨️ Print Now");
        pPrintBtn.setStyle("-fx-background-color: #2563eb; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        pPrintBtn.setOnAction(e -> {
            printService.printReport(type, dataSet, selectedSessionKey, printerSelect.getValue(),
                    type.isDefaultDuplex(), 1, msg -> Platform.runLater(() -> statusLabel.setText(msg)));
            previewStage.close();
        });

        pTop.getChildren().addAll(pTitle, pSpacer, pPrintBtn);
        pRoot.setTop(pTop);

        Scene pScene = new Scene(pRoot, 950, 700);
        previewStage.setScene(pScene);
        previewStage.show();
    }

    public void refreshUi() {
        refreshSessionDropdown();
        updateActiveSessionBadgeText();
        refreshPacksShelf();
        statusLabel.setText("Refreshed with " + dataSet.getAllSessions().size() + " session(s).");
    }
}
