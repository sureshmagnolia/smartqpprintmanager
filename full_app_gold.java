package com.printmanager;

import com.printmanager.model.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.embed.swing.SwingNode;
import org.cef.CefApp;
import org.cef.CefClient;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefDownloadHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.callback.CefQueryCallback;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.EnumProgress;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import netscape.javascript.JSObject;
import javax.swing.SwingUtilities;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.JToolBar;
import javax.swing.JPanel;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.net.ssl.*;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import java.security.cert.X509Certificate;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    private final javafx.collections.ObservableMap<String, String> printerStatusCache = FXCollections.observableHashMap();
    private final HBox simAlertHeader = new HBox();
    private final CheckBox printCoverPageCbox = new CheckBox("Print Room Status Cover Page?");

    private CefApp cefApp;
    private CefClient cefClient;
    private volatile boolean initializingCef = false;
    private volatile boolean autoOpenBrowser = false;
    private final Map<CefBrowser, JTextField> browserAddressBars = new HashMap<>();
    private final TextField urlField = new TextField("https://collegeportal.uoc.ac.in/");
    private final TextField sessionNameField = new TextField();
    private final TextField downloadPathField = new TextField();

    @Override
    public void start(Stage primaryStage) {
        config = configManager.loadConfig();
        logger.info("Application start: Loaded {} rules, {} split rules, {} queue items, {} room groups",
            config.getRules().size(), config.getSmartSplitRules().size(), config.getFileQueue().size(), config.getRoomGroups().size());

        rulesList.addAll(config.getRules());
        smartSplitRulesList.addAll(config.getSmartSplitRules());
        fileQueue.addAll(config.getFileQueue());
        roomGroupsList.addAll(config.getRoomGroups());
        
        // Initialize persistent UI fields from config
        if (config.getCollegeId() != null) urlField.setText("https://collegeportal.uoc.ac.in/"); // Reset to default just in case
        if (config.getBaseDownloadPath() != null) downloadPathField.setText(config.getBaseDownloadPath());

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
        Tab portalTab = new Tab("Exam Portals", createPortalView());
        portalTab.setClosable(false);
        tabPane.getTabs().addAll(mainTab, roomTab, printerTab, logsTab, settingsTab, aboutTab, portalTab);

        simAlertHeader.setId("simulation-alert");
        simAlertHeader.getChildren().add(new Label("ΓÜá SIMULATION MODE ACTIVE: Actual printing is disabled. Change this in Settings."));
        simAlertHeader.setManaged(false);
        simAlertHeader.setVisible(false);

        VBox root = new VBox(simAlertHeader, tabPane, createStatusBarView());
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 1200, 850);
        try {
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } catch (Exception e) { logger.warn("Could not load CSS"); }
        
        primaryStage.setTitle("Smart QP Print Manager v3.1.3");
        
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
        // Immediate initial poll
        analysisExecutor.submit(() -> {
            try {
                Map<String, String> initial = printService.getPrintersStatus();
                Platform.runLater(() -> printerStatusCache.putAll(initial));
            } catch (Exception e) { logger.error("Initial poll error", e); }
        });

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
        try {
            saveConfigs();
            analysisExecutor.shutdownNow();
            printQueueExecutor.shutdownNow();
            roomPrintExecutor.shutdownNow();
            
            if (cefApp != null) {
                cefApp.dispose();
            }
            
            activityLogger.info("Application stopping. Cleaning up all background instances...");
            
            // 1. Kill the standard helper name
            Runtime.getRuntime().exec("taskkill /F /IM jcef_helper.exe /T");
            
            // 2. Kill any processes named after the app itself (which helper processes often inherit)
            // We use a small delay to ensure this process has finished its own cleanup first
            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    Runtime.getRuntime().exec("taskkill /F /IM \"Smart QP Print Manager.exe\" /T");
                } catch (Exception e) {}
            }).start();
            
            activityLogger.info("Application stopped");
        } catch (Exception e) {
            logger.error("Error during shutdown cleanup", e);
        }
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
            {
                spinner.setPrefWidth(70);
                spinner.setEditable(true);
                
                // Commit value on focus lost or enter
                spinner.focusedProperty().addListener((obs, oldVal, newVal) -> {
                    if (!newVal) commitSpinnerValue();
                });
                
                spinner.valueProperty().addListener((o, ov, nv) -> {
                    if (getTableRow().getItem() != null) getTableRow().getItem().setCopies(nv);
                });
            }

            private void commitSpinnerValue() {
                try {
                    String text = spinner.getEditor().getText();
                    javafx.util.StringConverter<Integer> converter = spinner.getValueFactory().getConverter();
                    if (converter != null) {
                        Integer value = converter.fromString(text);
                        spinner.getValueFactory().setValue(value);
                    }
                } catch (Exception e) {
                    // Reset to current value on error
                    spinner.getEditor().setText(spinner.getValueFactory().getConverter().toString(spinner.getValue()));
                }
            }

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
        printerCol.setMinWidth(180);
        printerCol.setCellFactory(tc -> new TableCell<FileItem, String>() {
            private final ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList(printService.getAvailablePrinters()));
            private final Label statusIndicator = new Label();
            private final VBox container = new VBox(2, combo, statusIndicator);
            
            {
                combo.setPrefWidth(160);
                statusIndicator.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
                
                combo.setCellFactory(lv -> new ListCell<String>() {
                    @Override protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) setText(null);
                        else {
                            String status = printerStatusCache.getOrDefault(item, "Ready");
                            if ("Offline".equalsIgnoreCase(status)) {
                                setText(item + " (OFFLINE)");
                                setStyle("-fx-text-fill: #aaa;");
                                setDisable(true);
                            } else {
                                setText(item);
                                setStyle("-fx-text-fill: black;");
                                setDisable(false);
                            }
                        }
                    }
                });
                // Button cell for closed state
                combo.setButtonCell(combo.getCellFactory().call(null));
                
                combo.setOnAction(e -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        getTableRow().getItem().setTargetPrinter(combo.getValue());
                    }
                });

                // Centralized listener to update row indicator and combo state
                printerStatusCache.addListener((javafx.collections.MapChangeListener<String, String>) change -> updateDisplay());
            }

            private void updateDisplay() {
                String val = combo.getValue();
                if (val != null && !"None".equals(val)) {
                    String s = printerStatusCache.getOrDefault(val, "Ready");
                    Platform.runLater(() -> {
                        if ("Offline".equalsIgnoreCase(s)) {
                            statusIndicator.setText("ΓÜá OFFLINE");
                            statusIndicator.setStyle("-fx-text-fill: #f44336;");
                        } else {
                            statusIndicator.setText("Γ£ö Ready");
                            statusIndicator.setStyle("-fx-text-fill: #4CAF50;");
                        }
                    });
                } else {
                    Platform.runLater(() -> statusIndicator.setText(""));
                }
            }

            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) setGraphic(null);
                else { 
                    combo.setValue(item); 
                    updateDisplay();
                    setGraphic(container); 
                    setAlignment(javafx.geometry.Pos.CENTER); 
                }
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

        Button fetchExamflowBtn = new Button("\u2601 Fetch from Examflow");
        fetchExamflowBtn.setId("fetch-examflow-btn");
        fetchExamflowBtn.setTooltip(new Tooltip("Fetch seating data directly from the cloud using your College ID"));
        fetchExamflowBtn.setOnAction(e -> fetchFromExamflow(stage));
        fetchExamflowBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");

        HBox btns = new HBox(15, addBtn, loadJsonBtn, fetchExamflowBtn, printBtn, clearBtn);
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
        private final PrinterHealthMonitor monitor;

        public PrinterDisplay(String name, String status) {
            this.name = name;
            this.status = new SimpleStringProperty(status);
            this.activeJobs = new SimpleStringProperty("0");
            this.currentTask = new SimpleStringProperty("Idle");
            this.monitor = new PrinterHealthMonitor(name);
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

        public PrinterHealthMonitor getMonitor() { return monitor; }
        public StringProperty healthStatusProperty() { return monitor.healthStatusProperty(); }
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

        TableColumn<PrinterDisplay, String> healthCol = new TableColumn<>("Hardware Health");
        healthCol.setCellValueFactory(d -> d.getValue().healthStatusProperty());
        healthCol.setCellFactory(tc -> new TableCell<PrinterDisplay, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else {
                    setText(item); setAlignment(javafx.geometry.Pos.CENTER);
                    if ("Ready".equalsIgnoreCase(item) || "No Error".equalsIgnoreCase(item)) setStyle("-fx-text-fill: green; -fx-font-weight: bold;");
                    else if (item.contains("Low")) setStyle("-fx-text-fill: orange; -fx-font-weight: bold;");
                    else if (item.contains("Jam") || item.contains("No Paper") || item.contains("Open") || item.contains("Error")) setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: #555;");
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

        table.getColumns().addAll(nameCol, statusCol, healthCol, jobsCol, taskCol);

        printService.getAvailablePrinters().stream()
            .filter(n -> !"None".equals(n))
            .forEach(n -> {
                PrinterDisplay pd = new PrinterDisplay(n, "Checking...");
                printerDisplays.add(pd);
                pd.getMonitor().start();
            });

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
        if (!file.getName().toLowerCase().endsWith(".pdf")) return;
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
                    int afterCountWithKeyword = total - pageIdx + 1;
                    
                    // Logic refinement: Use configurable thresholds (e.g., "5,9")
                    boolean shouldSkip = false;
                    String thresholds = matched.getSkipThresholds();
                    if (thresholds != null && !thresholds.isEmpty()) {
                        String[] parts = thresholds.split(",");
                        for (String p : parts) {
                            if (p.trim().equals(String.valueOf(afterCountWithKeyword))) {
                                shouldSkip = true;
                                break;
                            }
                        }
                    }
                    
                    int startPage = shouldSkip ? pageIdx + 1 : pageIdx;
                    if (startPage <= total) {
                        File a = pdfService.splitPages(file, startPage, total);
                        routeSmartPart(a, file.getName(), matched, true);
                    }
                } else {
                    addFileToQueue(file, null, null);
                }
            } catch (Exception e) { 
                logger.error("Error processing file: " + file.getName(), e); 
                activityLogger.error("Failed to analyze: " + file.getName());
            }
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
                overlay = rule.getAfterOverlayText(); 
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
        
        // Prevent printing to Offline printers
        String liveStatus = printerStatusCache.getOrDefault(item.getTargetPrinter(), "Ready");
        if ("Offline".equalsIgnoreCase(liveStatus)) {
            activityLogger.error("Blocked print: " + item.getFileName() + " (Printer " + item.getTargetPrinter() + " is OFFLINE)");
            updateStatus("Error: Printer is Offline");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Printer Offline");
                alert.setHeaderText("Cannot print to " + item.getTargetPrinter());
                alert.setContentText("The printer is currently Offline. Please turn it on and wait for the dashboard to show 'Ready'.");
                alert.show();
            });
            return;
        }

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
                    // Skip offline printers in batch
                    if ("Offline".equalsIgnoreCase(printerStatusCache.getOrDefault(item.getTargetPrinter(), "Ready"))) {
                        Platform.runLater(() -> item.setStatus("Skipped (Offline)"));
                        continue;
                    }
                    printService.printPDF(item, s -> Platform.runLater(() -> item.setStatus(s)));
                    Thread.sleep(1000);
                }
            } catch (Exception e) { logger.error("Batch error", e); }
            finally { isPrintingAll = false; }
        });
    }

    private javafx.scene.Parent createSettingsView() {
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
        
        TextField ssOverlay = new TextField(); ssOverlay.setPromptText("Overlay Text (e.g. MCQ)"); ssOverlay.setPrefWidth(120);
        TextField ssSkipThr = new TextField(); ssSkipThr.setPromptText("Skip if after pages are (e.g. 5,9)"); ssSkipThr.setPrefWidth(180);
        
        ComboBox<String> ssB1 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 
        ComboBox<String> ssB2 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 
        ComboBox<String> ssB3 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 
        
        ComboBox<String> ssA1 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 
        ComboBox<String> ssA2 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 
        ComboBox<String> ssA3 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 
        ComboBox<String> ssA6 = new ComboBox<>(FXCollections.observableArrayList("Simplex", "Duplex", "Booklet")); 

        VBox smartControls = new VBox(15,
            new HBox(15, new Label("Active:"), ssEn, new Label("Target Keyword:"), ssKw),
            new HBox(15, new Label("Page Skip Logic:"), new Label("Skip Keyword Page only if 'After' part has exactly these page counts (comma separated):"), ssSkipThr),
            new HBox(15, new Label("Name Prefix:"), new Label("Before:"), ssPreB, new Label("After:"), ssPreA, new Label("After Overlay:"), ssOverlay),
            new VBox(5, 
                new Label("Style Mapping for 'Before' Split:"),
                new HBox(10, new Label("If 1 Page:"), ssB1, new Label("If 2 Pages:"), ssB2, new Label("If 3+ Pages:"), ssB3)
            ),
            new VBox(5, 
                new Label("Style Mapping for 'After' Split:"),
                new HBox(10, new Label("If 1 Page:"), ssA1, new Label("If 2 Pages:"), ssA2, new Label("If 3-4 Pages:"), ssA3, new Label("If 6+ Pages:"), ssA6)
            ),
            new Label("Note: If 'After' part is exactly 5 pages, it triggers the 'Special MCQ Mode' (Splits at page 2, adds overlay).")
        );

        smartListV.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                SmartSplitRule nv = smartListV.getSelectionModel().getSelectedItem();
                if (nv != null) {
                    ssEn.setSelected(nv.isEnabled());
                    ssKw.setText(nv.getKeyword());
                    ssPreB.setText(nv.getBeforePrefix());
                    ssPreA.setText(nv.getAfterPrefix());
                    ssOverlay.setText(nv.getAfterOverlayText());
                    ssSkipThr.setText(nv.getSkipThresholds());
                    ssB1.setValue(nv.getBeforeStyle1());
                    ssB2.setValue(nv.getBeforeStyle2());
                    ssB3.setValue(nv.getBeforeStyle3Plus());
                    ssA1.setValue(nv.getAfterStyle1());
                    ssA2.setValue(nv.getAfterStyle2());
                    ssA3.setValue(nv.getAfterStyle3To4());
                    ssA6.setValue(nv.getAfterStyle6Plus());
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
                sel.setAfterOverlayText(ssOverlay.getText());
                sel.setSkipThresholds(ssSkipThr.getText());
                sel.setBeforeStyle1(ssB1.getValue());
                sel.setBeforeStyle2(ssB2.getValue());
                sel.setBeforeStyle3Plus(ssB3.getValue());
                sel.setAfterStyle1(ssA1.getValue());
                sel.setAfterStyle2(ssA2.getValue());
                sel.setAfterStyle3To4(ssA3.getValue());
                sel.setAfterStyle6Plus(ssA6.getValue());
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
            nr.setAfterOverlayText(ssOverlay.getText());
            nr.setSkipThresholds(ssSkipThr.getText().isEmpty() ? "5,9" : ssSkipThr.getText());
            nr.setBeforeStyle1(ssB1.getValue() != null ? ssB1.getValue() : "Simplex");
            nr.setBeforeStyle2(ssB2.getValue() != null ? ssB2.getValue() : "Duplex");
            nr.setBeforeStyle3Plus(ssB3.getValue() != null ? ssB3.getValue() : "Booklet");
            nr.setAfterStyle1(ssA1.getValue() != null ? ssA1.getValue() : "Simplex");
            nr.setAfterStyle2(ssA2.getValue() != null ? ssA2.getValue() : "Duplex");
            nr.setAfterStyle3To4(ssA3.getValue() != null ? ssA3.getValue() : "Booklet");
            nr.setAfterStyle6Plus(ssA6.getValue() != null ? ssA6.getValue() : "Booklet");
            smartSplitRulesList.add(nr);
            saveConfigs();
        });
        Button remSmartBtn = new Button("Remove Smart Rule");
        remSmartBtn.setOnAction(e -> {
            smartSplitRulesList.remove(smartListV.getSelectionModel().getSelectedItem());
            saveConfigs();
        });

        TextField collegeIdField = new TextField(config.getCollegeId());
        collegeIdField.setPromptText("Examflow College ID");
        collegeIdField.setPrefWidth(300);
        Button saveCidBtn = new Button("Save ID");
        saveCidBtn.setOnAction(e -> {
            config.setCollegeId(collegeIdField.getText().trim());
            saveConfigs();
            activityLogger.info("Examflow College ID updated to: " + config.getCollegeId());
        });

        VBox layout = new VBox(15, 
            new Label("System Flags:"), simMode,
            new Separator(),
            new Label("Examflow Cloud Sync:"),
            new HBox(10, new Label("College ID:"), collegeIdField, saveCidBtn),
            new Separator(),
            new Label("1. Page Count Routing Rules:"), ruleListV, ruleInputs, ruleBtns,
            new Separator(),
            new Label("2. Smart Split Configuration:"), smartListV, smartControls,
            new HBox(10, addSmartBtn, updateSmartBtn, remSmartBtn)
        );
        layout.setPadding(new Insets(20));
        
        ScrollPane sp = new ScrollPane(layout);
        sp.setFitToWidth(true);
        return sp;
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

    private void fetchFromExamflow(Stage stage) {
        String cid = config.getCollegeId();
        if (cid == null || cid.isEmpty()) {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle("College ID Required");
            dialog.setHeaderText("Enter your Examflow College ID");
            dialog.setContentText("Please provide the unique ID found in your Examflow URL:");
            Optional<String> result = dialog.showAndWait();
            if (result.isPresent() && !result.get().trim().isEmpty()) {
                cid = result.get().trim();
                config.setCollegeId(cid);
                saveConfigs();
            } else {
                return;
            }
        }

        final String finalCid = cid;
        activityLogger.info("Attempting to fetch data from Examflow Cloud (College ID: " + finalCid + ")");
        updateStatus("Connecting to Examflow Cloud...");

        analysisExecutor.submit(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                String url = "https://firestore.googleapis.com/v1/projects/examflow-india/databases/(default)/documents/public_print_queue/" + finalCid;
                
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(response.body());
                    
                    // Firestore REST API returns fields in a nested structure
                    String payload = root.path("fields").path("payload").path("stringValue").asText("");
                    
                    if (payload.isEmpty()) {
                        throw new Exception("Document found but payload is empty.");
                    }

                    JsonNode seatingData = mapper.readTree(payload);
                    processSeatingJson(seatingData);
                    processRoomWiseJson(seatingData, "Examflow Cloud");

                } else if (response.statusCode() == 404) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("Data Not Found");
                        alert.setHeaderText("No data found for College ID: " + finalCid);
                        alert.setContentText("Did you click 'Sync to Print Manager' in the Examflow web app first?");
                        alert.show();
                        
                        // Clear invalid ID so user can re-enter
                        config.setCollegeId("");
                        saveConfigs();
                    });
                } else {
                    throw new Exception("HTTP Error: " + response.statusCode());
                }

            } catch (Exception e) {
                logger.error("Fetch Error", e);
                activityLogger.error("Examflow Sync Failed: " + e.getMessage());
                updateStatus("Sync Failed: " + e.getMessage());
            }
        });
    }

    private void processSeatingJson(JsonNode root) {
        if (fileQueue.isEmpty()) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Queue Empty");
                alert.setHeaderText("No files in Print Queue");
                alert.setContentText("Please add your PDF files to the 'Print Queue' tab first, then click Fetch. The app will automatically match the files with the cloud data.");
                alert.show();
            });
            updateStatus("Sync Skipped: Queue is empty.");
            return;
        }

        Map<String, Integer> qpTotalCounts = new HashMap<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                String qpCode = node.path("qpCode").asText("");
                int count = node.path("count").asInt(0);
                if (!qpCode.isEmpty()) {
                    qpTotalCounts.put(qpCode, qpTotalCounts.getOrDefault(qpCode, 0) + count);
                }
            }
        }

        int countUpdated = 0;
        for (Map.Entry<String, Integer> entry : qpTotalCounts.entrySet()) {
            String qpCode = entry.getKey();
            int totalCount = entry.getValue();
            
            for (FileItem item : fileQueue) {
                String fileName = item.getFileName();
                String extractedQP = extractQPFromFileName(fileName);
                if (qpCode.equalsIgnoreCase(extractedQP) || fileName.contains("_" + qpCode + "_") || fileName.contains("_" + qpCode + ".")) {
                    final int finalCount = totalCount;
                    Platform.runLater(() -> item.setCopies(finalCount));
                    countUpdated++;
                    activityLogger.info("Synced " + fileName + ": set copies to " + totalCount + " (Matched QP: " + qpCode + ")");
                }
            }
        }

        final int finalUpdated = countUpdated;
        Platform.runLater(() -> {
            updateStatus("Finished: Synced " + finalUpdated + " files.");
            activityLogger.success("Examflow cloud fetch complete. Total files updated: " + finalUpdated);
            
            if (finalUpdated == 0) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("No Matches Found");
                alert.setHeaderText("Synced 0 files");
                alert.setContentText("Cloud data was fetched, but no files in your queue matched the QP codes in the cloud data.\n\nEnsure your filenames contain the QP codes (e.g., '_143812_').");
                alert.show();
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
        printerCombo.setCellFactory(lv -> new ListCell<String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else {
                    String status = printerStatusCache.getOrDefault(item, "Ready");
                    if ("Offline".equalsIgnoreCase(status)) {
                        setText(item + " (OFFLINE)");
                        setStyle("-fx-text-fill: #aaa;");
                        setDisable(true);
                    } else {
                        setText(item);
                        setStyle("-fx-text-fill: black;");
                        setDisable(false);
                    }
                }
            }
        });
        // Also update the button cell (the one shown when the combo is closed)
        printerCombo.setButtonCell(printerCombo.getCellFactory().call(null));
        
        printerCombo.valueProperty().bindBidirectional(group.selectedPrinterProperty());

        Button sendBtn = new Button("Send Print");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        sendBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        sendBtn.setOnAction(e -> printRoom(group));

        Label roomStatus = new Label();
        roomStatus.textProperty().bind(group.statusProperty());
        roomStatus.setStyle("-fx-font-style: italic;");

        Label printerIndicator = new Label();
        printerIndicator.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        
        // Listen to printer selection AND global status cache changes
        Runnable updateBtnState = () -> {
            String p = group.getSelectedPrinter();
            if (p != null && !"None".equals(p)) {
                String s = printerStatusCache.getOrDefault(p, "Ready");
                Platform.runLater(() -> {
                    if ("Offline".equalsIgnoreCase(s)) {
                        printerIndicator.setText("ΓÜá PRINTER OFFLINE");
                        printerIndicator.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                        sendBtn.setDisable(true);
                        sendBtn.setTooltip(new Tooltip("Cannot print while printer is Offline"));
                    } else {
                        printerIndicator.setText("Γ£ö Printer Ready");
                        printerIndicator.setStyle("-fx-text-fill: #4CAF50;");
                        sendBtn.setDisable(false);
                        sendBtn.setTooltip(null);
                    }
                });
            } else {
                Platform.runLater(() -> {
                    printerIndicator.setText("");
                    sendBtn.setDisable(false);
                });
            }
        };

        group.selectedPrinterProperty().addListener((o, ov, nv) -> updateBtnState.run());
        // Periodic check to ensure button state stays in sync with background polls
        Thread btnWatcher = new Thread(() -> {
            while (true) {
                try { Thread.sleep(2000); updateBtnState.run(); } 
                catch (InterruptedException e) { break; }
            }
        });
        btnWatcher.setDaemon(true);
        btnWatcher.start();

        card.getChildren().addAll(title, table, printerCombo, printerIndicator, sendBtn, roomStatus);
        return card;
    }

    private javafx.scene.Parent createAboutView() {
        VBox layout = new VBox(20); layout.setPadding(new Insets(30)); layout.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        Label title = new Label("Smart QP Print Manager v3.1.3");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #2196F3;");
        Label createdBy = new Label("Created by Magnolia for Examination Management");
        createdBy.setStyle("-fx-font-size: 16px; -fx-font-weight: normal; -fx-text-fill: #555;");

        VBox configInfo = new VBox(5);
        configInfo.setAlignment(javafx.geometry.Pos.CENTER);
        Label configLabel = new Label("Configuration Path:");
        configLabel.setStyle("-fx-font-weight: bold;");
        TextField pathField = new TextField(configManager.getConfigPath());
        pathField.setEditable(false);
        pathField.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #ddd; -fx-alignment: center;");
        pathField.setMaxWidth(800);
        configInfo.getChildren().addAll(configLabel, pathField);

        TabPane helpPane = new TabPane(); helpPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE); helpPane.setPrefHeight(400);

        String overview = "OVERVIEW:\nA specialized utility for high-volume automated question paper printing.\n\n" +
            "1. PRINT QUEUE: Add PDFs for general batch printing.\n2. ROOM ROUTER: Automatically group and print papers per room.\n" +
            "3. PRINTER DASHBOARD: Monitor real-time status.\n4. ACTIVITY LOGS: Audit every action.";

        String roomLogic = "ROOM WISE ROUTING:\n- Requires JSON with 'roomSerial', 'qpCode', and 'count'.\n- Matches qpCode against Print Queue.\n- Split parts (Main/MCQ) appear independently.";

        helpPane.getTabs().add(new Tab("Overview", new ScrollPane(new Label(overview) {{ setPadding(new Insets(10)); setWrapText(true); }})));
        helpPane.getTabs().add(new Tab("Room Router", new ScrollPane(new Label(roomLogic) {{ setPadding(new Insets(10)); setWrapText(true); }})));

        layout.getChildren().addAll(title, createdBy, new Separator(), helpPane, new Separator(), configInfo);
        
        ScrollPane sp = new ScrollPane(layout);
        sp.setFitToWidth(true);
        return sp;
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
        if (file != null) processRoomWiseJsonFile(file);
    }

    private void processRoomWiseJsonFile(File file) {
        activityLogger.info("Auto-loading Seating JSON: " + file.getName());
        analysisExecutor.submit(() -> {
            try {
                updateStatus("Processing Seating JSON...");
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(file);
                processRoomWiseJson(root, file.getName());
            } catch (Exception e) { 
                updateStatus("Error loading JSON."); 
                activityLogger.error("Failed to load Room JSON: " + e.getMessage());
            }
        });
    }

    private void processRoomWiseJson(JsonNode root, String sourceName) {
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
            updateStatus("Loaded " + finalGroupSize + " rooms from " + sourceName);
            activityLogger.success("Room Routing setup complete. " + finalGroupSize + " rooms created, " + finalMatched + " files matched.");
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
        String printer = group.getSelectedPrinter();
        if (printer == null || "None".equals(printer)) { 
            updateStatus("Error: Select a printer."); 
            return; 
        }

        // 1. Prevent printing to Offline printers
        String liveStatus = printerStatusCache.getOrDefault(printer, "Ready");
        if ("Offline".equalsIgnoreCase(liveStatus)) {
            activityLogger.error("Blocked room print: " + group.getRoomSerial() + " (Printer " + printer + " is OFFLINE)");
            updateStatus("Error: Printer is Offline");
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Printer Offline");
                alert.setHeaderText("Cannot print to " + printer);
                alert.setContentText("The printer is currently Offline. Please turn it on and wait for the dashboard to show 'Ready'.");
                alert.show();
            });
            return;
        }

        boolean printCover = printCoverPageCbox.isSelected();
        group.setStatus("Sending...");
        saveConfigs();

        roomPrintExecutor.submit(() -> {
            try {
                // Track success of all items
                boolean allSuccess = true;
                
                // Optional Cover Page
                if (printCover) {
                    try {
                        File coverFile = generateRoomCoverPage(group);
                        FileItem coverItem = new FileItem(coverFile, 1, "", printer, false, false, "Left", 1, "A4", "");
                        printService.printPDF(coverItem, s -> {});
                        Thread.sleep(1000);
                    } catch (Exception ce) { 
                        activityLogger.error("Cover page failed for Room " + group.getRoomSerial());
                    }
                }

                // Print QP items
                for (RoomItem roomItem : group.getItems()) {
                    if (roomItem.getMatchedFile() == null) { 
                        Platform.runLater(() -> roomItem.setStatus("File Not Found"));
                        allSuccess = false;
                        continue; 
                    }
                    
                    roomItem.setStatus("Printing...");
                    FileItem fileItem = roomItem.getMatchedFile();
                    FileItem jobItem = new FileItem(fileItem.getFile(), fileItem.getPageCount(), fileItem.getContent(), printer, fileItem.isDuplex(), fileItem.isBooklet(), fileItem.getBindingType(), roomItem.getCount(), fileItem.getPaperSize(), fileItem.getOverlayText());
                    jobItem.setFileName(fileItem.getFileName()); jobItem.setStyle(fileItem.getStyle());
                    
                    // We need to wait for completion feedback for each item
                    final java.util.concurrent.CompletableFuture<String> jobResult = new java.util.concurrent.CompletableFuture<>();
                    printService.printPDF(jobItem, s -> {
                        Platform.runLater(() -> roomItem.setStatus(s));
                        if (s.contains("Finished") || s.contains("Error")) jobResult.complete(s);
                    });
                    
                    String result = jobResult.get(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (result.contains("Error")) allSuccess = false;
                    Thread.sleep(500);
                }

                final boolean finalSuccess = allSuccess;
                Platform.runLater(() -> {
                    group.setStatus(finalSuccess ? "Finished" : "Partial Error");
                    saveConfigs();
                    if (finalSuccess) activityLogger.success("Room " + group.getRoomSerial() + " printed successfully.");
                    else activityLogger.error("Room " + group.getRoomSerial() + " completed with ERRORS.");
                });
            } catch (Exception e) { 
                Platform.runLater(() -> { group.setStatus("Error"); saveConfigs(); });
                logger.error("Room print error", e);
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
                for (RoomItem i : group.getItems()) {
                    String[] rowData = {
                        String.valueOf(sNo++),
                        i.getDisplayName(),
                        i.getCourseName() != null ? i.getCourseName() : i.getPdfFileName(),
                        String.valueOf(i.getCount())
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

    private void initCef() {
        if (cefApp != null || initializingCef) return;
        initializingCef = true;
        
        // Move CEF initialization to a dedicated thread with slightly higher priority for stability
        Thread initThread = new Thread(() -> {
            try {
                activityLogger.info("Initializing Smart Browser Engine (Stability Guard Active)...");
                CefAppBuilder builder = new CefAppBuilder();
                // ... (rest of the builder setup remains the same)
                File projectDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
                File localBundle = new File(projectDir, "bin/chromium");
                
                // Use a robust AppData-based path for the cache to prevent permission and "ghost session" issues
                File cachePath = getResolvedCacheDir();
                if (!cachePath.exists()) cachePath.mkdirs();
                
                // FORCE CLEANUP of Chromium lock files to ensure a fresh session parenting
                try {
                    new File(cachePath, "SingletonLock").delete();
                    new File(cachePath, "SingletonCookie").delete();
                    new File(cachePath, "SingletonSocket").delete();
                } catch (Exception e) {}

                if (localBundle.exists() && localBundle.isDirectory()) builder.setInstallDir(localBundle);
                else builder.setInstallDir(new File(System.getProperty("user.home"), ".jcef-bundle"));

                // CRITICAL: Explicitly set subprocess path to jcef_helper.exe to prevent decoupling
                File installDir = localBundle.exists() ? localBundle : new File(System.getProperty("user.home"), ".jcef-bundle");
                File helper = new File(installDir, "jcef_helper.exe");
                if (helper.exists()) {
                    builder.getCefSettings().browser_subprocess_path = helper.getAbsolutePath();
                    logger.info("JCEF Subprocess path set to: " + helper.getAbsolutePath());
                }

                builder.setProgressHandler((progress, percentage) -> {
                    String status = "Chromium " + progress + (percentage >= 0 ? ": " + String.format("%.0f", percentage) + "%" : "...");
                    Platform.runLater(() -> updateStatus(status));
                });
                
                builder.getCefSettings().windowless_rendering_enabled = false;
                builder.getCefSettings().cache_path = cachePath.getAbsolutePath();
                builder.getCefSettings().persist_session_cookies = true;
                
                // DEEP FIX FLAGS: Disable all features that cause window detachment or GPU stalls
                builder.addJcefArgs(
                    "--no-sandbox", 
                    "--disable-gpu", 
                    "--disable-gpu-compositing", 
                    "--disable-features=CalculateNativeWinOcclusion",
                    "--disable-direct-composition", 
                    "--disable-gpu-rasterization",
                    "--disable-dev-shm-usage",
                    "--disable-software-rasterizer",
                    "--enable-password-save", 
                    "--enable-automatic-password-saving", 
                    "--password-store=basic", 
                    "--enable-password-manager"
                );
                
                cefApp = builder.build();
                cefClient = cefApp.createClient();
                
                cefClient.addDisplayHandler(new CefDisplayHandlerAdapter() {
                    @Override
                    public void onAddressChange(CefBrowser browser, org.cef.browser.CefFrame frame, String url) {
                        JTextField bar = browserAddressBars.get(browser);
                        if (bar != null && frame.isMain()) {
                            SwingUtilities.invokeLater(() -> bar.setText(url));
                        }
                    }
                });

                CefMessageRouter router = CefMessageRouter.create();
                router.addHandler(new CefMessageRouterHandlerAdapter() {
                    @Override
                    public boolean onQuery(CefBrowser browser, CefFrame frame, long query_id, String request, boolean persistent, CefQueryCallback callback) {
                        if (request.startsWith("download:")) {
                            String[] parts = request.substring(9).split("\\|");
                            if (parts.length >= 2) { new BrowserBridge().downloadQP(parts[0], parts[1]); callback.success("OK"); return true; }
                        } else if (request.startsWith("session:")) {
                            String s = request.substring(8); Platform.runLater(() -> sessionNameField.setText(s)); callback.success("OK"); return true;
                        } else if (request.equals("examflow_sync_start")) {
                            activityLogger.info("Examflow Sync Trigger received from browser tab. Waiting for cloud update...");
                            updateStatus("Syncing with Examflow...");
                            callback.success("OK"); return true;
                        } else if (request.equals("examflow_sync")) {
                            Platform.runLater(() -> fetchFromExamflow(null)); callback.success("OK"); return true;
                        } else if (request.equals("check_session")) {
                            String s = sessionNameField.getText().trim();
                            callback.success(s.isEmpty() ? "MISSING" : "OK"); return true;
                        }
                        return false;
                    }
                }, true);
                cefClient.addMessageRouter(router);

                cefClient.addDownloadHandler(new CefDownloadHandlerAdapter() {
                    @Override
                    public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem downloadItem, String suggestedName, CefBeforeDownloadCallback callback) {
                        File sessionDir = getSessionDir();
                        if (sessionDir == null) return false;
                        callback.Continue(new File(sessionDir, suggestedName).getAbsolutePath(), false); return true;
                    }
                    @Override
                    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem downloadItem, CefDownloadItemCallback callback) {
                        if (downloadItem.isComplete()) {
                            File f = new File(downloadItem.getFullPath());
                            activityLogger.success("Download Ready: " + f.getName());
                            String name = f.getName().toLowerCase();
                            if (name.endsWith(".json")) {
                                Platform.runLater(() -> processRoomWiseJsonFile(f));
                            } else if (name.endsWith(".pdf")) {
                                Platform.runLater(() -> processFile(f));
                            }
                        }
                    }
                });

                cefClient.addLoadHandler(new CefLoadHandlerAdapter() {
                    @Override
                    public void onLoadEnd(CefBrowser browser, org.cef.browser.CefFrame frame, int httpStatusCode) {
                        if (frame.isMain()) {
                            String url = browser.getURL();
                            String injectionScript = 
                                "(function() { " +
                                "  setInterval(function() { " +
                                "    var btns = document.querySelectorAll('.btn_download'); " +
                                "    if (btns.length > 0) { " +
                                "      try { " +
                                "        var raw = ''; " +
                                "        var inp = document.getElementById('examdate'); " +
                                "        if (inp && inp.value) { raw = inp.value; } " +
                                "        else { " +
                                "          var m = document.documentElement.innerHTML.match(/id=\\\"examdate\\\"[^>]*value=\\\"([^\\\"]+)\\\"/i); " +
                                "          if (m) { raw = m[1]; } " +
                                "          else { " +
                                "            var m2 = document.body.innerText.match(/(\\d{2})\\/(\\d{2})\\/(\\d{4})/); " +
                                "            if (m2) raw = m2[0]; " +
                                "          } " +
                                "        } " +
                                "        if (raw && !window._lastDate) { " +
                                "          window._lastDate = raw; " +
                                "          var p = raw.split(/[/\\-.]/); " +
                                "          var f = raw; " +
                                "          if (p.length === 3) { " +
                                "            if (p[0].length === 4) f = p[2] + '.' + p[1] + '.' + p[0].substring(2); " +
                                "            else f = p[0] + '.' + p[1] + '.' + p[2].substring(p[2].length-2); " +
                                "          } " +
                                "          var row = document.querySelector('tr.odd, tr.even'); " +
                                "          var suf = (row && row.cells[2].innerText.indexOf('PM') !== -1) ? 'AN' : 'FN'; " +
                                "          window.cefQuery({ request: 'session:' + f + ' ' + suf }); " +
                                "        } " +
                                "      } catch(e) {} " +
                                "      if (!document.getElementById('smart-bulk-header')) { " +
                                "        var h = document.createElement('div'); " +
                                "        h.id = 'smart-bulk-header'; " +
                                "        h.style.background = '#f8f9fa'; h.style.padding = '15px'; h.style.marginBottom = '20px'; " +
                                "        h.style.border = '1px solid #dee2e6'; h.style.borderRadius = '8px'; " +
                                "        h.style.display = 'flex'; h.style.alignItems = 'center'; h.style.justifyContent = 'space-between'; " +
                                "        h.style.boxShadow = '0 2px 4px rgba(0,0,0,0.05)'; " +
                                "        var t = document.createElement('span'); " +
                                "        t.innerText = 'Smart Print: Found ' + btns.length + ' Question Papers'; " +
                                "        t.style.fontWeight = 'bold'; t.style.color = '#333'; t.style.fontSize = '16px'; " +
                                "        var b = document.createElement('button'); " +
                                "        b.id = 'smart-bulk-btn'; b.innerText = 'Start Bulk Download & Queue'; " +
                                "        b.style.background = '#28a745'; b.style.color = 'white'; b.style.padding = '10px 20px'; " +
                                "        b.style.borderRadius = '5px'; b.style.cursor = 'pointer'; b.style.border = 'none'; b.style.fontWeight = 'bold'; " +
                                "        b.onclick = function() { " +
                                "          window.cefQuery({ request: 'check_session', onSuccess: function(res) { " +
                                "            if(res === 'MISSING') { alert('Please enter Detected Session name in the App first!'); return; } " +
                                "            if(!confirm('Start automated download for ' + btns.length + ' papers?')) return; " +
                                "            b.disabled = true; " +
                                "            for (var i = 0; i < btns.length; i++) { " +
                                "              (function(idx) { " +
                                "                setTimeout(function() { " +
                                "                  b.innerText = 'ΓÅ│ Processing ' + (idx + 1) + '/' + btns.length + '...'; " +
                                "                  var cur = btns[idx]; var r = cur.closest('tr'); " +
                                "                  var time = r.cells[2].innerText.replace(/:/g, '_'); " +
                                "                  var dp = (window._lastDate || '').replace(/[/\\-]/g, '.'); " +
                                "                  if (!dp) { var d = new Date(); dp = ('0' + d.getDate()).slice(-2) + '.' + ('0' + (d.getMonth()+1)).slice(-2) + '.' + (d.getFullYear()+'').substring(2); } " +
                                "                  var fname = 'REG_' + dp + '_' + time + '_' + r.cells[0].innerText + '_' + r.cells[1].innerText.replace(/[^a-z0-9]/gi, '_'); " +
                                "                  window.cefQuery({ request: 'download:' + cur.value.trim() + '|' + fname }); " +
                                "                  if (idx === btns.length - 1) { " +
                                "                    b.innerText = 'Γ£à All Files Queued'; " +
                                "                    setTimeout(function() { b.innerText = 'Bulk Download Complete'; }, 2000); " +
                                "                  } " +
                                "                }, idx * 1000); " +
                                "              })(i); " +
                                "            } " +
                                "          }}); " +
                                "        }; " +
                                "        h.appendChild(t); h.appendChild(b); " +
                                "        var target = document.querySelector('.table-responsive') || document.querySelector('table') || document.body.firstChild; " +
                                "        if(target) target.parentNode.insertBefore(h, target); " +
                                "      } " +
                                "    } " +
                                "    if (window.location.href.indexOf('examflow-india.web.app') !== -1) { " +
                                "      var sync = Array.from(document.querySelectorAll('button')).find(function(el) { return el.innerText.indexOf('Sync to Print Manager') !== -1; }); " +
                                "      if (sync && !sync.getAttribute('data-jcef')) { " +
                                "        sync.setAttribute('data-jcef', 'true'); " +
                                "        sync.addEventListener('click', function() { " +
                                "          window.cefQuery({ request: 'examflow_sync_start' }); " +
                                "          setTimeout(function() { window.cefQuery({ request: 'examflow_sync' }); }, 2500); " +
                                "        }); " +
                                "      } " +
                                "    } " +
                                "  }, 2000); " +
                                "})();";
                            browser.executeJavaScript(injectionScript, url, 0);
                        }
                    }
                });

                activityLogger.success("Smart Browser Engine Ready.");
                Platform.runLater(() -> {
                    updateStatus("Smart Browser Ready");
                    initializingCef = false;
                    if (autoOpenBrowser) {
                        autoOpenBrowser = false;
                        openSmartBrowser();
                    }
                });
            } catch (Exception e) { 
                initializingCef = false;
                activityLogger.error("Browser Init Failed: " + e.getMessage()); 
                logger.error("JCEF Init Error", e);
            }
        });
        initThread.setPriority(Thread.MAX_PRIORITY);
        initThread.start();
    }

    private void openSmartBrowser() {
        if (cefApp == null) {
            autoOpenBrowser = true;
            initCef();
            return;
        }
        SwingUtilities.invokeLater(() -> {
            JTabbedPane tabbedPane = new JTabbedPane();
            
            // Tab 1: University Portal
            CefBrowser browser1 = cefClient.createBrowser("https://collegeportal.uoc.ac.in/", false, false);
            JPanel panel1 = createBrowserTabPanel(browser1, "https://collegeportal.uoc.ac.in/");
            tabbedPane.addTab("University Portal", panel1);
            
            // Tab 2: Examflow
            CefBrowser browser2 = cefClient.createBrowser("https://examflow-india.web.app/index.html", false, false);
            JPanel panel2 = createBrowserTabPanel(browser2, "https://examflow-india.web.app/index.html");
            tabbedPane.addTab("Examflow Portal", panel2);

            JFrame frame = new JFrame("Smart QP - Browser");
            frame.getContentPane().add(tabbedPane, BorderLayout.CENTER);
            frame.setSize(1280, 800);
            frame.setLocationRelativeTo(null);
            
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    browserAddressBars.remove(browser1);
                    browserAddressBars.remove(browser2);
                    browser1.close(true);
                    browser2.close(true);
                }
            });
            
            frame.setVisible(true);
            activityLogger.info("Smart Browser window opened with tabs.");
        });
    }

    private JPanel createBrowserTabPanel(CefBrowser browser, String initialUrl) {
        JTextField addressBar = new JTextField(initialUrl);
        browserAddressBars.put(browser, addressBar);
        
        JButton backBtn = new JButton("<");
        JButton forwardBtn = new JButton(">");
        JButton refreshBtn = new JButton("Refresh");
        
        backBtn.addActionListener(e -> browser.goBack());
        forwardBtn.addActionListener(e -> browser.goForward());
        refreshBtn.addActionListener(e -> browser.reload());
        addressBar.addActionListener(e -> browser.loadURL(addressBar.getText()));
        
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(backBtn);
        toolBar.add(forwardBtn);
        toolBar.add(refreshBtn);
        toolBar.addSeparator();
        toolBar.add(addressBar);
        
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolBar, BorderLayout.NORTH);
        panel.add(browser.getUIComponent(), BorderLayout.CENTER);
        return panel;
    }

    private javafx.scene.Parent createPortalView() {
        VBox layout = new VBox(15); layout.setAlignment(javafx.geometry.Pos.CENTER); layout.setPadding(new Insets(30));
        Label info = new Label("University Portal Downloader"); info.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        sessionNameField.setPromptText("Auto-detected after launch..."); sessionNameField.setPrefWidth(300);
        HBox sessionBox = new HBox(10, new Label("Detected Session:"), sessionNameField); sessionBox.setAlignment(javafx.geometry.Pos.CENTER);
        downloadPathField.setPromptText("Select Save Folder"); downloadPathField.setPrefWidth(400);
        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser(); dc.setTitle("Select Save Folder");
            File initial = new File(downloadPathField.getText()); if (initial.exists()) dc.setInitialDirectory(initial);
            File selected = dc.showDialog(null); if (selected != null) { downloadPathField.setText(selected.getAbsolutePath()); config.setBaseDownloadPath(selected.getAbsolutePath()); saveConfigs(); }
        });
        HBox pathBox = new HBox(10, new Label("Save Folder:"), downloadPathField, browseBtn); pathBox.setAlignment(javafx.geometry.Pos.CENTER);
        Button launchBtn = new Button("Launch Smart Browser");
        launchBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 15 30;");
        launchBtn.setOnAction(e -> {
            if (downloadPathField.getText().isEmpty()) { new Alert(Alert.AlertType.WARNING, "Please select a Save Folder first!").show(); return; }
            openSmartBrowser();
        });
        layout.getChildren().addAll(info, sessionBox, pathBox, launchBtn);
        return layout;
    }

    public class BrowserBridge {
        public void downloadQP(String fileId, String fileName) {
            if (sessionNameField.getText().trim().isEmpty()) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Session Name Required");
                    alert.setHeaderText("Session Name is missing");
                    alert.setContentText("The application could not auto-detect the session name. Please enter it manually in the 'Detected Session' field on the Portal tab before downloading.");
                    alert.show();
                });
                return;
            }
            activityLogger.info("Queued download: " + fileName);
            analysisExecutor.submit(() -> {
                try {
                    updateStatus("Downloading: " + fileName);
                    HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
                    HttpRequest request = HttpRequest.newBuilder().uri(URI.create("https://collegeportal.uoc.ac.in/valuation_camp/downloadqp_file?fileid=" + fileId)).GET().build();
                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    
                    if (response.statusCode() == 200) {
                        File sessionDir = getSessionDir(); 
                        if (sessionDir == null) {
                            activityLogger.error("Download directory missing for: " + fileName);
                            return;
                        }
                        File saveFile = new File(sessionDir, fileName + ".pdf");
                        java.nio.file.Files.write(saveFile.toPath(), response.body());
                        Platform.runLater(() -> { 
                            processFile(saveFile); 
                            activityLogger.success("Fetched: " + fileName);
                            updateStatus("Downloaded: " + fileName);
                        });
                    } else {
                        activityLogger.error("Failed to fetch " + fileName + " (HTTP " + response.statusCode() + ")");
                        updateStatus("Error downloading: " + fileName);
                    }
                } catch (Exception e) { 
                    activityLogger.error("Download Error (" + fileName + "): " + e.getMessage()); 
                    updateStatus("Download Failed: " + fileName);
                }
            });
        }
    }

    private File getSessionDir() {
        String base = downloadPathField.getText(); String session = sessionNameField.getText().trim();
        if (base.isEmpty() || session.isEmpty()) return null;
        File sessionDir = new File(base, session);
        if (!sessionDir.exists()) sessionDir.mkdirs();
        return sessionDir;
    }

    private File getResolvedCacheDir() {
        String userDir = System.getProperty("user.dir");
        String os = System.getProperty("os.name").toLowerCase();
        boolean inProgramFiles = userDir.toLowerCase().contains("program files");
        
        if (!inProgramFiles) {
            // Portable mode check
            try {
                Path testPath = Paths.get(userDir, ".write_test_" + System.currentTimeMillis());
                Files.createFile(testPath);
                Files.delete(testPath);
                return new File(userDir, "bin/chromium_cache");
            } catch (Exception e) {}
        }

        // Installed mode: Use AppData
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return new File(appData != null ? appData : System.getProperty("user.home"), "SmartQPPrintManager/chromium_cache");
        }
        return new File(System.getProperty("user.home"), ".smartqpprintmanager/chromium_cache");
    }

    private static File getChromiumDir() {
        File projectDir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        File localBundle = new File(projectDir, "bin/chromium");
        if (localBundle.exists() && localBundle.isDirectory()) return localBundle;
        return new File(System.getProperty("user.home"), ".jcef-bundle");
    }

    private static void bypassSSL() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{ new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return null; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                public void checkServerTrusted(X509Certificate[] certs, String authType) { }
            }};
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {}
    }

    public static void main(String[] args) { 
        // 1. Immediately exit if this is a JCEF helper process to prevent UI recursion
        for (String arg : args) {
            if (arg.contains("--type=renderer") || arg.contains("--type=gpu-process") || arg.contains("--type=utility")) {
                return;
            }
        }

        // 2. HARD RESET: Force cleanup before JavaFX even starts
        try {
            // Kill any ghosts
            Process p = Runtime.getRuntime().exec("taskkill /F /IM jcef_helper.exe /T");
            p.waitFor();
            
            // Proactively clear Chromium locks in AppData to prevent "Opening in existing session" hand-off
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                File cacheDir = new File(appData, "SmartQPPrintManager/chromium_cache");
                if (cacheDir.exists()) {
                    new File(cacheDir, "SingletonLock").delete();
                    new File(cacheDir, "SingletonCookie").delete();
                    new File(cacheDir, "SingletonSocket").delete();
                }
            }
            Thread.sleep(500); // Brief pause for OS stability
        } catch (Exception e) {}

        bypassSSL();
        launch(args); 
    }
}
