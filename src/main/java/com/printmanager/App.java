package com.printmanager;

import com.printmanager.model.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class App extends Application {
    private static final Logger logger = LoggerFactory.getLogger(App.class);

    private final PDFService pdfService = new PDFService();
    private final PrintService printService = new PrintService();
    private final ConfigManager configManager = new ConfigManager();
    private final PDFViewer pdfViewer = new PDFViewer();
    private final ActivityLogger activityLogger = ActivityLogger.getInstance();

    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService printQueueExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService roomPrintExecutor = Executors.newFixedThreadPool(10);

    private Config config;
    private final ObservableList<FileItem> fileQueue = FXCollections.observableArrayList(item -> new javafx.beans.Observable[] {
        item.copiesProperty(), item.targetPrinterProperty(), item.statusProperty(), item.styleProperty(), item.paperSizeProperty()
    });
    private final FilteredList<FileItem> filteredQueue = new FilteredList<>(fileQueue, p -> true);
    private final javafx.collections.transformation.SortedList<FileItem> sortedQueue = new javafx.collections.transformation.SortedList<>(filteredQueue);
    private final ObservableList<PrintRule> rulesList = FXCollections.observableArrayList();
    private final ObservableList<SmartSplitRule> smartSplitRulesList = FXCollections.observableArrayList();
    private final ObservableList<RoomGroup> roomGroupsList = FXCollections.observableArrayList(group -> new javafx.beans.Observable[] {
        group.statusProperty(), group.selectedPrinterProperty()
    });

    private final Label statusBar = new Label("Ready");
    private volatile boolean isPrintingAll = false;
    private final Map<String, String> printerStatusCache = new HashMap<>();
    private final HBox simAlertHeader = new HBox();
    private final CheckBox printCoverPageCbox = new CheckBox("Print Room Status Cover Page?");

    @Override
    public void start(Stage primaryStage) {
        config = configManager.loadConfig();
        logger.info("Application start: Loaded {} rules, {} split rules, {} queue items, {} room groups",
            config.getRules().size(), config.getSmartSplitRules().size(), config.getFileQueue().size(), config.getRoomGroups().size());

        rulesList.addAll(config.getRules());
        smartSplitRulesList.addAll(config.getSmartSplitRules());
        fileQueue.addAll(config.getFileQueue());
        roomGroupsList.addAll(config.getRoomGroups());

        // Re-link RoomItem.matchedFile to actual instances in fileQueue for identity consistency
        for (RoomGroup g : roomGroupsList) {
            for (RoomItem i : g.getItems()) {
                if (i.getMatchedFile() != null) {
                    for (FileItem qItem : fileQueue) {
                        if (qItem.getFileName().equals(i.getMatchedFile().getFileName()) && qItem.getPageCount() == i.getMatchedFile().getPageCount()) {
                            i.setMatchedFile(qItem);
                            break;
                        }
                    }
                }
            }
        }

        TabPane tabPane = new TabPane();
        Tab mainTab = new Tab("Print Queue", createMainView(primaryStage));
        mainTab.setClosable(false);
        Tab roomTab = new Tab("Smart Room Wise Router", createRoomRouterView(primaryStage));
        roomTab.setClosable(false);
        Tab printerTab = new Tab("Printer Dashboard", createPrinterDashboardView());
        printerTab.setClosable(false);
        Tab logsTab = new Tab("Activity Logs", createLogsView());
        logsTab.setClosable(false);
        Tab settingsTab = new Tab("Settings", createSettingsView());
        settingsTab.setClosable(false);
        Tab aboutTab = new Tab("About", createAboutView());
        aboutTab.setClosable(false);
        tabPane.getTabs().addAll(mainTab, roomTab, printerTab, logsTab, settingsTab, aboutTab);

        simAlertHeader.setId("simulation-alert");
        simAlertHeader.getChildren().add(new Label("⚠ SIMULATION MODE ACTIVE: Actual printing is disabled. Change this in Settings."));
        simAlertHeader.setManaged(false);
        simAlertHeader.setVisible(false);

        VBox root = new VBox(simAlertHeader, tabPane, createStatusBarView());
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 1200, 850);
        try {
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } catch (Exception e) { logger.warn("Could not load CSS"); }
        
        primaryStage.setTitle("Smart QP Print Manager v3.0.3");
        
        try {
            primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png")));
        } catch (Exception e) {
            logger.warn("Could not load application icon", e);
        }

        primaryStage.setScene(scene);
        primaryStage.setMaximized(true);
        primaryStage.show();

        // Setup auto-save listeners AFTER UI is ready and initial load is complete
        Platform.runLater(() -> {
            // Re-initialize Room Router view content to force UI refresh with loaded data
            roomTab.setContent(createRoomRouterView(primaryStage));

            fileQueue.addListener((javafx.collections.ListChangeListener<FileItem>) c -> saveConfigs());
            roomGroupsList.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> {
                saveConfigs();
                while (c.next()) {
                    if (c.wasAdded()) {
                        c.getAddedSubList().forEach(this::setupRoomGroupListeners);
                    }
                }
            });
            roomGroupsList.forEach(this::setupRoomGroupListeners);
        });

        activityLogger.info("Application started");

        startPrinterStatusMonitor();
        updateSimulationUI();
    }

    private void updateSimulationUI() {
        boolean active = printService.isSimulationMode();
        simAlertHeader.setManaged(active);
        simAlertHeader.setVisible(active);
    }

    private void startPrinterStatusMonitor() {
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Map<String, String> currentStatus = printService.getPrintersStatus();
                    Platform.runLater(() -> {
                        printerStatusCache.clear();
                        printerStatusCache.putAll(currentStatus);
                    });
                    Thread.sleep(10000); 
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Printer monitor error", e);
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    @Override
    public void stop() {
        analysisExecutor.shutdownNow();
        printQueueExecutor.shutdownNow();
        activityLogger.info("Application stopped");
    }

    private HBox createStatusBarView() {
        statusBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusBar, Priority.ALWAYS);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.getStyleClass().add("status-bar");
        return new HBox(statusBar);
    }

    private void updateStatus(String message) {
        Platform.runLater(() -> statusBar.setText(message));
    }

    private VBox createLogsView() {
        TableView<ActivityLogger.LogEntry> table = new TableView<>(activityLogger.getLogs());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<ActivityLogger.LogEntry, String> timeCol = new TableColumn<>("Timestamp");
        timeCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getTimestamp()));
        timeCol.setPrefWidth(150); timeCol.setMaxWidth(160);

        TableColumn<ActivityLogger.LogEntry, String> levelCol = new TableColumn<>("Level");
        levelCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getLevel()));
        levelCol.setPrefWidth(80); levelCol.setMaxWidth(90);

        TableColumn<ActivityLogger.LogEntry, String> msgCol = new TableColumn<>("Message");
        msgCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getMessage()));

        table.getColumns().addAll(timeCol, levelCol, msgCol);

        table.setRowFactory(tv -> new TableRow<ActivityLogger.LogEntry>() {
            @Override protected void updateItem(ActivityLogger.LogEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (item == null || empty) setStyle("");
                else {
                    if ("ERROR".equals(item.getLevel())) getStyleClass().add("log-entry-error");
                    else if ("SUCCESS".equals(item.getLevel())) getStyleClass().add("log-entry-success");
                    else getStyleClass().add("log-entry-info");
                }
            }
        });

        VBox layout = new VBox(10, new Label("System Activity Logs:"), table);
        layout.setPadding(new Insets(10));
        VBox.setVgrow(table, Priority.ALWAYS);
        return layout;
    }

    private VBox createMainView(Stage stage) {
        HBox statsDash = new HBox(30);
        statsDash.setPadding(new Insets(15));
        statsDash.getStyleClass().add("glass-panel");
        
        Label totalJobs = new Label("Total Jobs: 0");
        totalJobs.setStyle("-fx-font-weight: bold; -fx-text-fill: #2196F3;");
        Label activePrinters = new Label("Active Printers: 0");
        activePrinters.setStyle("-fx-font-weight: bold; -fx-text-fill: #4CAF50;");
        Label totalPages = new Label("Total Pages: 0");
        totalPages.setStyle("-fx-font-weight: bold; -fx-text-fill: #FF9800;");

        Label simWarning = new Label("Simulation Mode is ON");
        simWarning.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
        simWarning.visibleProperty().bind(simAlertHeader.visibleProperty());
        simWarning.managedProperty().bind(simAlertHeader.managedProperty());

        fileQueue.addListener((javafx.collections.ListChangeListener<FileItem>) c -> {
            totalJobs.setText("Total Jobs: " + fileQueue.size());
            totalPages.setText("Total Pages: " + fileQueue.stream().mapToInt(FileItem::getPageCount).sum());
        });

        Thread statsThread = new Thread(() -> {
            while(true) {
                try {
                    long active = printerStatusCache.values().stream().filter(s -> "Ready".equalsIgnoreCase(s) || "Printing".equalsIgnoreCase(s)).count();
                    Platform.runLater(() -> activePrinters.setText("Active Printers: " + active));
                    Thread.sleep(5000);
                } catch (Exception e) { break; }
            }
        });
        statsThread.setDaemon(true);
        statsThread.start();

        statsDash.getChildren().addAll(totalJobs, activePrinters, totalPages, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, simWarning);

        TextField searchField = new TextField();
        searchField.setPromptText("Search files...");
        searchField.textProperty().addListener((obs, old, newValue) -> {
            filteredQueue.setPredicate(item -> {
                if (newValue == null || newValue.isEmpty()) return true;
                String low = newValue.toLowerCase();
                return item.getFileName().toLowerCase().contains(low);
            });
        });

        TableView<FileItem> table = new TableView<>(sortedQueue);
        sortedQueue.comparatorProperty().bind(table.comparatorProperty());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<FileItem, Integer> snCol = new TableColumn<>("S.No");
        snCol.setCellFactory(col -> new TableCell<FileItem, Integer>() {
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setText(null);
                else setText(String.valueOf(getIndex() + 1));
            }
        });
        snCol.setPrefWidth(50); snCol.setMaxWidth(60); snCol.setSortable(false);

        TableColumn<FileItem, String> nameCol = new TableColumn<>("File Name");
        nameCol.setCellValueFactory(d -> d.getValue().fileNameProperty());
        nameCol.setMinWidth(300);

        TableColumn<FileItem, Integer> pagesCol = new TableColumn<>("Pages");
        pagesCol.setCellValueFactory(d -> d.getValue().pageCountProperty().asObject());
        pagesCol.setPrefWidth(60); pagesCol.setMaxWidth(70);
        pagesCol.setCellFactory(tc -> new TableCell<FileItem, Integer>() {
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else { setText(item.toString()); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        TableColumn<FileItem, Integer> copiesCol = new TableColumn<>("Copies");
        copiesCol.setCellValueFactory(d -> d.getValue().copiesProperty().asObject());
        copiesCol.setPrefWidth(80); copiesCol.setMaxWidth(90);
        copiesCol.setCellFactory(tc -> new TableCell<FileItem, Integer>() {
            private final Spinner<Integer> spinner = new Spinner<>(1, 999, 1);
            { spinner.setPrefWidth(70); spinner.valueProperty().addListener((o, ov, nv) -> {
                if (getTableRow().getItem() != null) getTableRow().getItem().setCopies(nv);
            }); }
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else { spinner.getValueFactory().setValue(item); setGraphic(spinner); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        TableColumn<FileItem, String> styleCol = new TableColumn<>("Style");
        styleCol.setCellValueFactory(d -> d.getValue().styleProperty());
        styleCol.setPrefWidth(100); styleCol.setMaxWidth(110);
        styleCol.setCellFactory(tc -> new TableCell<FileItem, String>() {
            private final ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet"));
            { combo.setPrefWidth(90); combo.setOnAction(e -> {
                if (getTableRow() != null && getTableRow().getItem() != null) getTableRow().getItem().setStyle(combo.getValue());
            }); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else { combo.setValue(item); setGraphic(combo); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        TableColumn<FileItem, String> printerCol = new TableColumn<>("Printer");
        printerCol.setCellValueFactory(d -> d.getValue().targetPrinterProperty());
        printerCol.setMinWidth(150);
        printerCol.setCellFactory(tc -> new TableCell<FileItem, String>() {
            private final ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(printService.getAvailablePrinters()));
            { combo.setPrefWidth(140); combo.setOnAction(e -> {
                if (getTableRow() != null && getTableRow().getItem() != null) getTableRow().getItem().setTargetPrinter(combo.getValue());
            }); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else { combo.setValue(item); setGraphic(combo); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        TableColumn<FileItem, String> paperCol = new TableColumn<>("Paper");
        paperCol.setCellValueFactory(d -> d.getValue().paperSizeProperty());
        paperCol.setPrefWidth(80); paperCol.setMaxWidth(90);
        paperCol.setCellFactory(tc -> new TableCell<FileItem, String>() {
            private final ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("A4", "A3"));
            { combo.setPrefWidth(70); combo.setOnAction(e -> {
                if (getTableRow() != null && getTableRow().getItem() != null) getTableRow().getItem().setPaperSize(combo.getValue());
            }); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else { combo.setValue(item); setGraphic(combo); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        TableColumn<FileItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> d.getValue().statusProperty());
        statusCol.setPrefWidth(80); statusCol.setMaxWidth(90);
        statusCol.setCellFactory(tc -> new TableCell<FileItem, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else {
                    setText(item); setAlignment(javafx.geometry.Pos.CENTER);
                    if (item.contains("Sent")) setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                    else if (item.contains("Finished")) setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                    else if (item.contains("Error")) setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    else setStyle("");
                }
            }
        });

        TableColumn<FileItem, Void> actionCol = new TableColumn<>("Action");
        actionCol.setMinWidth(250); actionCol.setMaxWidth(280);
        actionCol.setCellFactory(tc -> new TableCell<FileItem, Void>() {
            private final Button pBtn = new Button("Print");
            private final Button sBtn = new Button("Save");
            private final Button vBtn = new Button("\uD83D\uDC41");
            private final Button rBtn = new Button("X");
            private final HBox container = new HBox(8, pBtn, sBtn, vBtn, rBtn);
            {
                container.setAlignment(javafx.geometry.Pos.CENTER);
                pBtn.setOnAction(e -> { if (getTableRow().getItem() != null) printFile(getTableRow().getItem()); });
                sBtn.setOnAction(e -> { if (getTableRow().getItem() != null) saveFileAs(getTableRow().getItem()); });
                vBtn.setOnAction(e -> { if (getTableRow().getItem() != null) previewFile(getTableRow().getItem(), false); });
                rBtn.setOnAction(e -> { if (getTableRow().getItem() != null) fileQueue.remove(getTableRow().getItem()); });
                rBtn.setStyle("-fx-text-fill: red;");
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null); else setGraphic(container);
            }
        });

        table.getColumns().addAll(snCol, nameCol, pagesCol, copiesCol, styleCol, printerCol, paperCol, statusCol, actionCol);

        table.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<FileItem>() {
                @Override protected void updateItem(FileItem item, boolean empty) {
                    super.updateItem(item, empty);
                    if (item == null || empty) setStyle("");
                    else updateRowStyle(this, item.getStatus());
                }
            };
            row.itemProperty().addListener((obs, old, item) -> {
                if (item != null) item.statusProperty().addListener((o, ov, nv) -> updateRowStyle(row, nv));
            });
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    pdfViewer.show(row.getItem().getFile(), false, (subFile, style, overlay) -> addFileToQueue(subFile, style, overlay));
                }
            });
            return row;
        });

        Button addBtn = new Button("Add PDFs");
        addBtn.setId("add-btn");
        addBtn.setTooltip(new Tooltip("Add PDF files to the print queue"));
        addBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
            List<File> files = fc.showOpenMultipleDialog(stage);
            if (files != null) files.forEach(this::processFile);
        });

        Button printBtn = new Button("Print All");
        printBtn.setId("print-btn");
        printBtn.setTooltip(new Tooltip("Print all files in the queue that have a printer assigned"));
        printBtn.setOnAction(e -> printAll());

        Button clearBtn = new Button("Clear All");
        clearBtn.setId("clear-btn");
        clearBtn.setTooltip(new Tooltip("Remove all files from the queue"));
        clearBtn.setOnAction(e -> fileQueue.clear());

        Button loadJsonBtn = new Button("Upload JSON");
        loadJsonBtn.setId("load-json-btn");
        loadJsonBtn.setTooltip(new Tooltip("Upload a JSON file to automatically update copy counts based on QP codes"));
        loadJsonBtn.setOnAction(e -> loadJsonAndUpdateCopies(stage));

        HBox btns = new HBox(15, addBtn, loadJsonBtn, printBtn, clearBtn);
        btns.setPadding(new Insets(10));
        btns.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox layout = new VBox(10, statsDash, searchField, table, btns);
        VBox.setVgrow(table, Priority.ALWAYS);
        layout.setPadding(new Insets(10));
        return layout;
    }

    public static class PrinterDisplay {
        private final String name;
        private final SimpleStringProperty status;
        private final SimpleStringProperty activeJobs;
        private final SimpleStringProperty currentTask;

        public PrinterDisplay(String name, String status) {
            this.name = name;
            this.status = new SimpleStringProperty(status);
            this.activeJobs = new SimpleStringProperty("0");
            this.currentTask = new SimpleStringProperty("Idle");
        }

        public String getName() { return name; }
        public String getStatus() { return status.get(); }
        public SimpleStringProperty statusProperty() { return status; }
        public void setStatus(String status) { this.status.set(status); }

        public String getActiveJobs() { return activeJobs.get(); }
        public SimpleStringProperty activeJobsProperty() { return activeJobs; }
        public void setActiveJobs(String activeJobs) { this.activeJobs.set(activeJobs); }

        public String getCurrentTask() { return currentTask.get(); }
        public SimpleStringProperty currentTaskProperty() { return currentTask; }
        public void setCurrentTask(String currentTask) { this.currentTask.set(currentTask); }
    }

    private VBox createPrinterDashboardView() {
        ObservableList<PrinterDisplay> printerDisplays = FXCollections.observableArrayList();
        TableView<PrinterDisplay> table = new TableView<>(printerDisplays);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.getStyleClass().add("glass-panel");

        TableColumn<PrinterDisplay, String> nameCol = new TableColumn<>("Printer Name");
        nameCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getName()));

        TableColumn<PrinterDisplay, String> statusCol = new TableColumn<>("Hardware Status");
        statusCol.setCellValueFactory(d -> d.getValue().statusProperty());
        statusCol.setCellFactory(tc -> new TableCell<PrinterDisplay, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else {
                    setText(item); setAlignment(javafx.geometry.Pos.CENTER);
                    if ("Ready".equalsIgnoreCase(item)) setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                    else if ("Offline".equalsIgnoreCase(item)) setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    else if ("Printing".equalsIgnoreCase(item)) setStyle("-fx-text-fill: blue; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: orange;");
                }
            }
        });

        TableColumn<PrinterDisplay, String> jobsCol = new TableColumn<>("Active Jobs");
        jobsCol.setCellValueFactory(d -> d.getValue().activeJobsProperty());
        jobsCol.setPrefWidth(100); jobsCol.setMaxWidth(120);
        jobsCol.setCellFactory(tc -> new TableCell<PrinterDisplay, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setText(null); else { setText(item); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        TableColumn<PrinterDisplay, String> taskCol = new TableColumn<>("Current Task");
        taskCol.setCellValueFactory(d -> d.getValue().currentTaskProperty());
        taskCol.setCellFactory(tc -> new TableCell<PrinterDisplay, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setText(null); else { setText(item); setAlignment(javafx.geometry.Pos.CENTER); }
            }
        });

        table.getColumns().addAll(nameCol, statusCol, jobsCol, taskCol);

        printService.getAvailablePrinters().stream()
            .filter(n -> !"None".equals(n))
            .forEach(n -> printerDisplays.add(new PrinterDisplay(n, "Checking...")));

        Thread dashboardUpdater = new Thread(() -> {
            while (true) {
                try {
                    Map<String, Map<String, String>> detailed = printService.getPrintersDetailedStatus();
                    Platform.runLater(() -> {
                        for (PrinterDisplay pd : printerDisplays) {
                            if (detailed.containsKey(pd.getName())) {
                                Map<String, String> data = detailed.get(pd.getName());
                                pd.setStatus(data.get("status"));
                                pd.setActiveJobs(data.get("jobs"));
                                pd.setCurrentTask(data.get("current"));
                            }
                        }
                    });
                    Thread.sleep(5000); 
                } catch (InterruptedException e) { break; }
                catch (Exception e) { logger.error("Dashboard error", e); }
            }
        });
        dashboardUpdater.setDaemon(true);
        dashboardUpdater.start();

        VBox layout = new VBox(20, new Label("Live Printer Activities:") {{ setStyle("-fx-font-size: 18px; -fx-font-weight: bold;"); }}, table);
        layout.setPadding(new Insets(30));
        layout.setAlignment(javafx.geometry.Pos.CENTER);
        VBox.setVgrow(table, Priority.ALWAYS);
        return layout;
    }

    private void processFile(File file) {
        analysisExecutor.submit(() -> {
            try {
                updateStatus("Analyzing: " + file.getName());
                SmartSplitRule matched = null;
                int pageIdx = -1;
                for (SmartSplitRule r : smartSplitRulesList) {
                    if (r.isEnabled()) {
                        pageIdx = pdfService.findKeywordPage(file, r.getKeyword());
                        if (pageIdx != -1) { matched = r; break; }
                    }
                }

                if (matched != null) {
                    if (pageIdx > 1) {
                        File b = pdfService.splitPages(file, 1, pageIdx - 1);
                        routeSmartPart(b, file.getName(), matched, false);
                    }
                    int total = pdfService.getPageCount(file);
                    File a = pdfService.splitPages(file, pageIdx, total);
                    routeSmartPart(a, file.getName(), matched, true);
                } else {
                    addFileToQueue(file, null, null);
                }
            } catch (Exception e) { logger.error("Process error", e); }
        });
    }

    private void routeSmartPart(File file, String originalName, SmartSplitRule rule, boolean isAfter) throws Exception {
        int pages = pdfService.getPageCount(file);
        String style = "Simplex"; String overlay = ""; File f = file;
        String prefix = isAfter ? rule.getAfterPrefix() : rule.getBeforePrefix();

        if (isAfter) {
            if (pages == 1) style = rule.getAfterStyle1();
            else if (pages == 2) style = rule.getAfterStyle2();
            else if (pages <= 4) style = rule.getAfterStyle3To4();
            else if (pages == 5 && rule.isSpecial5PageMode()) { 
                f = pdfService.splitPages(file, 2, 5); 
                pages = 4; 
                style = "Booklet"; 
                overlay = "MCQ"; 
            } else style = rule.getAfterStyle6Plus();
        } else {
            if (pages == 1) style = rule.getBeforeStyle1();
            else if (pages == 2) style = rule.getBeforeStyle2();
            else style = rule.getBeforeStyle3Plus();
        }

        final String fs = style; final String fo = overlay; final File ff = f; final int fp = pages;
        Platform.runLater(() -> {
            FileItem item = new FileItem(ff, fp, "", "None", false, false, "Left", 1, "A4", fo);
            item.setFileName(prefix + originalName);
            item.setStyle(fs);
            fileQueue.add(item);
        });
    }

    private void addFileToQueue(File file, String manualStyle, String manualOverlay) {
        analysisExecutor.submit(() -> {
            try {
                int p = pdfService.getPageCount(file);
                String c = pdfService.getText(file);
                PrintRule rule = findMatchingRule(p, c);
                
                String pr = (rule != null) ? rule.getPrinterName() : "None";
                
                // Smart Printer Fallback: If rule printer is None/Missing, use System Default
                List<String> available = printService.getAvailablePrinters();
                if ("None".equals(pr) || "Default Printer".equalsIgnoreCase(pr) || !available.contains(pr)) {
                    pr = printService.getDefaultPrinterName();
                }

                final String finalPrinter = pr;
                int cp = (rule != null) ? rule.getCopies() : 1;
                String ps = (rule != null) ? rule.getPaperSize() : "A4";
                boolean dx = (rule != null) && rule.isDuplex();
                boolean bk = (rule != null) && rule.isBooklet();
                String bt = (rule != null) ? rule.getBindingType() : "Left";

                Platform.runLater(() -> {
                    FileItem item = new FileItem(file, p, c, finalPrinter, dx, bk, bt, cp, ps, manualOverlay != null ? manualOverlay : "");
                    if (manualStyle != null) item.setStyle(manualStyle);
                    fileQueue.add(item);
                });
            } catch (Exception e) { logger.error("Add error", e); }
        });
    }

    private PrintRule findMatchingRule(int pages, String content) {
        for (PrintRule r : rulesList) if (r.matches(pages, content)) return r;
        return null;
    }

    private void previewFile(FileItem item, boolean isReadOnly) {
        analysisExecutor.submit(() -> {
            try {
                File f = item.getFile();
                if (item.isBooklet()) f = pdfService.createBookletPDF(f, item.getBindingType(), item.getPaperSize());
                File finalF = f;
                Platform.runLater(() -> pdfViewer.show(finalF, false, isReadOnly ? null : (sf, s, o) -> addFileToQueue(sf, s, o)));
            } catch (Exception e) { logger.error("Preview error", e); }
        });
    }

    private void saveFileAs(FileItem item) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save PDF"); fc.setInitialFileName(item.getFileName());
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File t = fc.showSaveDialog(null);
        if (t != null) {
            analysisExecutor.submit(() -> {
                try {
                    File pf = item.getFile(); List<File> temps = new ArrayList<>();
                    if (item.getOverlayText() != null && !item.getOverlayText().isEmpty()) {
                        pf = printService.applyOverlayInternal(pf, item.getOverlayText()); temps.add(pf);
                    }
                    if (item.isBooklet()) {
                        pf = pdfService.createBookletPDF(pf, item.getBindingType(), item.getPaperSize()); temps.add(pf);
                    }
                    java.nio.file.Files.copy(pf.toPath(), t.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    temps.forEach(f -> { if (f.exists()) f.delete(); });
                    updateStatus("Saved: " + t.getName());
                } catch (Exception e) { logger.error("Save error", e); }
            });
        }
    }

    private void printFile(FileItem item) {
        if ("None".equals(item.getTargetPrinter())) { updateStatus("Error: No Printer"); return; }
        activityLogger.info("Starting print job: " + item.getFileName() + " on " + item.getTargetPrinter());
        printQueueExecutor.submit(() -> {
            try {
                item.setStatus("Printing...");
                printService.printPDF(item, s -> {
                    Platform.runLater(() -> item.setStatus(s));
                    if (s.contains("Finished")) activityLogger.success("Printed: " + item.getFileName() + " on " + item.getTargetPrinter());
                });
            } catch (Exception e) { 
                Platform.runLater(() -> item.setStatus("Error"));
                activityLogger.error("Failed to print: " + item.getFileName() + " (" + e.getMessage() + ")");
            }
        });
    }

    private void printAll() {
        if (isPrintingAll) return;
        isPrintingAll = true;
        printQueueExecutor.submit(() -> {
            try {
                for (FileItem item : List.copyOf(fileQueue)) {
                    if ("None".equals(item.getTargetPrinter())) continue;
                    printService.printPDF(item, s -> Platform.runLater(() -> item.setStatus(s)));
                    Thread.sleep(1000);
                }
            } catch (Exception e) { logger.error("Batch error", e); }
            finally { isPrintingAll = false; }
        });
    }

    private VBox createSettingsView() {
        CheckBox simMode = new CheckBox("Enable Print Simulation Mode (Test Run)");
        simMode.setSelected(printService.isSimulationMode());
        simMode.setOnAction(e -> {
            printService.setSimulationMode(simMode.isSelected());
            activityLogger.info("Simulation Mode: " + (simMode.isSelected() ? "Enabled" : "Disabled"));
            updateSimulationUI();
        });

        ListView<PrintRule> ruleListV = new ListView<>(rulesList); ruleListV.setPrefHeight(180);
        
        TextField minP = new TextField(); minP.setPromptText("Min Pages"); minP.setPrefWidth(80);
        TextField maxP = new TextField(); maxP.setPromptText("Max Pages"); maxP.setPrefWidth(80);
        ComboBox<String> prn = new ComboBox<>(FXCollections.observableArrayList(printService.getAvailablePrinters())); prn.setPromptText("Printer");
        CheckBox dx = new CheckBox("Duplex");
        CheckBox bk = new CheckBox("Booklet");
        ComboBox<String> bd = new ComboBox<>(FXCollections.observableArrayList("Left", "Right")); bd.setValue("Left");
        ComboBox<String> ps = new ComboBox<>(FXCollections.observableArrayList("A4", "A3")); ps.setValue("A4");
        Spinner<Integer> cp = new Spinner<>(1, 999, 1); cp.setPrefWidth(70);

        ruleListV.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                PrintRule r = ruleListV.getSelectionModel().getSelectedItem();
                if (r != null) {
                    minP.setText(String.valueOf(r.getMinPages()));
                    maxP.setText(String.valueOf(r.getMaxPages()));
                    prn.setValue(r.getPrinterName());
                    dx.setSelected(r.isDuplex());
                    bk.setSelected(r.isBooklet());
                    bd.setValue(r.getBindingType());
                    ps.setValue(r.getPaperSize());
                    cp.getValueFactory().setValue(r.getCopies());
                }
            }
        });

        Button addRuleBtn = new Button("Add New Rule");
        addRuleBtn.setOnAction(e -> {
            try {
                PrintRule r = new PrintRule(
                    minP.getText().isEmpty() ? 0 : Integer.parseInt(minP.getText()),
                    maxP.getText().isEmpty() ? 9999 : Integer.parseInt(maxP.getText()),
                    prn.getValue(), dx.isSelected(), bk.isSelected(), bd.getValue(), "", cp.getValue(), ps.getValue()
                );
                rulesList.add(r); saveConfigs();
            } catch (Exception ex) { logger.error("Input error", ex); }
        });
        
        Button updateRuleBtn = new Button("Update Rule");
        updateRuleBtn.setOnAction(e -> {
            PrintRule sel = ruleListV.getSelectionModel().getSelectedItem();
            if (sel != null) {
                try {
                    sel.setMinPages(minP.getText().isEmpty() ? 0 : Integer.parseInt(minP.getText()));
                    sel.setMaxPages(maxP.getText().isEmpty() ? 9999 : Integer.parseInt(maxP.getText()));
                    sel.setPrinterName(prn.getValue());
                    sel.setDuplex(dx.isSelected());
                    sel.setBooklet(bk.isSelected());
                    sel.setBindingType(bd.getValue());
                    sel.setPaperSize(ps.getValue());
                    sel.setCopies(cp.getValue());
                    ruleListV.refresh();
                    saveConfigs();
                } catch (Exception ex) { logger.error("Update error", ex); }
            }
        });

        Button remRuleBtn = new Button("Remove Rule");
        remRuleBtn.setOnAction(e -> { rulesList.remove(ruleListV.getSelectionModel().getSelectedItem()); saveConfigs(); });

        HBox ruleInputs = new HBox(5, minP, maxP, prn, dx, bk, bd, ps, cp);
        ruleInputs.setPadding(new Insets(5, 0, 5, 0));
        HBox ruleBtns = new HBox(10, addRuleBtn, updateRuleBtn, remRuleBtn);

        ListView<SmartSplitRule> smartListV = new ListView<>(smartSplitRulesList); smartListV.setPrefHeight(180);
        CheckBox ssEn = new CheckBox("Enabled");
        TextField ssKw = new TextField(); ssKw.setPromptText("Keyword");
        TextField ssPreB = new TextField(); ssPreB.setPromptText("Prefix Before"); ssPreB.setPrefWidth(120);
        TextField ssPreA = new TextField(); ssPreA.setPromptText("Prefix After"); ssPreA.setPrefWidth(120);
        
        VBox smartControls = new VBox(10,
            new HBox(10, new Label("Active:"), ssEn, new Label("Target Keyword:"), ssKw),
            new HBox(10, new Label("Name Prefix (Before):"), ssPreB, new Label("Name Prefix (After):"), ssPreA)
        );

        smartListV.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                SmartSplitRule nv = smartListV.getSelectionModel().getSelectedItem();
                if (nv != null) {
                    ssEn.setSelected(nv.isEnabled());
                    ssKw.setText(nv.getKeyword());
                    ssPreB.setText(nv.getBeforePrefix());
                    ssPreA.setText(nv.getAfterPrefix());
                }
            }
        });

        Button updateSmartBtn = new Button("Update Smart Rule");
        updateSmartBtn.setOnAction(e -> {
            SmartSplitRule sel = smartListV.getSelectionModel().getSelectedItem();
            if (sel != null) {
                sel.setEnabled(ssEn.isSelected());
                sel.setKeyword(ssKw.getText());
                sel.setBeforePrefix(ssPreB.getText());
                sel.setAfterPrefix(ssPreA.getText());
                smartListV.refresh();
                saveConfigs();
            }
        });
        Button addSmartBtn = new Button("Add New Smart Rule");
        addSmartBtn.setOnAction(e -> {
            SmartSplitRule nr = new SmartSplitRule();
            nr.setKeyword(ssKw.getText().isEmpty() ? "New Keyword" : ssKw.getText());
            nr.setEnabled(ssEn.isSelected());
            nr.setBeforePrefix(ssPreB.getText());
            nr.setAfterPrefix(ssPreA.getText());
            smartSplitRulesList.add(nr);
            saveConfigs();
        });
        Button remSmartBtn = new Button("Remove Smart Rule");
        remSmartBtn.setOnAction(e -> {
            smartSplitRulesList.remove(smartListV.getSelectionModel().getSelectedItem());
            saveConfigs();
        });

        VBox layout = new VBox(15, 
            new Label("System Flags:"), simMode,
            new Separator(),
            new Label("1. Page Count Routing Rules:"), ruleListV, ruleInputs, ruleBtns,
            new Separator(),
            new Label("2. Smart Split Configuration:"), smartListV, smartControls,
            new HBox(10, addSmartBtn, updateSmartBtn, remSmartBtn)
        );
        layout.setPadding(new Insets(20));
        return layout;
    }

    private void loadJsonAndUpdateCopies(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select QP Print Job JSON");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null) return;
        
        activityLogger.info("Fetching data from JSON: " + file.getName());
        analysisExecutor.submit(() -> {
            try {
                updateStatus("Reading JSON: " + file.getName());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(file);
                int countUpdated = 0;
                if (root.isArray()) {
                    for (JsonNode node : root) {
                        String qpCode = node.path("qpCode").asText("");
                        int count = node.path("count").asInt(1);
                        if (!qpCode.isEmpty()) {
                            boolean matched = false;
                            for (FileItem item : fileQueue) {
                                String fileName = item.getFileName();
                                String extractedQP = extractQPFromFileName(fileName);
                                if (qpCode.equalsIgnoreCase(extractedQP) || fileName.contains("_" + qpCode + "_") || fileName.contains("_" + qpCode + ".")) {
                                    final int finalCount = count;
                                    Platform.runLater(() -> item.setCopies(finalCount));
                                    countUpdated++;
                                    matched = true;
                                    activityLogger.info("Updated " + fileName + " copies to " + finalCount + " (Matched QP: " + qpCode + ")");
                                }
                            }
                            if (!matched) activityLogger.error("No file found in queue for QP Code: " + qpCode);
                        }
                    }
                }
                final int finalUpdated = countUpdated;
                Platform.runLater(() -> {
                    updateStatus("Finished: Updated " + finalUpdated + " files.");
                    activityLogger.success("JSON data fetch complete. Total files updated: " + finalUpdated);
                });
            } catch (Exception e) { 
                logger.error("JSON Error", e);
                activityLogger.error("Failed to read JSON: " + e.getMessage());
            }
        });
    }

    private String extractQPFromFileName(String fileName) {
        if (fileName == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("_\\d{2}[-_:\\.]\\d{2}\\s+[AP]M_([A-Z0-9]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(fileName);
        if (m.find()) return m.group(1);
        return null;
    }

    private void updateRowStyle(TableRow<FileItem> row, String status) {
        if (status == null) row.setStyle("");
        else if (status.contains("Finished")) row.setStyle("-fx-background-color: #c8e6c9;"); 
        else if (status.contains("Error")) row.setStyle("-fx-background-color: #ffcdd2;");    
        else row.setStyle("");
    }

    private void saveConfigs() {
        if (roomGroupsList.isEmpty() && fileQueue.isEmpty() && !rulesList.isEmpty()) {
            logger.warn("Prevented saveConfigs because both queue and room groups are empty (safety check).");
            // return; // Commented out for now to see if this is the cause
        }
        logger.info("Triggering saveConfigs. Queue size: {}, Room groups: {}", fileQueue.size(), roomGroupsList.size());
        config.setRules(List.copyOf(rulesList));
        config.setSmartSplitRules(List.copyOf(smartSplitRulesList));
        config.setFileQueue(new ArrayList<>(fileQueue));
        config.setRoomGroups(new ArrayList<>(roomGroupsList));
        configManager.saveConfig(config);
    }

    private VBox createRoomRouterView(Stage stage) {
        Button uploadBtn = new Button("Upload Room Wise JSON");
        uploadBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        uploadBtn.setPrefWidth(250);
        uploadBtn.setOnAction(e -> loadRoomWiseJson(stage));

        Button clearBlocksBtn = new Button("Clear All Blocks");
        clearBlocksBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10 20; -fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
        clearBlocksBtn.setPrefWidth(200);
        clearBlocksBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Clear All Blocks");
            alert.setHeaderText("Are you sure you want to clear all room blocks?");
            alert.setContentText("This action cannot be undone.");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    roomGroupsList.clear();
                    saveConfigs();
                }
            });
        });

        TextField roomSearch = new TextField();
        roomSearch.setPromptText("Filter rooms or QP codes...");
        roomSearch.setPrefWidth(300);
        roomSearch.setStyle("-fx-font-size: 14px; -fx-padding: 10;");

        ScrollPane scrollPane = new ScrollPane();
        FlowPane flowPane = new FlowPane();
        flowPane.setPadding(new Insets(20)); flowPane.setHgap(20); flowPane.setVgap(20);
        flowPane.prefWidthProperty().bind(scrollPane.widthProperty().subtract(20));

        roomSearch.textProperty().addListener((obs, old, val) -> {
            flowPane.getChildren().clear();
            String filter = val.toLowerCase();
            roomGroupsList.stream()
                .filter(g -> g.getRoomSerial().toLowerCase().contains(filter) || 
                             g.getItems().stream().anyMatch(i -> i.getQpCode().toLowerCase().contains(filter)))
                .forEach(g -> flowPane.getChildren().add(createRoomCard(g)));
        });

        roomGroupsList.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> {
            flowPane.getChildren().clear();
            roomGroupsList.forEach(g -> flowPane.getChildren().add(createRoomCard(g)));
        });

        // Initialize the view with any already loaded room groups
        roomGroupsList.forEach(g -> flowPane.getChildren().add(createRoomCard(g)));

        scrollPane.setContent(flowPane);
        scrollPane.setFitToWidth(true);

        HBox header = new HBox(20, uploadBtn, clearBlocksBtn, roomSearch, printCoverPageCbox);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox layout = new VBox(20, header, scrollPane);
        layout.setPadding(new Insets(20));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return layout;
    }

    private VBox createRoomCard(RoomGroup group) {
        VBox card = new VBox(10);
        String defaultStyle = "-fx-background-color: white; -fx-border-color: #ddd; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 15; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 10, 0, 0, 5);";
        String finishedStyle = "-fx-background-color: #f1f8e9; -fx-border-color: #8bc34a; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 15; -fx-effect: dropshadow(three-pass-box, rgba(139,195,74,0.3), 10, 0, 0, 5);";
        card.setStyle(group.getStatus() != null && group.getStatus().contains("Finished") ? finishedStyle : defaultStyle);
        card.setPrefWidth(350);

        group.statusProperty().addListener((obs, old, val) -> {
            if (val != null && val.contains("Finished")) {
                card.setStyle(finishedStyle);
            } else {
                card.setStyle(defaultStyle);
            }
        });

        int totalQty = group.getItems().stream().mapToInt(RoomItem::getCount).sum();
        Label title = new Label("Room: " + group.getRoomSerial() + " (Total Qty: " + totalQty + ")");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setAlignment(javafx.geometry.Pos.CENTER);

        TableView<RoomItem> table = new TableView<>(group.getItems());
        table.setPrefHeight(150);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<RoomItem, String> qpCol = new TableColumn<>("QP");
        qpCol.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getDisplayName()));
        
        TableColumn<RoomItem, Integer> countCol = new TableColumn<>("Qty");
        countCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().getCount()));
        countCol.setPrefWidth(50);

        TableColumn<RoomItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(d -> d.getValue().statusProperty());

        TableColumn<RoomItem, Void> actionCol = new TableColumn<>("Action");
        actionCol.setCellFactory(tc -> new TableCell<RoomItem, Void>() {
            private final Button btn = new Button("Edit & Send");
            {
                btn.setStyle("-fx-font-size: 10px; -fx-padding: 2 5; -fx-background-color: #ff9800; -fx-text-fill: white;");
                btn.setOnAction(e -> {
                    RoomItem item = getTableRow().getItem();
                    if (item != null) {
                        TextInputDialog dialog = new TextInputDialog(String.valueOf(item.getCount()));
                        dialog.setTitle("Edit Quantity");
                        dialog.setHeaderText("Update quantity for QP: " + item.getQpCode());
                        dialog.setContentText("Enter new quantity:");
                        dialog.showAndWait().ifPresent(v -> {
                            try {
                                int newQty = Integer.parseInt(v);
                                item.setCount(newQty);
                                printSingleRoomItem(item, group.getSelectedPrinter());
                            } catch (Exception ex) { }
                        });
                    }
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null); else setGraphic(btn);
            }
        });

        table.getColumns().addAll(qpCol, countCol, statusCol, actionCol);

        table.setRowFactory(tv -> {
            TableRow<RoomItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) previewRoomItem(row.getItem());
            });
            return row;
        });

        ComboBox<String> printerCombo = new ComboBox<>(FXCollections.observableArrayList(printService.getAvailablePrinters()));
        printerCombo.setPromptText("Select Printer");
        printerCombo.setMaxWidth(Double.MAX_VALUE);
        printerCombo.valueProperty().bindBidirectional(group.selectedPrinterProperty());

        Button sendBtn = new Button("Send Print");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        sendBtn.setOnAction(e -> printRoom(group));

        Label roomStatus = new Label();
        roomStatus.textProperty().bind(group.statusProperty());
        roomStatus.setStyle("-fx-font-style: italic;");

        Label printerIndicator = new Label();
        printerIndicator.setStyle("-fx-font-size: 10px; -fx-text-fill: #777;");
        group.selectedPrinterProperty().addListener((o, ov, nv) -> {
            if (nv != null && printerStatusCache.containsKey(nv)) {
                String s = printerStatusCache.get(nv);
                printerIndicator.setText("Status: " + s);
                printerIndicator.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ("Ready".equalsIgnoreCase(s) ? "green" : "red") + ";");
            } else printerIndicator.setText("");
        });

        card.getChildren().addAll(title, table, printerCombo, printerIndicator, sendBtn, roomStatus);
        return card;
    }

    private VBox createAboutView() {
        VBox layout = new VBox(20); layout.setPadding(new Insets(30)); layout.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        Label title = new Label("Smart QP Print Manager v3.0.2");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #2196F3;");
        Label createdBy = new Label("Created by Magnolia for Examination Management");
        createdBy.setStyle("-fx-font-size: 16px; -fx-font-weight: normal; -fx-text-fill: #555;");

        TabPane helpPane = new TabPane(); helpPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); helpPane.setPrefHeight(400);

        String overview = "OVERVIEW:\nA specialized utility for high-volume automated question paper printing.\n\n" +
            "1. PRINT QUEUE: Add PDFs for general batch printing.\n2. ROOM ROUTER: Automatically group and print papers per room.\n" +
            "3. PRINTER DASHBOARD: Monitor real-time status.\n4. ACTIVITY LOGS: Audit every action.";

        String roomLogic = "ROOM WISE ROUTING:\n- Requires JSON with 'roomSerial', 'qpCode', and 'count'.\n- Matches qpCode against Print Queue.\n- Split parts (Main/MCQ) appear independently.";

        helpPane.getTabs().add(new Tab("Overview", new ScrollPane(new Label(overview) {{ setPadding(new Insets(10)); setWrapText(true); }})));
        helpPane.getTabs().add(new Tab("Room Router", new ScrollPane(new Label(roomLogic) {{ setPadding(new Insets(10)); setWrapText(true); }})));

        layout.getChildren().addAll(title, createdBy, new Separator(), helpPane);
        return layout;
    }

    private void previewRoomItem(RoomItem item) {
        if (item.getMatchedFile() == null) { updateStatus("No matched file to preview."); return; }
        previewFile(item.getMatchedFile(), true);
    }

    private void printSingleRoomItem(RoomItem roomItem, String selectedPrinter) {
        if ("None".equals(selectedPrinter) || selectedPrinter == null) { updateStatus("Error: Select a printer."); return; }
        roomItem.setStatus("Sending...");
        roomPrintExecutor.submit(() -> {
            try {
                if (roomItem.getMatchedFile() == null) { Platform.runLater(() -> roomItem.setStatus("File Not Found")); return; }
                FileItem fileItem = roomItem.getMatchedFile();
                FileItem jobItem = new FileItem(fileItem.getFile(), fileItem.getPageCount(), fileItem.getContent(), selectedPrinter, fileItem.isDuplex(), fileItem.isBooklet(), fileItem.getBindingType(), roomItem.getCount(), fileItem.getPaperSize(), fileItem.getOverlayText());
                jobItem.setFileName(fileItem.getFileName()); jobItem.setStyle(fileItem.getStyle());
                printService.printPDF(jobItem, s -> Platform.runLater(() -> roomItem.setStatus(s)));
            } catch (Exception e) { Platform.runLater(() -> roomItem.setStatus("Error")); }
        });
    }

    private void loadRoomWiseJson(Stage stage) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Room JSON");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null) return;

        activityLogger.info("Loading Room Seating JSON: " + file.getName());
        analysisExecutor.submit(() -> {
            try {
                updateStatus("Loading Room JSON...");
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(file);
                Map<String, RoomGroup> groups = new LinkedHashMap<>();
                int matchedItems = 0;

                if (root.isArray()) {
                    for (JsonNode node : root) {
                        String roomSerial = node.path("roomSerial").asText("Unknown");
                        String qpCode = node.path("qpCode").asText("");
                        String pdfFileName = node.path("pdfFileName").asText("");
                        String courseName = node.path("courseName").asText(pdfFileName);
                        int count = node.path("count").asInt(0);
                        
                        RoomGroup group = groups.computeIfAbsent(roomSerial, RoomGroup::new);
                        boolean matched = false;
                        for (FileItem fileItem : fileQueue) {
                            String fileName = fileItem.getFileName();
                            String extractedQP = extractQPFromFileName(fileName);
                            if (qpCode.equalsIgnoreCase(extractedQP) || fileName.contains("_" + qpCode + "_") || fileName.contains("_" + qpCode + ".")) {
                                RoomItem roomItem = new RoomItem(roomSerial, qpCode, pdfFileName, count);
                                roomItem.setCourseName(courseName);
                                roomItem.setMatchedFile(fileItem);
                                group.getItems().add(roomItem);
                                matched = true;
                                matchedItems++;
                                activityLogger.info("Room " + roomSerial + ": Matched QP " + qpCode + " (" + fileName + ")");
                            }
                        }
                        if (!matched) {
                            RoomItem roomItem = new RoomItem(roomSerial, qpCode, pdfFileName, count);
                            roomItem.setCourseName(courseName);
                            group.getItems().add(roomItem);
                            activityLogger.error("Room " + roomSerial + ": File NOT FOUND for QP " + qpCode);
                        }
                    }
                }
                final int finalMatched = matchedItems;
                final int finalGroupSize = groups.size();
                Platform.runLater(() -> {
                    roomGroupsList.setAll(groups.values());
                    updateStatus("Loaded " + finalGroupSize + " rooms.");
                    activityLogger.success("Room Routing setup complete. " + finalGroupSize + " rooms created, " + finalMatched + " files matched.");
                });
            } catch (Exception e) { 
                updateStatus("Error loading JSON."); 
                activityLogger.error("Failed to load Room JSON: " + e.getMessage());
            }
        });
    }

    private void setupRoomGroupListeners(RoomGroup g) {
        g.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c -> {
            saveConfigs();
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(this::attachRoomItemListeners);
                }
            }
        });
        g.getItems().forEach(this::attachRoomItemListeners);
    }

    private void attachRoomItemListeners(RoomItem i) {
        i.statusProperty().addListener((o, ov, nv) -> saveConfigs());
        i.countProperty().addListener((o, ov, nv) -> saveConfigs());
    }

    private void printRoom(RoomGroup group) {
        if ("None".equals(group.getSelectedPrinter()) || group.getSelectedPrinter() == null) { updateStatus("Error: Select a printer."); return; }
        boolean printCover = printCoverPageCbox.isSelected();
        group.setStatus("Sending...");
        saveConfigs(); // Force save on status change
        roomPrintExecutor.submit(() -> {
            try {
                // 1. Optional Cover Page Generation
                logger.info("printCover is {}", printCover);
                if (printCover) {
                    try {
                        File coverFile = generateRoomCoverPage(group);
                        FileItem coverItem = new FileItem(coverFile, 1, "", group.getSelectedPrinter(), false, false, "Left", 1, "A4", "");
                        coverItem.setFileName("Cover_Room_" + group.getRoomSerial());
                        activityLogger.info("Generating Cover Page for Room: " + group.getRoomSerial());
                        printService.printPDF(coverItem, s -> {});
                        Thread.sleep(1000);
                    } catch (Exception ce) { 
                        logger.error("Failed to generate cover page", ce);
                        activityLogger.error("Failed to generate cover page: " + ce.getMessage()); 
                    }
                }

                // 2. Print actual QP items
                for (RoomItem roomItem : group.getItems()) {
                    if (roomItem.getMatchedFile() == null) { roomItem.setStatus("File Not Found"); continue; }
                    roomItem.setStatus("Printing...");
                    FileItem fileItem = roomItem.getMatchedFile();
                    FileItem jobItem = new FileItem(fileItem.getFile(), fileItem.getPageCount(), fileItem.getContent(), group.getSelectedPrinter(), fileItem.isDuplex(), fileItem.isBooklet(), fileItem.getBindingType(), roomItem.getCount(), fileItem.getPaperSize(), fileItem.getOverlayText());
                    jobItem.setFileName(fileItem.getFileName()); jobItem.setStyle(fileItem.getStyle());
                    printService.printPDF(jobItem, s -> Platform.runLater(() -> roomItem.setStatus(s)));
                    Thread.sleep(300);
                }
                Platform.runLater(() -> {
                    group.setStatus("Finished");
                    saveConfigs();
                });
            } catch (Exception e) { 
                Platform.runLater(() -> {
                    group.setStatus("Error");
                    saveConfigs();
                }); 
            }
        });
    }

    private File generateRoomCoverPage(RoomGroup group) throws Exception {
        File temp = File.createTempFile("room_cover_", ".pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            doc.addPage(page);
            
            float margin = 50;
            float width = page.getMediaBox().getWidth() - 2 * margin;
            float yStart = page.getMediaBox().getHeight() - margin;
            float tableWidth = width;
            float rowHeight = 25f;
            float cellMargin = 5f;

            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                // Title
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD), 36);
                cs.newLineAtOffset(margin, yStart - 40);
                String safeRoomSerial = group.getRoomSerial() != null ? group.getRoomSerial().replaceAll("[^\\x00-\\x7F]", "") : "";
                cs.showText("ROOM: " + safeRoomSerial);
                cs.endText();

                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 14);
                cs.newLineAtOffset(margin, yStart - 70);
                cs.showText("Smart QP Print Manager - Routing Summary");
                cs.endText();

                float yPosition = yStart - 100;
                float[] colWidths = {40, 100, 250, 80}; // S.No, QP Code, Course Name, Qty
                String[] headers = {"S.No", "QP Code", "Course Name / File", "Qty"};

                // Draw Table Header
                drawTableRow(cs, margin, yPosition, colWidths, headers, true);
                yPosition -= rowHeight;

                // Draw Table Rows
                int sNo = 1;
                for (RoomItem item : group.getItems()) {
                    String[] rowData = {
                        String.valueOf(sNo++),
                        item.getDisplayName(),
                        item.getCourseName() != null ? item.getCourseName() : item.getPdfFileName(),
                        String.valueOf(item.getCount())
                    };
                    drawTableRow(cs, margin, yPosition, colWidths, rowData, false);
                    yPosition -= rowHeight;
                    if (yPosition < margin + 50) break; // Overflow check
                }

                // Footer
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_OBLIQUE), 10);
                cs.newLineAtOffset(margin, margin);
                cs.showText("Printed on: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
                cs.endText();
            }
            doc.save(temp);
        }
        return temp;
    }

    private void drawTableRow(org.apache.pdfbox.pdmodel.PDPageContentStream cs, float x, float y, float[] widths, String[] data, boolean isHeader) throws Exception {
        float height = 25f;
        float fontSize = isHeader ? 12 : 11;
        org.apache.pdfbox.pdmodel.font.PDFont font = isHeader ? 
            new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD) :
            new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);

        float curX = x;
        for (int i = 0; i < widths.length; i++) {
            // Draw border
            cs.setLineWidth(1f);
            cs.setStrokingColor(0f, 0f, 0f);
            cs.addRect(curX, y - height, widths[i], height);
            cs.stroke();

            // Draw background for header
            if (isHeader) {
                cs.setNonStrokingColor(0.9f, 0.9f, 0.9f);
                cs.addRect(curX + 0.5f, y - height + 0.5f, widths[i] - 1f, height - 1f);
                cs.fill();
            }

            // Draw text centered
            cs.setNonStrokingColor(0f, 0f, 0f);
            cs.beginText();
            cs.setFont(font, fontSize);
            
            String text = data[i] != null ? data[i] : "";
            // Sanitize text for standard Type1 PDF fonts (ASCII only) to prevent IllegalArgumentException
            text = text.replaceAll("[^\\x00-\\x7F]", "");
            
            // Simple text truncation for width
            float textWidth = font.getStringWidth(text) / 1000 * fontSize;
            while (textWidth > widths[i] - 10 && text.length() > 3) {
                text = text.substring(0, text.length() - 4) + "...";
                textWidth = font.getStringWidth(text) / 1000 * fontSize;
            }

            float textX = curX + (widths[i] - textWidth) / 2;
            float textY = y - height + (height - fontSize) / 2 + 2;
            cs.newLineAtOffset(textX, textY);
            cs.showText(text);
            cs.endText();

            curX += widths[i];
        }
    }

    public static void main(String[] args) { launch(args); }
}
