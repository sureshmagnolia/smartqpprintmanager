package com.printmanager;

import com.printmanager.model.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
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
    private static SSLContext unsafeSslContext;
    public static Image appIcon = null;

    private final PDFService pdfService = new PDFService();
    private final PrintService printService = new PrintService();
    private final ConfigManager configManager = new ConfigManager();
    private final PDFViewer pdfViewer = new PDFViewer();
    private final ActivityLogger activityLogger = ActivityLogger.getInstance();

    private final ExecutorService analysisExecutor = Executors.newFixedThreadPool(4);
    private final ExecutorService printQueueExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService roomPrintExecutor = Executors.newFixedThreadPool(2);

    private Config config;
    private AppState appState;
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
    private Button printAllBtn;
    private final javafx.collections.ObservableMap<String, String> printerStatusCache = FXCollections.observableHashMap();
    private final HBox simAlertHeader = new HBox();
    private final CheckBox printCoverPageCbox = new CheckBox("Print Room Status Cover Page?");

    private CefApp cefApp;
    private CefClient cefClient;
    private volatile boolean initializingCef = false;
    private volatile boolean autoOpenBrowser = false;
    private final Map<CefBrowser, JTextField> browserAddressBars = new HashMap<>();
    private final Map<String, String> downloadFilenameMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final TextField urlField = new TextField("https://collegeportal.uoc.ac.in/");
    private final TextField sessionNameField = new TextField();
    private final TextField downloadPathField = new TextField();

    // Global Synchronization Engine
    private final DoubleProperty globalPulseOpacity = new SimpleDoubleProperty(1.0);
    private final BooleanProperty dualAlertPhase = new SimpleBooleanProperty(true); // true = Red, false = Black

    @Override
    public void start(Stage primaryStage) {
        // Initialize Global Pulse Timeline (0.7 to 1.0)
        javafx.animation.Timeline globalPulse = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.ZERO, 
                new javafx.animation.KeyValue(globalPulseOpacity, 1.0)),
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(800), 
                new javafx.animation.KeyValue(globalPulseOpacity, 0.7))
        );
        globalPulse.setAutoReverse(true);
        globalPulse.setCycleCount(javafx.animation.Animation.INDEFINITE);
        globalPulse.play();

        // Initialize Dual Alert Phase Timer (flips every 1.6s)
        javafx.animation.Timeline phaseFlip = new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(javafx.util.Duration.millis(1600), e -> dualAlertPhase.set(!dualAlertPhase.get()))
        );
        phaseFlip.setCycleCount(javafx.animation.Animation.INDEFINITE);
        phaseFlip.play();

        // Load icon early and keep reference for other windows
        try {
            appIcon = new Image(getClass().getResourceAsStream("/icon.png"));
            primaryStage.getIcons().add(appIcon);
        } catch (Exception e) {
            logger.warn("Could not load application icon from resource", e);
            try {
                File iconFile = new File("src/main/resources/icon.png");
                if (iconFile.exists()) {
                    appIcon = new Image(iconFile.toURI().toString());
                    primaryStage.getIcons().add(appIcon);
                }
            } catch (Exception e2) { logger.error("Icon fallback failed", e2); }
        }

        config = configManager.loadConfig();
        appState = configManager.loadAppState();
        logger.info("Application start: Loaded {} rules, {} split rules, {} queue items, {} room groups",
            config.getRules().size(), config.getSmartSplitRules().size(), appState.getFileQueue().size(), appState.getRoomGroups().size());

        rulesList.addAll(config.getRules());
        smartSplitRulesList.addAll(config.getSmartSplitRules());
        fileQueue.addAll(appState.getFileQueue());
        roomGroupsList.addAll(appState.getRoomGroups());
        
        // Initialize persistent UI fields from config
        if (config.getCollegeId() != null) urlField.setText("https://collegeportal.uoc.ac.in/"); // Reset to default just in case
        if (config.getBaseDownloadPath() != null) downloadPathField.setText(config.getBaseDownloadPath());
        if (config.getLastSessionName() != null) sessionNameField.setText(config.getLastSessionName());
        sessionNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            config.setLastSessionName(newVal.trim());
            saveConfigs();
        });

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
        simAlertHeader.getChildren().add(new Label("\u26A0 SIMULATION MODE ACTIVE: Actual printing is disabled. Change this in Settings."));
        simAlertHeader.setManaged(false);
        simAlertHeader.setVisible(false);

        VBox root = new VBox(simAlertHeader, tabPane, createStatusBarView());
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 1200, 850);
        try {
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } catch (Exception e) { logger.warn("Could not load CSS"); }
        
        primaryStage.setTitle("Smart QP Print Manager v3.4.2");
        
        try {
            primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/icon.png")));
        } catch (Exception e) {
            logger.warn("Could not load application icon", e);
        }

        primaryStage.setScene(scene);
        primaryStage.setMaximized(true);
        primaryStage.show();

        // Hide splash screen if it exists
        if (Launcher.splash != null) {
            Launcher.splash.dispose();
            Launcher.splash = null;
        }

        // Setup auto-save listeners AFTER UI is ready and initial load is complete
        Platform.runLater(() -> {
            // Re-initialize Room Router view content to force UI refresh with loaded data
            roomTab.setContent(createRoomRouterView(primaryStage));

            fileQueue.addListener((javafx.collections.ListChangeListener<FileItem>) c -> {
                relinkRoomItems();
                saveConfigs();
            });
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

    private final ObservableList<PrinterDisplay> printerDisplays = FXCollections.observableArrayList();

    private void startPrinterStatusMonitor() {
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    // Fetch detailed status once per cycle
                    Map<String, Map<String, String>> detailed = printService.getPrintersDetailedStatus();
                    
                    Platform.runLater(() -> {
                        // 1. Update simple status cache (for row indicators)
                        printerStatusCache.clear();
                        detailed.forEach((name, data) -> printerStatusCache.put(name, data.get("status")));
                        
                        // 2. Update dashboard displays
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
                catch (Exception e) { logger.error("Printer monitor error", e); }
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
                // Ensure cookies are flushed before disposal
                cefApp.dispose();
                Thread.sleep(500); 
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
        
        Label totalQPs = new Label("Total QPs: 0");
        totalQPs.setStyle("-fx-font-weight: bold; -fx-text-fill: #2196F3;");
        Label totalCopies = new Label("Total Copies: 0");
        totalCopies.setStyle("-fx-font-weight: bold; -fx-text-fill: #9C27B0;");
        Label printSheets = new Label("Print Sheets: 0");
        printSheets.setStyle("-fx-font-weight: bold; -fx-text-fill: #E91E63;");
        Label activePrinters = new Label("Active Printers: 0");
        activePrinters.setStyle("-fx-font-weight: bold; -fx-text-fill: #4CAF50;");
        Label totalPages = new Label("Total Pages: 0");
        totalPages.setStyle("-fx-font-weight: bold; -fx-text-fill: #FF9800;");

        Label simWarning = new Label("Simulation Mode is ON");
        simWarning.setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
        simWarning.visibleProperty().bind(simAlertHeader.visibleProperty());
        simWarning.managedProperty().bind(simAlertHeader.managedProperty());

        Label mapAlert = new Label("\uD83D\uDDFA MAP ALERT");
        mapAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-color: #800020; -fx-padding: 6px 12px; -fx-background-radius: 5px; -fx-border-color: #ffeb3b; -fx-border-width: 2px; -fx-border-radius: 5px;");
        mapAlert.setVisible(false);
        mapAlert.managedProperty().bind(mapAlert.visibleProperty());
        mapAlert.opacityProperty().bind(globalPulseOpacity);

        Label stapleAlert = new Label("\uD83D\uDCCE STAPLE ALERT");
        stapleAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-color: #212121; -fx-padding: 6px 12px; -fx-background-radius: 5px; -fx-border-color: white; -fx-border-width: 2px; -fx-border-radius: 5px;");
        stapleAlert.setVisible(false);
        stapleAlert.managedProperty().bind(stapleAlert.visibleProperty());
        stapleAlert.opacityProperty().bind(globalPulseOpacity);

        Runnable updateQueueStats = () -> {
            long uniqueQPs = fileQueue.stream()
                .map(item -> {
                    String name = item.getFileName();
                    if (name == null) return "";
                    String qp = extractQPFromFileName(name);
                    if (qp != null) return qp;
                    return name.replaceFirst("^(Split_|Remain_)+", "");
                })
                .filter(name -> !name.isEmpty())
                .distinct()
                .count();
            totalQPs.setText("Total QPs: " + uniqueQPs);
            totalPages.setText("Total Pages: " + fileQueue.stream().mapToInt(FileItem::getPageCount).sum());
            totalCopies.setText("Total Copies: " + fileQueue.stream().mapToInt(FileItem::getCopies).sum());
            printSheets.setText("Print Sheets: " + fileQueue.stream().mapToInt(item -> calculatePP(item) * item.getCopies()).sum());
        };

        fileQueue.addListener((javafx.collections.ListChangeListener<FileItem>) c -> {
            updateQueueStats.run();
        });
        updateQueueStats.run();

        Thread statsThread = new Thread(() -> {
            while(true) {
                try {
                    long active = printerStatusCache.values().stream().filter(s -> "Ready".equalsIgnoreCase(s) || "Printing".equalsIgnoreCase(s)).count();
                    boolean hasHistory = fileQueue.stream().anyMatch(f -> f.getFileName() != null && f.getFileName().toLowerCase().contains("history")) ||
                                         roomGroupsList.stream().flatMap(g -> g.getItems().stream()).anyMatch(i -> (i.getPdfFileName() != null && i.getPdfFileName().toLowerCase().contains("history")) || (i.getCourseName() != null && i.getCourseName().toLowerCase().contains("history")));
                    boolean hasStaple = roomGroupsList.stream().flatMap(g -> g.getItems().stream())
                                         .anyMatch(i -> i.getMatchedFile() != null && "Booklet".equals(i.getMatchedFile().getStyle()) && calculatePP(i.getMatchedFile()) > 1);
                    Platform.runLater(() -> {
                        activePrinters.setText("Active Printers: " + active);
                        mapAlert.setVisible(hasHistory);
                        stapleAlert.setVisible(hasStaple);
                    });
                    Thread.sleep(5000);
                } catch (Exception e) { break; }
            }
        });
        statsThread.setDaemon(true);
        statsThread.start();

        totalQPs.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        totalCopies.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        printSheets.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        activePrinters.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        totalPages.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        
        HBox alertsBox = new HBox(12, mapAlert, stapleAlert);
        alertsBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        statsDash.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statsDash.setPadding(new Insets(10, 20, 10, 20));
        statsDash.getChildren().addAll(totalQPs, totalCopies, printSheets, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, activePrinters, totalPages, alertsBox, simWarning);

        TextField searchField = new TextField();
        searchField.setPromptText("Search files...");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button clearSearchBtn = new Button("Clear");
        clearSearchBtn.setOnAction(e -> searchField.clear());
        HBox searchBox = new HBox(5, searchField, clearSearchBtn);
        searchBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

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
                if (empty) { setText(null); setStyle(""); }
                else {
                    setText(String.valueOf(getIndex() + 1));
                    setAlignment(javafx.geometry.Pos.CENTER);
                    setStyle("-fx-text-fill: " + (isAlertItem(getTableRow().getItem()) ? "white" : "black") + ";");
                }
            }
        });
        snCol.setPrefWidth(50); snCol.setMaxWidth(60); snCol.setSortable(false);

        TableColumn<FileItem, String> nameCol = new TableColumn<>("File Name");
        nameCol.setCellValueFactory(d -> d.getValue().fileNameProperty());
        nameCol.setMinWidth(300);
        nameCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else {
                    setText(item);
                    setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    setStyle("-fx-text-fill: " + (isAlertItem(getTableRow().getItem()) ? "white" : "black") + ";");
                }
            }
        });
        TableColumn<FileItem, Integer> pagesCol = new TableColumn<>("Pages");
        pagesCol.setCellValueFactory(d -> d.getValue().pageCountProperty().asObject());
        pagesCol.setPrefWidth(60); pagesCol.setMaxWidth(70);
        pagesCol.setCellFactory(tc -> new TableCell<FileItem, Integer>() {
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else { 
                    setText(item.toString()); setAlignment(javafx.geometry.Pos.CENTER); 
                    setStyle("-fx-text-fill: " + (isAlertItem(getTableRow().getItem()) ? "white" : "black") + ";");
                }
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
                spinner.getEditor().setStyle("-fx-text-fill: black;");

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
            { 
                combo.setStyle("-fx-text-inner-color: black; -fx-text-fill: black;");
                combo.setPrefWidth(90); combo.setOnAction(e -> {
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
            private final HBox container = new HBox(5, combo, statusIndicator);
            
            {
                combo.setStyle("-fx-text-inner-color: black; -fx-text-fill: black;");
                combo.setPrefWidth(140);
                container.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                statusIndicator.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
                
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
                            statusIndicator.setText("\u26A0 OFFLINE");
                            statusIndicator.setStyle("-fx-text-fill: #f44336;");
                        } else {
                            statusIndicator.setText("\u2714 Ready");
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
            { 
                combo.setStyle("-fx-text-inner-color: black; -fx-text-fill: black;");
                combo.setPrefWidth(70); combo.setOnAction(e -> {
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
                    boolean alert = isAlertItem(getTableRow().getItem());
                    if (item.contains("Sent")) setStyle("-fx-text-fill: " + (alert ? "#81c784" : "green") + "; -fx-font-weight: bold;");
                    else if (item.contains("Finished")) setStyle("-fx-text-fill: " + (alert ? "#90caf9" : "blue") + "; -fx-font-weight: bold;");
                    else if (item.contains("Error")) setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: " + (alert ? "white" : "black") + ";");
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
            private javafx.beans.value.ChangeListener<String> statusListener = null;
            {
                container.setAlignment(javafx.geometry.Pos.CENTER);
                pBtn.setOnAction(e -> {
                    FileItem item = getTableRow().getItem();
                    if (item == null) return;
                    if ("Sent".equals(pBtn.getText())) {
                        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                        alert.setTitle("Print Confirmation");
                        alert.setHeaderText("Already Sent!");
                        alert.setContentText("This file has already been sent to the printer.\nAre you sure you want to print it again?");
                        alert.showAndWait().ifPresent(response -> {
                            if (response == ButtonType.OK) {
                                pBtn.setText("Sending...");
                                pBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold;");
                                pBtn.setDisable(true);
                                printFile(item);
                            }
                        });
                    } else {
                        pBtn.setText("Sending...");
                        pBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold;");
                        pBtn.setDisable(true);
                        printFile(item);
                    }
                });
                sBtn.setOnAction(e -> { if (getTableRow().getItem() != null) saveFileAs(getTableRow().getItem()); });
                vBtn.setOnAction(e -> { if (getTableRow().getItem() != null) previewFile(getTableRow().getItem(), true, true); });
                rBtn.setOnAction(e -> { if (getTableRow().getItem() != null) fileQueue.remove(getTableRow().getItem()); });
                rBtn.setStyle("-fx-text-fill: red;");
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                FileItem fi = getTableRow().getItem();
                // Remove old listener if row was recycled
                if (statusListener != null && fi != null) {
                    fi.statusProperty().removeListener(statusListener);
                }
                // Sync button state from current status
                syncPrintBtnState(pBtn, fi.getStatus());
                // Listen for future status changes
                statusListener = (obs, ov, nv) -> Platform.runLater(() -> syncPrintBtnState(pBtn, nv));
                fi.statusProperty().addListener(statusListener);
                setGraphic(container);
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
                    // Row double-click -> Original Preview (for splitting)
                    previewFile(row.getItem(), false, false);
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
        printAllBtn = printBtn;
        printBtn.setOnAction(e -> {
            if (isPrintingAll) return;
            printBtn.setText("Sending...");
            printBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold;");
            printBtn.setDisable(true);
            printAll();
        });

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

        VBox layout = new VBox(10, statsDash, searchBox, table, btns);
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

        table.setRowFactory(tv -> {
            TableRow<PrinterDisplay> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    openWindowsPrinterQueue(row.getItem().getName());
                }
            });
            return row;
        });

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

    private void openWindowsPrinterQueue(String printerName) {
        try {
            // rundll32.exe printui.dll,PrintUIEntry /o /n "Printer Name"
            Runtime.getRuntime().exec("rundll32.exe printui.dll,PrintUIEntry /o /n \"" + printerName + "\"");
            activityLogger.info("Opening Windows printer queue for: " + printerName);
        } catch (Exception e) {
            logger.error("Failed to open printer queue for " + printerName, e);
            activityLogger.error("Failed to open printer queue for " + printerName);
        }
    }

    private void processFile(File file) {
        if (!file.getName().toLowerCase().endsWith(".pdf")) return;
        
        // Remove existing entries for the same file path to allow "refresh/replace" behavior
        Platform.runLater(() -> {
            fileQueue.removeIf(item -> item.getFile().getAbsolutePath().equalsIgnoreCase(file.getAbsolutePath()));
        });

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
                    addFileToQueue(file, null, null, null);
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
                // Ensure MCQ_ prefix is applied for special mode
                if (overlay != null && !overlay.isEmpty()) {
                    prefix = overlay + "_";
                }
            } else {
                style = rule.getAfterStyle6Plus();
                // If an overlay text is defined, use it as a prefix (e.g. MCQ_)
                if (rule.getAfterOverlayText() != null && !rule.getAfterOverlayText().isEmpty()) {
                    prefix = rule.getAfterOverlayText() + "_";
                }
            }
        } else {
            // Main Part: Apply QP Overlay if keyword suggests MCQ/SDE
            if (rule.getKeyword().toUpperCase().contains("MCQ") || rule.getKeyword().toUpperCase().contains("SDE")) {
                String qp = extractQPFromFileName(originalName);
                if (qp != null) {
                    try {
                        File overlaid = printService.applyTopLeftOverlay(file, qp);
                        f = overlaid;
                    } catch (Exception e) {
                        logger.error("Failed to apply QP overlay", e);
                    }
                }
            }
            if (pages == 1) style = rule.getBeforeStyle1();
            else if (pages == 2) style = rule.getBeforeStyle2();
            else style = rule.getBeforeStyle3Plus();
        }

        final String fs = style; final String fo = overlay; final File ff = f; final int fp = pages;
        final String finalPrefix = prefix;
        Platform.runLater(() -> {
            FileItem item = new FileItem(ff, fp, "", "None", false, false, "Left", 1, "A4", fo);
            item.setFileName(finalPrefix + originalName);
            item.setStyle(fs);
            fileQueue.add(item);
        });
    }

    private void addFileToQueue(File file, String manualStyle, String manualOverlay, String customName) {
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
                    if (customName != null) item.setFileName(customName);
                    fileQueue.add(item);
                });
            } catch (Exception e) { logger.error("Add error", e); }
        });
    }

    private PrintRule findMatchingRule(int pages, String content) {
        for (PrintRule r : rulesList) if (r.matches(pages, content)) return r;
        return null;
    }

    private void previewFile(FileItem item, boolean isReadOnly, boolean showRendered) {
        analysisExecutor.submit(() -> {
            try {
                File f = item.getFile();
                // If showRendered is true, apply booklet and overlay to show the "final" output
                if (showRendered) {
                    if (item.isBooklet()) f = pdfService.createBookletPDF(f, item.getBindingType(), item.getPaperSize());
                    if (item.getOverlayText() != null && !item.getOverlayText().isEmpty()) {
                        File pf = printService.applyOverlayInternal(f, item.getOverlayText());
                        // If we created a booklet temp file, delete it after overlay is applied to its copy
                        if (f != item.getFile()) f.delete();
                        f = pf;
                    }
                }
                
                File finalF = f;
                Platform.runLater(() -> pdfViewer.show(finalF, false, isReadOnly ? null : (sf, name, s, o, start, end, sub) -> {
                    if (sub) handleManualSubtract(item, sf, start, end, s, o);
                    else addFileToQueue(sf, s, o, "Split_" + item.getFileName());
                }));
            } catch (Exception e) { logger.error("Preview error", e); }
        });
    }

    private void handleManualSubtract(FileItem originalItem, File splitPart, int start, int end, String style, String overlay) {
        analysisExecutor.submit(() -> {
            try {
                File originalFile = originalItem.getFile();
                String parentDir = originalFile.getParent();
                if (parentDir == null) parentDir = ".";

                String originalName = originalItem.getFileName();
                String baseName = originalName.endsWith(".pdf") ? originalName.substring(0, originalName.length() - 4) : originalName;

                // 1. Save the Split Part into the same directory as the original
                String splitFileName = "Split_" + baseName + "_P" + start + "-" + end + ".pdf";
                File finalSplitFile = new File(parentDir, splitFileName);
                java.nio.file.Files.copy(splitPart.toPath(), finalSplitFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                Platform.runLater(() -> {
                    addFileToQueue(finalSplitFile, style, overlay, splitFileName);
                    activityLogger.info("Manual Split saved & added: " + splitFileName);
                });

                // 2. Create the Remainder file and replace the original
                File remainingTemp = pdfService.removePages(originalFile, start, end);
                if (remainingTemp != null) {
                    // Overwrite the original file with the remainder
                    java.nio.file.Files.copy(remainingTemp.toPath(), originalFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    int newCount = pdfService.getPageCount(originalFile);

                    Platform.runLater(() -> {
                        // Trigger UI update and rule re-application
                        originalItem.setFile(originalFile);
                        originalItem.setPageCount(newCount);
                        
                        // Explicitly rename to Remain_ for visibility if not already prefixed
                        if (!originalItem.getFileName().startsWith("Remain_") && !originalItem.getFileName().startsWith("Split_")) {
                            originalItem.setFileName("Remain_" + originalItem.getFileName());
                        }

                        // Re-apply rules for the remainder based on new page count
                        PrintRule rule = findMatchingRule(newCount, originalItem.getContent());
                        if (rule != null) {
                            originalItem.setTargetPrinter(rule.getPrinterName());
                            originalItem.setCopies(rule.getCopies());
                            originalItem.setStyle(rule.isBooklet() ? "Booklet" : (rule.isDuplex() ? "Duplex" : "Simplex"));
                        }
                        activityLogger.success("Original updated with Remainder: " + newCount + " pages.");
                        relinkRoomItems();
                        saveConfigs();
                    });

                    if (remainingTemp.exists()) remainingTemp.delete();
                }
                if (splitPart.exists()) splitPart.delete();

            } catch (Exception e) {
                logger.error("Subtract error", e);
                activityLogger.error("Manual subtract failed: " + e.getMessage());
            }
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

    private void syncPrintBtnState(Button pBtn, String status) {
        if (status == null || status.isEmpty() || status.equals("Ready") || status.equals("Queued")) {
            pBtn.setText("Print");
            pBtn.setStyle("");
            pBtn.setDisable(false);
        } else if (status.contains("Printing") || status.contains("Sending")) {
            pBtn.setText("Sending...");
            pBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold;");
            pBtn.setDisable(true);
        } else if (status.contains("Finished")) {
            pBtn.setText("Sent");
            pBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
            pBtn.setDisable(false);
        } else if (status.contains("Error") || status.contains("Skipped")) {
            pBtn.setText("Retry");
            pBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
            pBtn.setDisable(false);
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
        if (printAllBtn != null) Platform.runLater(() -> {
            printAllBtn.setText("Sending...");
            printAllBtn.setStyle("-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold;");
            printAllBtn.setDisable(true);
        });
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
            finally {
                isPrintingAll = false;
                if (printAllBtn != null) Platform.runLater(() -> {
                    printAllBtn.setText("✔ Sent");
                    printAllBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
                    printAllBtn.setDisable(false);
                });
            }
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

        Button updateSmartBtn = new Button("Update Rule");
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
        Button remSmartBtn = new Button("Remove Rule");
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
                Set<FileItem> matchedFiles = new HashSet<>();
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
                                    matchedFiles.add(item);
                                    activityLogger.info("Updated " + fileName + " copies to " + finalCount + " (Matched QP: " + qpCode + ")");
                                }
                            }
                            if (!matched) activityLogger.error("No file found in queue for QP Code: " + qpCode);
                        }
                    }
                }
                
                // Alert for files in queue that have no routing in JSON
                for (FileItem item : fileQueue) {
                    if (!matchedFiles.contains(item)) {
                        activityLogger.warn("Queue File: " + item.getFileName() + " has NO routing entries in JSON.");
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
                HttpClient client = HttpClient.newBuilder()
                    .sslContext(unsafeSslContext)
                    .build();
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

    private int calculatePP(FileItem item) {
        if (item == null) return 0;
        int pages = item.getPageCount();
        String style = item.getStyle();
        if ("Booklet".equals(style)) {
            return (int) Math.ceil(pages / 4.0);
        } else if ("Duplex".equals(style)) {
            return (int) Math.ceil(pages / 2.0);
        } else {
            return pages;
        }
    }

    private String extractQPFromFileName(String fileName) {
        if (fileName == null) return null;
        
        // 1. Try robust standard pattern _143812_ (most common)
        java.util.regex.Pattern p1 = java.util.regex.Pattern.compile("_(\\d{5,8})_", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m1 = p1.matcher(fileName);
        if (m1.find()) return m1.group(1);

        // 2. Try Examflow Fallback _14.05.26_FN_143812_
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("_([A-Z0-9]{5,10})[_\\.]", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m2 = p2.matcher(fileName);
        if (m2.find()) return m2.group(1);
        
        // 3. Last resort: standard datetime match from v3.1.3
        java.util.regex.Pattern p3 = java.util.regex.Pattern.compile("_\\d{2}[-_:\\.]\\d{2}\\s+[AP]M_([A-Z0-9]+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m3 = p3.matcher(fileName);
        if (m3.find()) return m3.group(1);

        return null;
    }

    private void relinkRoomItems() {
        if (roomGroupsList.isEmpty()) return;
        logger.info("Relinking & Syncing Room Items (Queue Size: {})", fileQueue.size());
        
        for (RoomGroup g : roomGroupsList) {
            // Identify all unique QP codes currently in this room
            Set<String> qpCodes = g.getItems().stream()
                .map(RoomItem::getQpCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            
            for (String qp : qpCodes) {
                // Find all matching files in queue for this QP
                List<FileItem> matches = fileQueue.stream()
                    .filter(f -> {
                        String extracted = extractQPFromFileName(f.getFileName());
                        return qp.equalsIgnoreCase(extracted) || f.getFileName().contains("_" + qp + "_") || f.getFileName().contains("_" + qp + ".");
                    })
                    .collect(Collectors.toList());
                
                // For each match, ensure a RoomItem exists in this group
                for (FileItem f : matches) {
                    boolean exists = g.getItems().stream()
                        .anyMatch(ri -> ri.getMatchedFile() == f);
                    
                    if (!exists) {
                        // Create a new RoomItem by copying from an existing one with same QP
                        RoomItem proto = g.getItems().stream()
                            .filter(ri -> ri.getQpCode().equalsIgnoreCase(qp))
                            .findFirst().orElse(null);
                        
                        if (proto != null) {
                            RoomItem newItem = new RoomItem(g.getRoomSerial(), qp, f.getFileName(), proto.getCount(), proto.getSourceNodeId());
                            newItem.setCourseName(proto.getCourseName());
                            newItem.setMatchedFile(f);
                            Platform.runLater(() -> {
                                if (g.getItems().stream().noneMatch(ri -> ri.getMatchedFile() == f)) {
                                    g.getItems().add(newItem);
                                }
                            });
                        }
                    }
                }
            }
            
            // Re-link existing items and INVALIDATE matches if file removed from queue
            for (RoomItem i : g.getItems()) {
                FileItem currentMatch = i.getMatchedFile();
                if (currentMatch != null && !fileQueue.contains(currentMatch)) {
                    i.setMatchedFile(null);
                    i.setStatus("PND"); // Reset to Pending
                }

                if (i.getMatchedFile() == null) {
                    for (FileItem qItem : fileQueue) {
                        String qName = qItem.getFileName();
                        String extractedQP = extractQPFromFileName(qName);
                        if (i.getQpCode().equalsIgnoreCase(extractedQP) || qName.contains("_" + i.getQpCode() + "_") || qName.contains("_" + i.getQpCode() + ".")) {
                            i.setMatchedFile(qItem);
                            break;
                        }
                    }
                }
            }

            // Reset status if it was "Partial Error" or "File Not Found" but all items are now matched
            if ("Partial Error".equals(g.getStatus()) || "File Not Found".equals(g.getStatus())) {
                boolean allMatched = g.getItems().stream().allMatch(ri -> ri.getMatchedFile() != null);
                if (allMatched) {
                    Platform.runLater(() -> g.setStatus("Ready"));
                }
            }
        }
    }

    private boolean isAlertItem(FileItem item) {
        if (item == null) return false;
        boolean isMap = item.getFileName() != null && item.getFileName().toLowerCase().contains("history");
        boolean isStaple = "Booklet".equals(item.getStyle()) && calculatePP(item) > 1;
        return isMap || isStaple;
    }

    private boolean isAlertRoomItem(RoomItem item) {
        if (item == null) return false;
        boolean isMap = (item.getPdfFileName() != null && item.getPdfFileName().toLowerCase().contains("history")) || 
                        (item.getCourseName() != null && item.getCourseName().toLowerCase().contains("history"));
        boolean isStaple = false;
        if (item.getMatchedFile() != null) {
            FileItem f = item.getMatchedFile();
            isStaple = "Booklet".equals(f.getStyle()) && calculatePP(f) > 1;
        }
        return isMap || isStaple;
    }

    private void updateRowStyle(TableRow<FileItem> row, String status) {
        FileItem item = row.getItem();
        if (item == null) {
            row.styleProperty().unbind();
            row.setStyle("");
            row.opacityProperty().unbind();
            row.setOpacity(1.0);
            return;
        }

        boolean isMap = item.getFileName() != null && item.getFileName().toLowerCase().contains("history");
        boolean isStaple = "Booklet".equals(item.getStyle()) && calculatePP(item) > 1;

        // Reset bindings
        row.styleProperty().unbind();
        row.opacityProperty().unbind();
        row.setOpacity(1.0);

        if (status != null && status.contains("Finished")) {
            row.setStyle("-fx-background-color: #c8e6c9; -fx-text-inner-color: black;");
        } else if (status != null && status.contains("Error")) {
            row.setStyle("-fx-background-color: #ffcdd2; -fx-text-inner-color: black;");
        } else {
            if (isMap && isStaple) {
                // DUAL Alert: Alternates colors Wine Red <-> Black
                row.styleProperty().bind(Bindings.when(dualAlertPhase)
                    .then("-fx-background-color: #800020; -fx-text-inner-color: white;") // Wine Red
                    .otherwise("-fx-background-color: #212121; -fx-text-inner-color: white;")); // Black
                row.opacityProperty().bind(globalPulseOpacity);
            } else if (isMap) {
                row.setStyle("-fx-background-color: #800020; -fx-text-inner-color: white;");
                row.opacityProperty().bind(globalPulseOpacity);
            } else if (isStaple) {
                row.setStyle("-fx-background-color: #212121; -fx-text-inner-color: white;");
                row.opacityProperty().bind(globalPulseOpacity);
            } else {
                row.setStyle("");
            }
        }
    }

    private void saveConfigs() {
        if (roomGroupsList.isEmpty() && fileQueue.isEmpty() && !rulesList.isEmpty()) {
            logger.warn("Prevented saveConfigs because both queue and room groups are empty (safety check).");
            // return; // Commented out for now to see if this is the cause
        }
        logger.info("Triggering saveConfigs. Queue size: {}, Room groups: {}", fileQueue.size(), roomGroupsList.size());
        
        // Save System Settings
        config.setRules(List.copyOf(rulesList));
        config.setSmartSplitRules(List.copyOf(smartSplitRulesList));
        configManager.saveConfig(config);
        
        // Save Dynamic Data
        appState.setFileQueue(new ArrayList<>(fileQueue));
        appState.setRoomGroups(new ArrayList<>(roomGroupsList));
        configManager.saveAppState(appState);
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
        
        Button clearRoomSearchBtn = new Button("Clear");
        clearRoomSearchBtn.setStyle("-fx-font-size: 14px; -fx-padding: 10;");
        clearRoomSearchBtn.setOnAction(e -> roomSearch.clear());
        HBox roomSearchBox = new HBox(5, roomSearch, clearRoomSearchBtn);
        roomSearchBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

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

        printCoverPageCbox.setSelected(config.isPrintCoverPage());
        printCoverPageCbox.setOnAction(e -> {
            config.setPrintCoverPage(printCoverPageCbox.isSelected());
            saveConfigs();
        });

        Label roomMapAlert = new Label("\uD83D\uDDFA MAP ALERT");
        roomMapAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-color: #800020; -fx-padding: 6px 12px; -fx-background-radius: 5px; -fx-border-color: #ffeb3b; -fx-border-width: 2px; -fx-border-radius: 5px;");
        roomMapAlert.setVisible(false);
        roomMapAlert.managedProperty().bind(roomMapAlert.visibleProperty());
        roomMapAlert.opacityProperty().bind(globalPulseOpacity);

        Label roomStapleAlert = new Label("\uD83D\uDCCE STAPLE ALERT");
        roomStapleAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-color: #212121; -fx-padding: 6px 12px; -fx-background-radius: 5px; -fx-border-color: white; -fx-border-width: 2px; -fx-border-radius: 5px;");
        roomStapleAlert.setVisible(false);
        roomStapleAlert.managedProperty().bind(roomStapleAlert.visibleProperty());
        roomStapleAlert.opacityProperty().bind(globalPulseOpacity);

        Label roomTotalPP = new Label("Total PP: 0");
        roomTotalPP.setStyle("-fx-font-weight: bold; -fx-text-fill: #E91E63; -fx-font-size: 16px;");

        Runnable updateRoomAlerts = () -> {
            boolean hasHistory = roomGroupsList.stream().flatMap(g -> g.getItems().stream()).anyMatch(i -> (i.getPdfFileName() != null && i.getPdfFileName().toLowerCase().contains("history")) || (i.getCourseName() != null && i.getCourseName().toLowerCase().contains("history")));
            boolean hasStaple = roomGroupsList.stream().flatMap(g -> g.getItems().stream())
                                  .anyMatch(i -> i.getMatchedFile() != null && "Booklet".equals(i.getMatchedFile().getStyle()) && calculatePP(i.getMatchedFile()) > 1);
            roomMapAlert.setVisible(hasHistory);
            roomStapleAlert.setVisible(hasStaple);

            int totalPPVal = 0;
            for (RoomGroup g : roomGroupsList) {
                for (RoomItem ri : g.getItems()) {
                    if (ri.getMatchedFile() != null) {
                        totalPPVal += calculatePP(ri.getMatchedFile()) * ri.getCount();
                    }
                }
            }
            roomTotalPP.setText("Total PP: " + totalPPVal);
        };
        roomGroupsList.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> {
            updateRoomAlerts.run();
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(g -> g.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c2 -> updateRoomAlerts.run()));
                }
            }
        });
        roomGroupsList.forEach(g -> g.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c -> updateRoomAlerts.run()));
        updateRoomAlerts.run();

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox roomAlertsBox = new HBox(8, roomTotalPP, roomMapAlert, roomStapleAlert);
        roomAlertsBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        HBox header = new HBox(20, uploadBtn, clearBlocksBtn, roomSearchBox, printCoverPageCbox, spacer, roomAlertsBox);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        VBox layout = new VBox(20, header, scrollPane);
        layout.setPadding(new Insets(20));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return layout;
    }

    private VBox createRoomCard(RoomGroup group) {
        VBox card = new VBox(12);
        String defaultStyle = "-fx-background-color: #ffffff; -fx-border-color: #e0e0e0; -fx-border-radius: 12; -fx-background-radius: 12; -fx-padding: 15; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.08), 15, 0, 0, 8);";
        String finishedStyle = "-fx-background-color: #f9fdf9; -fx-border-color: #4caf50; -fx-border-width: 2; -fx-border-radius: 12; -fx-background-radius: 12; -fx-padding: 15; -fx-effect: dropshadow(three-pass-box, rgba(76,175,80,0.15), 15, 0, 0, 8);";
        card.setStyle(group.getStatus() != null && group.getStatus().contains("Finished") ? finishedStyle : defaultStyle);
        card.setPrefWidth(420); // Maintain spacious width

        group.statusProperty().addListener((obs, old, val) -> {
            if (val != null && val.contains("Finished")) card.setStyle(finishedStyle);
            else card.setStyle(defaultStyle);
        });

        // Helper to recalculate room total from items, avoiding double-counting splits
        Runnable recalculateRoomTotal = () -> {
            // Group by sourceNodeId to ensure Main/MCQ splits are counted as one student set
            // Fallback to QP + Course name for already loaded data where sourceId might be missing
            java.util.Map<Object, Integer> nodeCounts = new java.util.HashMap<>();
            for (RoomItem item : group.getItems()) {
                Object key;
                int nid = item.getSourceNodeId();
                if (nid >= 0) {
                    key = nid;
                } else {
                    // Fallback key: Combination of QP and Course name
                    key = (item.getQpCode() != null ? item.getQpCode() : "") + "|" + 
                          (item.getCourseName() != null ? item.getCourseName() : "");
                }
                
                // For the same student set (node), we take the count once.
                // If counts differ (shouldn't happen), we take the max as a safety measure.
                nodeCounts.put(key, Math.max(nodeCounts.getOrDefault(key, 0), item.getCount()));
            }
            int sum = nodeCounts.values().stream().mapToInt(Integer::intValue).sum();
            
            // Only update if it's different to avoid binding loops
            if (group.getTotalStudents() != sum) {
                group.setTotalStudents(sum);
            }
        };

        // Reactive Total Qty calculation: Strictly use the RoomGroup's totalStudents property
        javafx.beans.binding.IntegerBinding totalQtyBinding = javafx.beans.binding.Bindings.createIntegerBinding(() -> {
            return group.getTotalStudents();
        }, group.totalStudentsProperty());

        Label title = new Label("Room: " + group.getRoomSerial());
        title.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");
        
        Label subtitle = new Label();
        subtitle.textProperty().bind(javafx.beans.binding.Bindings.concat("TOTAL STUDENTS: ", totalQtyBinding.asString()));
        subtitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2e7d32; -fx-padding: 5px 0;");
        
        // Attachment logic for count listeners (syncs split files and updates room total)
        java.util.function.Consumer<RoomItem> attachListener = ri -> {
            ri.countProperty().addListener((obs, old, val) -> {
                // Update siblings (same split source) to keep Main/MCQ counts in sync
                group.getItems().stream()
                     .filter(other -> other != ri && other.getSourceNodeId() == ri.getSourceNodeId() && ri.getSourceNodeId() >= 0)
                     .forEach(other -> other.setCount(val.intValue()));
                recalculateRoomTotal.run();
            });
        };

        // Attach to existing and future items
        group.getItems().forEach(attachListener);
        group.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c -> {
            while (c.next()) {
                if (c.wasAdded()) c.getAddedSubList().forEach(attachListener);
            }
        });
        
        // Initial calculation to ensure 0 is not shown if items already exist
        recalculateRoomTotal.run();

        VBox headerArea = new VBox(2, title, subtitle);
        headerArea.setAlignment(javafx.geometry.Pos.CENTER);
        headerArea.setPadding(new Insets(10, 0, 10, 0));

        TableView<RoomItem> table = new TableView<>(group.getItems());
        table.setPrefHeight(230);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setStyle("-fx-font-size: 11px;");

        TableColumn<RoomItem, String> qpCol = new TableColumn<>("QP");
        qpCol.setCellValueFactory(d -> {
            RoomItem ri = d.getValue();
            return javafx.beans.binding.Bindings.createStringBinding(() -> ri.getDisplayName(), ri.matchedFileProperty());
        });
        qpCol.setPrefWidth(90);
        qpCol.setCellFactory(tc -> new TableCell<RoomItem, String>() {
            private final Label label = new Label();
            private final Label icon = new Label();
            private final HBox container = new HBox(4, icon, label);
            { container.setAlignment(javafx.geometry.Pos.CENTER_LEFT); }
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setStyle(""); }
                else {
                    label.setText(item); label.setStyle("-fx-font-weight: bold;");
                    RoomItem ri = getTableRow().getItem();
                    boolean alert = isAlertRoomItem(ri);
                    if (ri != null && ri.getMatchedFile() != null) {
                        FileItem f = ri.getMatchedFile();
                        String name = f.getFileName().toLowerCase();
                        if (name.startsWith("split_")) { icon.setText("\u2702"); icon.setStyle("-fx-text-fill: " + (alert ? "white" : "#2196f3") + ";"); }
                        else if (name.startsWith("remain_")) { icon.setText("\u21BB"); icon.setStyle("-fx-text-fill: " + (alert ? "white" : "#ff9800") + ";"); }
                        else { icon.setText("\uD83D\uDCC4"); icon.setStyle("-fx-text-fill: " + (alert ? "white" : "#666") + ";"); }
                        if (alert) label.setStyle("-fx-font-weight: bold; -fx-text-fill: white;");
                        else label.setStyle("-fx-font-weight: bold; -fx-text-fill: black;");
                    } else { icon.setText("\u2757"); icon.setStyle("-fx-text-fill: #f44336;"); }
                    setGraphic(container);
                }
            }
        });
        
        TableColumn<RoomItem, String> styleCol = new TableColumn<>("M"); // Mode
        styleCol.setCellValueFactory(d -> {
            RoomItem ri = d.getValue();
            return javafx.beans.binding.Bindings.createStringBinding(() -> {
                FileItem f = ri.getMatchedFile();
                return f != null ? f.getStyle() : "-";
            }, ri.matchedFileProperty());
        });
        styleCol.setPrefWidth(35);
        styleCol.setCellFactory(tc -> new TableCell<RoomItem, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || "-".equals(item)) { setText(null); setStyle(""); }
                else {
                    String code = item.substring(0,1).toUpperCase();
                    setText(code); setAlignment(javafx.geometry.Pos.CENTER);
                    RoomItem ri = getTableRow().getItem();
                    boolean needStaple = ri != null && ri.getMatchedFile() != null && "Booklet".equals(ri.getMatchedFile().getStyle()) && calculatePP(ri.getMatchedFile()) > 1;
                    if (needStaple) setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
                    else if ("B".equals(code)) setStyle("-fx-text-fill: #e65100; -fx-font-weight: bold;");
                    else if ("D".equals(code)) setStyle("-fx-text-fill: #0d47a1; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: #2e7d32; -fx-font-weight: bold;");
                }
            }
        });

        TableColumn<RoomItem, String> ppCol = new TableColumn<>("PS"); // Printed Sheets
        ppCol.setPrefWidth(45);
        ppCol.setCellValueFactory(d -> {
            RoomItem ri = d.getValue();
            return javafx.beans.binding.Bindings.createStringBinding(() -> {
                FileItem f = ri.getMatchedFile();
                return (f == null) ? "-" : String.valueOf(calculatePP(f));
            }, ri.matchedFileProperty());
        });
        ppCol.setCellFactory(tc -> new TableCell<RoomItem, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else { 
                    setText(item); setAlignment(javafx.geometry.Pos.CENTER); 
                    RoomItem ri = getTableRow().getItem();
                    boolean needStaple = ri != null && ri.getMatchedFile() != null && "Booklet".equals(ri.getMatchedFile().getStyle()) && calculatePP(ri.getMatchedFile()) > 1;
                    setStyle("-fx-font-weight: bold; -fx-text-fill: " + (needStaple ? "white" : "#555") + ";");
                }
            }
        });

        TableColumn<RoomItem, Integer> countCol = new TableColumn<>("Q"); // Qty
        countCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().getCount()));
        countCol.setPrefWidth(40);
        countCol.setCellFactory(tc -> new TableCell<RoomItem, Integer>() {
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else { 
                    setText(String.valueOf(item)); setAlignment(javafx.geometry.Pos.CENTER); 
                    RoomItem ri = getTableRow().getItem();
                    boolean needStaple = ri != null && ri.getMatchedFile() != null && "Booklet".equals(ri.getMatchedFile().getStyle()) && calculatePP(ri.getMatchedFile()) > 1;
                    setStyle("-fx-font-weight: bold; -fx-text-fill: " + (needStaple ? "white" : "black") + ";");
                }
            }
        });

        TableColumn<RoomItem, String> statusCol = new TableColumn<>("S"); // Stat
        statusCol.setCellValueFactory(d -> d.getValue().statusProperty());
        statusCol.setPrefWidth(55);
        statusCol.setCellFactory(tc -> new TableCell<RoomItem, String>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); }
                else {
                    setText(item.startsWith("Fin") ? "OK" : (item.startsWith("Err") ? "ERR" : "PND")); 
                    setAlignment(javafx.geometry.Pos.CENTER);
                    RoomItem ri = getTableRow().getItem();
                    boolean needStaple = ri != null && ri.getMatchedFile() != null && "Booklet".equals(ri.getMatchedFile().getStyle()) && calculatePP(ri.getMatchedFile()) > 1;
                    if (item.contains("Finished")) setStyle("-fx-text-fill: " + (needStaple ? "#81c784" : "#4caf50") + "; -fx-font-weight: bold;");
                    else if (item.contains("Error")) setStyle("-fx-text-fill: #f44336; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: " + (needStaple ? "#ccc" : "#888") + ";");
                }
            }
        });

        TableColumn<RoomItem, Void> actionCol = new TableColumn<>("");
        actionCol.setPrefWidth(35);
        actionCol.setCellFactory(tc -> new TableCell<RoomItem, Void>() {
            private final Button btn = new Button("\u270F"); // Pencil Icon (Reliable)
            {
                btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #2196f3; -fx-font-size: 14px; -fx-padding: 0; -fx-cursor: hand;");
                btn.setOnAction(e -> {
                    RoomItem item = getTableRow().getItem();
                    if (item != null) {
                        TextInputDialog dialog = new TextInputDialog(String.valueOf(item.getCount()));
                        dialog.setTitle("Edit Qty");
                        dialog.setHeaderText("QP: " + item.getQpCode());
                        dialog.setContentText("Enter quantity:");
                        dialog.showAndWait().ifPresent(v -> {
                            try { 
                                int newQty = Integer.parseInt(v);
                                item.setCount(newQty); 
                                activityLogger.info("Updated Qty for " + item.getQpCode() + " to " + newQty);
                                saveConfigs();
                            } catch (Exception ex) {}
                        });
                    }
                });
            }
            @Override protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) { setGraphic(null); setStyle(""); }
                else {
                    RoomItem ri = getTableRow().getItem();
                    boolean needStaple = ri != null && ri.getMatchedFile() != null && "Booklet".equals(ri.getMatchedFile().getStyle()) && calculatePP(ri.getMatchedFile()) > 1;
                    btn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + (needStaple ? "white" : "#2196f3") + "; -fx-font-size: 14px; -fx-padding: 0; -fx-cursor: hand;");
                    setGraphic(btn);
                }
            }
        });

        table.getColumns().addAll(qpCol, styleCol, ppCol, countCol, statusCol, actionCol);

        table.setRowFactory(tv -> {
            TableRow<RoomItem> row = new TableRow<RoomItem>();
            
            // Helper to update row style based on item properties
            Runnable updateRowStyle = () -> {
                RoomItem item = row.getItem();
                row.styleProperty().unbind();
                row.opacityProperty().unbind();
                row.setOpacity(1.0);

                if (item == null) { row.setStyle(""); return; }

                boolean isMap = (item.getPdfFileName() != null && item.getPdfFileName().toLowerCase().contains("history")) || 
                                (item.getCourseName() != null && item.getCourseName().toLowerCase().contains("history"));
                
                boolean isStaple = false;
                if (item.getMatchedFile() != null) {
                    FileItem f = item.getMatchedFile();
                    isStaple = "Booklet".equals(f.getStyle()) && calculatePP(f) > 1;
                }

                if (item.statusProperty().get() != null && item.statusProperty().get().contains("Finished")) {
                    row.setStyle("-fx-background-color: #c8e6c9; -fx-text-inner-color: black;");
                } else if (item.statusProperty().get() != null && item.statusProperty().get().contains("Error")) {
                    row.setStyle("-fx-background-color: #ffcdd2; -fx-text-inner-color: black;");
                } else {
                    if (isMap && isStaple) {
                        row.styleProperty().bind(Bindings.when(dualAlertPhase)
                            .then("-fx-background-color: #800020; -fx-text-inner-color: white;")
                            .otherwise("-fx-background-color: #212121; -fx-text-inner-color: white;"));
                        row.opacityProperty().bind(globalPulseOpacity);
                    } else if (isMap) {
                        row.setStyle("-fx-background-color: #800020; -fx-text-inner-color: white;");
                        row.opacityProperty().bind(globalPulseOpacity);
                    } else if (isStaple) {
                        row.setStyle("-fx-background-color: #212121; -fx-text-inner-color: white;");
                        row.opacityProperty().bind(globalPulseOpacity);
                    } else {
                        if (item.getMatchedFile() != null) {
                            String name = item.getMatchedFile().getFileName().toLowerCase();
                            if (name.startsWith("split_")) row.setStyle("-fx-background-color: #f0f7ff; -fx-text-inner-color: black;");
                            else if (name.startsWith("remain_")) row.setStyle("-fx-background-color: #fffaf0; -fx-text-inner-color: black;");
                            else row.setStyle("");
                        } else {
                            row.setStyle("-fx-background-color: #fff9f9; -fx-text-inner-color: black;");
                        }
                    }
                }
            };

            row.itemProperty().addListener((obs, old, nv) -> {
                if (old != null) {
                    old.matchedFileProperty().removeListener((o, v, n) -> updateRowStyle.run());
                }
                if (nv != null) {
                    updateRowStyle.run();
                    nv.matchedFileProperty().addListener((o, v, n) -> {
                        updateRowStyle.run();
                        if (n != null) {
                            n.styleProperty().addListener((o2, v2, n2) -> updateRowStyle.run());
                            n.pageCountProperty().addListener((o2, v2, n2) -> updateRowStyle.run());
                        }
                    });
                    if (nv.getMatchedFile() != null) {
                        nv.getMatchedFile().styleProperty().addListener((o2, v2, n2) -> updateRowStyle.run());
                        nv.getMatchedFile().pageCountProperty().addListener((o2, v2, n2) -> updateRowStyle.run());
                    }
                } else {
                    row.setStyle("");
                }
            });

            row.setOnMouseClicked(event -> { if (event.getClickCount() == 2 && !row.isEmpty()) previewRoomItem(row.getItem()); });
            return row;
        });

        ComboBox<String> printerCombo = new ComboBox<>(FXCollections.observableArrayList(printService.getAvailablePrinters()));
        printerCombo.setPromptText("Assign Printer");
        printerCombo.setMaxWidth(Double.MAX_VALUE);
        printerCombo.setStyle("-fx-font-size: 13px;");
        
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
        // Button cell for closed state
        printerCombo.setButtonCell(printerCombo.getCellFactory().call(null));

        printerCombo.valueProperty().bindBidirectional(group.selectedPrinterProperty());

        Button sendBtn = new Button("SEND PRINT BATCH");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        String defaultBtnStyle = "-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 12; -fx-background-radius: 8;";
        String sendingBtnStyle = "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 12; -fx-background-radius: 8;";
        String sentBtnStyle = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 12; -fx-background-radius: 8;";
        String errorBtnStyle = "-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 12; -fx-background-radius: 8;";
        
        sendBtn.setStyle(defaultBtnStyle);
        sendBtn.setOnAction(e -> {
            if ("Sending...".equals(sendBtn.getText())) return;
            if ("Sent".equals(sendBtn.getText())) {
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Print Confirmation");
                alert.setHeaderText("Already Sent!");
                alert.setContentText("This room batch has already been sent to the printer.\nAre you sure you want to print it again?");
                alert.showAndWait().ifPresent(response -> {
                    if (response == ButtonType.OK) {
                        sendBtn.setText("Sending...");
                        sendBtn.setStyle(sendingBtnStyle);
                        sendBtn.setDisable(true);
                        printRoom(group);
                    }
                });
            } else {
                sendBtn.setText("Sending...");
                sendBtn.setStyle(sendingBtnStyle);
                sendBtn.setDisable(true);
                printRoom(group);
            }
        });

        group.statusProperty().addListener((obs, old, val) -> {
            Platform.runLater(() -> {
                if (val != null) {
                    if (val.contains("Sending")) {
                        sendBtn.setText("Sending...");
                        sendBtn.setStyle(sendingBtnStyle);
                        sendBtn.setDisable(true);
                    } else if (val.contains("Finished")) {
                        sendBtn.setText("Sent");
                        sendBtn.setStyle(sentBtnStyle);
                        sendBtn.setDisable(false);
                    } else if (val.contains("Error")) {
                        sendBtn.setText("Error - Try Again");
                        sendBtn.setStyle(errorBtnStyle);
                        sendBtn.setDisable(false);
                    } else {
                        sendBtn.setText("SEND PRINT BATCH");
                        sendBtn.setStyle(defaultBtnStyle);
                        if (!"Offline".equalsIgnoreCase(printerStatusCache.getOrDefault(group.getSelectedPrinter(), "Ready"))) {
                            sendBtn.setDisable(false);
                        }
                    }
                }
            });
        });

        if (group.getStatus() != null) {
            if (group.getStatus().contains("Sending")) {
                sendBtn.setText("Sending...");
                sendBtn.setStyle(sendingBtnStyle);
                sendBtn.setDisable(true);
            } else if (group.getStatus().contains("Finished")) {
                sendBtn.setText("Sent");
                sendBtn.setStyle(sentBtnStyle);
            } else if (group.getStatus().contains("Error")) {
                sendBtn.setText("Error - Try Again");
                sendBtn.setStyle(errorBtnStyle);
            }
        }

        Label roomStatus = new Label();
        roomStatus.textProperty().bind(group.statusProperty());
        roomStatus.setStyle("-fx-font-style: italic; -fx-text-fill: #888; -fx-font-size: 12px;");
        roomStatus.setMaxWidth(Double.MAX_VALUE); roomStatus.setAlignment(javafx.geometry.Pos.CENTER);

        Label printerIndicator = new Label();
        printerIndicator.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
        
        Runnable updateBtnState = () -> {
            String p = group.getSelectedPrinter();
            if (p != null && !"None".equals(p)) {
                String s = printerStatusCache.getOrDefault(p, "Ready");
                Platform.runLater(() -> {
                    if ("Offline".equalsIgnoreCase(s)) {
                        printerIndicator.setText("\u26A0 PRINTER OFFLINE");
                        printerIndicator.setStyle("-fx-text-fill: #f44336;");
                        if (!"Sending...".equals(sendBtn.getText())) sendBtn.setDisable(true);
                    } else {
                        printerIndicator.setText("\u2714 Printer Ready (" + p + ")");
                        printerIndicator.setStyle("-fx-text-fill: #4CAF50;");
                        if (!"Sending...".equals(sendBtn.getText())) sendBtn.setDisable(false);
                    }
                });
            } else {
                Platform.runLater(() -> { 
                    printerIndicator.setText(""); 
                    if (!"Sending...".equals(sendBtn.getText())) sendBtn.setDisable(false); 
                });
            }
        };

        group.selectedPrinterProperty().addListener((o, ov, nv) -> updateBtnState.run());
        Thread btnWatcher = new Thread(() -> {
            while (true) {
                try { Thread.sleep(2000); updateBtnState.run(); } 
                catch (InterruptedException e) { break; }
            }
        });
        btnWatcher.setDaemon(true);
        btnWatcher.start();

        card.getChildren().addAll(headerArea, table, printerCombo, printerIndicator, sendBtn, roomStatus);
        return card;
    }

    private javafx.scene.Parent createAboutView() {
        VBox mainLayout = new VBox(0);
        mainLayout.setStyle("-fx-background-color: #f8f9fa;");

        // Header Section
        VBox header = new VBox(10);
        header.setPadding(new Insets(40, 20, 40, 20));
        header.setAlignment(javafx.geometry.Pos.CENTER);
        header.setStyle("-fx-background-color: linear-gradient(to right, #1a237e, #283593);");

        Label title = new Label("Smart QP Print Manager");
        title.setStyle("-fx-font-size: 36px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label version = new Label("Professional Edition v3.4.1");
        version.setStyle("-fx-font-size: 18px; -fx-text-fill: #e8eaf6;");
        header.getChildren().addAll(title, version);

        // Content Sections
        VBox content = new VBox(30);
        content.setPadding(new Insets(30, 50, 50, 50));
        content.setMaxWidth(1000);

        content.getChildren().addAll(
            createManualSection("\uD83D\uDCDD Overview", 
                "The Smart QP Print Manager is an enterprise-grade solution designed to automate the complex workflow of examination paper printing. " +
                "It eliminates manual page counting, printer selection, and room-wise sorting, ensuring high accuracy and speed during peak exam periods."),

            createManualSection("\u2699\uFE0F 1. Core Workflow: The Print Queue",
                "• Add PDFs: Drag and drop or use the 'Add PDFs' button. The app immediately analyzes each file.\n" +
                "• Auto-Routing: Based on the page count and content, the app assigns a printer and print style (Simplex/Duplex/Booklet) automatically.\n" +
                "• Manual Override: You can change the number of copies, printer, or style directly in the table before printing.\n" +
                "• Batch Printing: Click 'Print All' to send all configured jobs to their respective printers sequentially."),

            createManualSection("\u2702\uFE0F 2. Smart Split Technology",
                "This advanced feature detects 'MCQ' or 'SDE' parts within a single PDF and splits them into two independent jobs:\n" +
                "• Main Part: Usually 3+ pages, routed for Booklet/Duplex printing with a QP Overlay applied to the top-left.\n" +
                "• MCQ Part: Detected via keywords, typically printed in Simplex or special 5-page modes.\n" +
                "• Split/Subtract: Use the 'eye' icon (\uD83D\uDC41) to manually split a file or subtract specific pages from an existing job."),

            createManualSection("\uD83D\uDCCB 3. Smart Room-Wise Router",
                "The most powerful feature for large-scale exams:\n" +
                "• Upload JSON: Import a seating arrangement file. The app automatically groups files by Room Number.\n" +
                "• Smart Matching: It matches the QP codes in the JSON with the files in your Print Queue.\n" +
                "• Dashboard: Each room gets a 'Card' showing all required papers, quantities, and real-time status.\n" +
                "• One-Click Room Print: Click 'SEND PRINT BATCH' on a room card to print everything for that room, including an optional status cover page."),

            createManualSection("\u2601\uFE0F 4. Examflow Cloud Integration",
                "Sync your local manager with the Examflow web platform:\n" +
                "• 1-Click Sync: When you click 'Sync to Print Manager' on the website, this app fetches the data using your College ID.\n" +
                "• Real-time Fetch: No need for JSON files; data flows directly from the cloud to your local router."),

            createManualSection("\uD83D\uDDA5\uFE0F 5. Printer Monitoring",
                "• Health Dashboard: Monitor 'Ready', 'Printing', or 'Offline' status of all connected hardware.\n" +
                "• Error Alerts: Instant notifications for Paper Jams, Low Toner, or Out of Paper scenarios.\n" +
                "• Simulation Mode: Use 'Settings' to enable Simulation Mode for training or testing without wasting paper."),

            createManualSection("\u2753 FAQ & Troubleshooting",
                "• Missing Files: Ensure your filenames contain the QP code (e.g., _123456_).\n" +
                "• Printer Offline: Check physical cables and ensure the printer is turned on. Windows must show it as 'Online'.\n" +
                "• JSON Format: The JSON must contain 'roomSerial', 'qpCode', and 'count' fields."),

            new Separator(),

            new VBox(10,
                new Label("System Configuration"),
                new HBox(10, new Label("Config Path:"), new TextField(configManager.getConfigPath()) {{ setEditable(false); setPrefWidth(600); }})
            )
        );

        ScrollPane sp = new ScrollPane(new VBox(header, new StackPane(content) {{ setAlignment(javafx.geometry.Pos.TOP_CENTER); }}));
        sp.setFitToWidth(true);
        return sp;
    }

    private VBox createManualSection(String title, String body) {
        VBox section = new VBox(10);
        Label lblTitle = new Label(title);
        lblTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");
        
        Label lblBody = new Label(body);
        lblBody.setStyle("-fx-font-size: 14px; -fx-text-fill: #333; -fx-line-spacing: 5px;");
        lblBody.setWrapText(true);
        lblBody.setMaxWidth(900);
        
        section.getChildren().addAll(lblTitle, lblBody);
        return section;
    }

    private void previewRoomItem(RoomItem item) {
        if (item.getMatchedFile() == null) { updateStatus("No matched file to preview."); return; }
        previewFile(item.getMatchedFile(), true, true);
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
        Map<String, Integer> qpTotalCounts = new HashMap<>();
        Set<FileItem> matchedFiles = new HashSet<>();
        int matchedItems = 0;
        int nodeIdCounter = 0;

        if (root.isArray()) {
            for (JsonNode node : root) {
                int currentNodeId = nodeIdCounter++;
                String roomSerial = node.path("roomSerial").asText("Unknown");
                String qpCode = node.path("qpCode").asText("");
                String pdfFileName = node.path("pdfFileName").asText("");
                String courseName = node.path("courseName").asText(pdfFileName);
                int count = node.path("count").asInt(0);
                
                int totalStudentsField = node.path("totalStudents").asInt(0);
                if (totalStudentsField == 0) totalStudentsField = node.path("totalCount").asInt(0);
                if (totalStudentsField == 0) totalStudentsField = node.path("roomTotal").asInt(0);

                RoomGroup group = groups.computeIfAbsent(roomSerial, RoomGroup::new);
                if (totalStudentsField > 0) {
                    group.setTotalStudents(totalStudentsField);
                } else {
                    // Accumulate counts from each JSON entry to get the total room population
                    group.setTotalStudents(group.getTotalStudents() + count);
                }

                if (!qpCode.isEmpty()) {
                    qpTotalCounts.put(qpCode, qpTotalCounts.getOrDefault(qpCode, 0) + count);
                }
                
                boolean matched = false;
                for (FileItem fileItem : fileQueue) {
                    String fileName = fileItem.getFileName();
                    String extractedQP = extractQPFromFileName(fileName);
                    if (qpCode.equalsIgnoreCase(extractedQP) || fileName.contains("_" + qpCode + "_") || fileName.contains("_" + qpCode + ".")) {
                        RoomItem roomItem = new RoomItem(roomSerial, qpCode, pdfFileName, count, currentNodeId);
                        roomItem.setCourseName(courseName);
                        roomItem.setMatchedFile(fileItem);
                        group.getItems().add(roomItem);
                        matched = true;
                        matchedItems++;
                        matchedFiles.add(fileItem);
                        activityLogger.info("Room " + roomSerial + ": Matched QP " + qpCode + " (" + fileName + ")");
                    }
                }
                if (!matched) {
                    RoomItem roomItem = new RoomItem(roomSerial, qpCode, pdfFileName, count, currentNodeId);
                    roomItem.setCourseName(courseName);
                    group.getItems().add(roomItem);
                    activityLogger.error("Room " + roomSerial + ": File NOT FOUND for QP " + qpCode);
                }
            }
        }

        // Alert for files in queue that have no routing in JSON
        for (FileItem item : fileQueue) {
            if (!matchedFiles.contains(item)) {
                activityLogger.warn("Queue File: " + item.getFileName() + " has NO routing entries in JSON.");
            }
        }

        // Sync total counts back to Print Queue
        for (Map.Entry<String, Integer> entry : qpTotalCounts.entrySet()) {
            String qpCode = entry.getKey();
            int totalCount = entry.getValue();
            for (FileItem item : fileQueue) {
                String fileName = item.getFileName();
                String extractedQP = extractQPFromFileName(fileName);
                if (qpCode.equalsIgnoreCase(extractedQP) || fileName.contains("_" + qpCode + "_") || fileName.contains("_" + qpCode + ".")) {
                    final int finalCount = totalCount;
                    Platform.runLater(() -> item.setCopies(finalCount));
                }
            }
        }

        final int finalMatched = matchedItems;
        final int finalGroupSize = groups.size();
        Platform.runLater(() -> {
            roomGroupsList.setAll(groups.values());
            updateStatus("Loaded " + finalGroupSize + " rooms from " + sourceName);
            activityLogger.success("Room Routing setup complete. " + finalGroupSize + " rooms created, " + finalMatched + " files matched and queue copies updated.");
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
                    FileItem fileItem = roomItem.getMatchedFile();
                    
                    if (fileItem == null || !fileQueue.contains(fileItem)) { 
                        Platform.runLater(() -> {
                            roomItem.setMatchedFile(null); // Explicitly clear if it's gone from queue
                            roomItem.setStatus("Missing in Queue");
                        });
                        activityLogger.error("Room " + group.getRoomSerial() + ": QP " + roomItem.getQpCode() + " is MISSING in Print Queue.");
                        allSuccess = false;
                        continue; 
                    }
                    
                    if (fileItem.getFile() == null || !fileItem.getFile().exists()) {
                        Platform.runLater(() -> roomItem.setStatus("File Not Found"));
                        activityLogger.error("Room " + group.getRoomSerial() + ": PDF File missing on disk for " + fileItem.getFileName());
                        allSuccess = false;
                        continue;
                    }

                    roomItem.setStatus("Printing...");
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
                    if (finalSuccess) activityLogger.success("Room " + group.getRoomSerial() + " printed successfully.");
                    else activityLogger.error("Room " + group.getRoomSerial() + " completed with ERRORS.");
                });
                synchronized (this) { saveConfigs(); }
            } catch (Exception e) { 
                Platform.runLater(() -> { group.setStatus("Error"); });
                synchronized (this) { saveConfigs(); }
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
                float pageWidth = page.getMediaBox().getWidth();
                
                // 1. Removed old title text per request

                // 2. Room Number - Centered, 96pt, in a square box with white background
                float roomFontSize = 96;
                org.apache.pdfbox.pdmodel.font.PDFont roomFont = new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_BOLD);
                String safeRoomSerial = group.getRoomSerial() != null ? group.getRoomSerial().replaceAll("[^\\x00-\\x7F]", "") : "";
                String roomText = safeRoomSerial; // Only the number
                float roomWidth = roomFont.getStringWidth(roomText) / 1000 * roomFontSize;
                
                // Draw white background box with border
                float boxPaddingX = 30f;
                float boxPaddingY = 15f;
                float boxWidth = roomWidth + 2 * boxPaddingX;
                float boxHeight = roomFontSize * 0.75f + 2 * boxPaddingY; // Approximate font cap height
                float boxX = (pageWidth - boxWidth) / 2;
                float boxY = yStart - 130 - (roomFontSize * 0.15f);
                
                cs.setNonStrokingColor(1f, 1f, 1f); // White fill
                cs.addRect(boxX, boxY, boxWidth, boxHeight);
                cs.fill();
                
                cs.setStrokingColor(0f, 0f, 0f); // Black border
                cs.setLineWidth(2f);
                cs.addRect(boxX, boxY, boxWidth, boxHeight);
                cs.stroke();
                
                cs.setNonStrokingColor(0f, 0f, 0f); // Black text
                cs.beginText();
                cs.setFont(roomFont, roomFontSize);
                cs.newLineAtOffset((pageWidth - roomWidth) / 2, yStart - 130);
                cs.showText(roomText);
                cs.endText();

                // 3. Subtitle - Centered
                float subFontSize = 12;
                org.apache.pdfbox.pdmodel.font.PDFont subFont = new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA_OBLIQUE);
                String subText = "Question Paper Account";
                float subWidth = subFont.getStringWidth(subText) / 1000 * subFontSize;
                cs.beginText();
                cs.setFont(subFont, subFontSize);
                cs.newLineAtOffset((pageWidth - subWidth) / 2, yStart - 160);
                cs.showText(subText);
                cs.endText();

                float yPosition = yStart - 190;
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
            
            // Adjust font size to fit width instead of truncating (abridging)
            float currentFontSize = fontSize;
            float textWidth = font.getStringWidth(text) / 1000 * currentFontSize;
            while (textWidth > widths[i] - 10 && currentFontSize > 4f) {
                currentFontSize -= 0.5f;
                textWidth = font.getStringWidth(text) / 1000 * currentFontSize;
            }
            
            cs.setFont(font, currentFontSize);
            float textX = curX + (widths[i] - textWidth) / 2;
            float textY = y - height + (height - currentFontSize) / 2 + 2;
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
                    new File(cachePath, "SingletonSocket").delete();
                    // SingletonCookie is KEPT to preserve login sessions
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
                    "--enable-password-manager",
                    "--persist-session-cookies",
                    "--restore-last-session",
                    "--enable-aggressive-domstorage-flushing",
                    "--ignore-certificate-errors",
                    "--disable-site-isolation-trials",
                    "--disable-features=IsolateOrigins,site-per-process",
                    "--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
                            if (parts.length >= 2) { 
                                String fileId = parts[0];
                                String fileName = parts[1];
                                downloadFilenameMap.put(fileId, fileName);
                                // Trigger standard browser download by loading the URL
                                browser.loadURL("https://collegeportal.uoc.ac.in/valuation_camp/downloadqp_file?fileid=" + fileId);
                                callback.success("OK"); 
                                return true; 
                            }
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
                        } else if (request.equals("request_portal_data")) {
                            CefBrowser portalBrowser = null;
                            for (CefBrowser b : browserAddressBars.keySet()) {
                                String url = b.getURL().toLowerCase().replace("%20", " ");
                                if (url.contains("collegeportal.uoc.ac.in") || 
                                    url.contains("centralized college portal") || 
                                    url.contains("qpcollegehome") || 
                                    url.contains("downloadqp") || 
                                    url.contains("qp page") || 
                                    url.contains("qp downloadpage")) { 
                                    portalBrowser = b; 
                                    break; 
                                }
                            }
                            if (portalBrowser == null) {
                                for (CefBrowser b : browserAddressBars.keySet()) {
                                    if (b != browser) {
                                        portalBrowser = b;
                                        break;
                                    }
                                }
                            }
                            if (portalBrowser == null) {
                                callback.failure(1, "University Portal tab not found.");
                            } else {
                                String qpPrefix = config.getPortalQpPrefix() != null ? config.getPortalQpPrefix().trim().toUpperCase() : "";
                                String scraper = "(function() { " +
                                    "  try { " +
                                    "    var prefix = '" + qpPrefix + "'; " +
                                    "    var rows = document.querySelectorAll('table tr'); " +
                                    "    var results = []; " +
                                    "    var cols = (function() { " +
                                    "      var hr = Array.from(document.querySelectorAll('table tr')).find(function(r){return r.querySelector('th');}) || document.querySelector('table tr'); " +
                                    "      var hs = hr ? Array.from(hr.querySelectorAll('th, td')).map(function(h){return h.innerText.toLowerCase().trim();}) : []; " +
                                    "      var q = 0, p = 1, t = 2, sy = -1; " +
                                    "      for(var col=0; col<hs.length; col++) { " +
                                    "        var txt = hs[col]; " +
                                    "        if(txt.indexOf('qp') !== -1 || txt.indexOf('code') !== -1) q = col; " +
                                    "        else if(txt.indexOf('paper') !== -1 || txt.indexOf('subject') !== -1 || txt.indexOf('title') !== -1) p = col; " +
                                    "        else if(txt.indexOf('time') !== -1) t = col; " +
                                    "        else if(txt.indexOf('syllabus') !== -1 || txt.indexOf('year') !== -1) sy = col; " +
                                    "      } " +
                                    "      return {qp: q, paper: p, time: t, syllabus: sy}; " +
                                    "    })(); " +
                                    "    rows.forEach(function(row) { " +
                                    "      var cells = row.querySelectorAll('td'); " +
                                    "      if (cells.length >= 2) { " +
                                    "        var qpVal = ''; var pVal = ''; var syVal = ''; " +
                                    "        if (row.cells[cols.qp] && /^\\d+$/.test(row.cells[cols.qp].innerText.trim())) { " +
                                    "          qpVal = row.cells[cols.qp].innerText.trim(); " +
                                    "          pVal = row.cells[cols.paper] ? row.cells[cols.paper].innerText.trim() : ''; " +
                                    "          syVal = (cols.syllabus !== -1 && row.cells[cols.syllabus]) ? row.cells[cols.syllabus].innerText.trim().replace(/[()]/g, '') : ''; " +
                                    "          if (syVal && pVal.indexOf(syVal) === -1) pVal = pVal + ' ' + syVal; " +
                                    "          results.push(prefix + qpVal + '\\t' + pVal); " +
                                    "        } else if (/^\\d+$/.test(cells[0].innerText.trim())) { " +
                                    "          qpVal = cells[0].innerText.trim(); " +
                                    "          pVal = cells[1].innerText.trim(); " +
                                    "          syVal = (cols.syllabus !== -1 && cells.length > cols.syllabus) ? cells[cols.syllabus].innerText.trim().replace(/[()]/g, '') : ''; " +
                                    "          if (syVal && pVal.indexOf(syVal) === -1) pVal = pVal + ' ' + syVal; " +
                                    "          results.push(prefix + qpVal + '\\t' + pVal); " +
                                    "        } else if (/^\\d+$/.test(cells[1].innerText.trim()) && cells.length >= 3) { " +
                                    "          qpVal = cells[1].innerText.trim(); " +
                                    "          pVal = cells[2].innerText.trim(); " +
                                    "          syVal = (cols.syllabus !== -1 && cells.length > cols.syllabus) ? cells[cols.syllabus].innerText.trim().replace(/[()]/g, '') : ''; " +
                                    "          if (syVal && pVal.indexOf(syVal) === -1) pVal = pVal + ' ' + syVal; " +
                                    "          results.push(prefix + qpVal + '\\t' + pVal); " +
                                    "        } " +
                                    "      } " +
                                    "    }); " +                                    "    if (results.length > 0) { window.cefQuery({request: 'portal_data_relay:' + results.join('\\n')}); } " +
                                    "    else { window.cefQuery({request: 'portal_error:NO DATA FOUND'}); } " +
                                    "  } catch(e) { window.cefQuery({request: 'portal_error:SCRAPE FAILED'}); } " +
                                    "})();";
                                portalBrowser.executeJavaScript(scraper, portalBrowser.getURL(), 0);
                                callback.success("OK");
                            }
                            return true;
                        } else if (request.startsWith("portal_data_relay:")) {
                            String data = request.substring(18);
                            for (CefBrowser b : browserAddressBars.keySet()) {
                                if (b.getURL().contains("examflow-india.web.app") || b.getURL().contains("localhost")) {
                                    b.executeJavaScript("if (window.receivePortalData) window.receivePortalData(`" + data.replace("`", "\\`") + "`);", b.getURL(), 0);
                                }
                            }
                            callback.success("OK"); return true;
                        } else if (request.startsWith("portal_error:")) {
                            String err = request.substring(13);
                            for (CefBrowser b : browserAddressBars.keySet()) {
                                if (b.getURL().contains("examflow-india.web.app") || b.getURL().contains("localhost")) {
                                    b.executeJavaScript("if (window.portalError) window.portalError('" + err + "');", b.getURL(), 0);
                                }
                            }
                            callback.success("OK"); return true;
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
                        
                        String url = downloadItem.getURL();
                        String fileName = suggestedName;
                        
                        // Check if this download was registered with a custom name
                        if (url.contains("fileid=")) {
                            String fileId = null;
                            try {
                                String search = "fileid=";
                                int start = url.indexOf(search) + search.length();
                                int end = url.indexOf("&", start);
                                fileId = (end == -1) ? url.substring(start) : url.substring(start, end);
                            } catch (Exception e) {}
                            
                        if (fileId != null && downloadFilenameMap.containsKey(fileId)) {
                                fileName = downloadFilenameMap.get(fileId) + ".pdf";
                                downloadFilenameMap.remove(fileId);
                                logger.info("Applying mapped filename: {} for fileid: {}", fileName, fileId);
                            }
                        }

                        File targetFile = new File(sessionDir, fileName);
                        if (targetFile.exists()) {
                            boolean deleted = targetFile.delete();
                            if (!deleted) {
                                // Try renaming first (move aside) to break locks
                                File trash = new File(sessionDir, "old_" + System.currentTimeMillis() + "_" + fileName);
                                if (targetFile.renameTo(trash)) {
                                    trash.delete(); // Try to delete the moved file
                                    logger.info("Replaced existing file (via rename): {}", fileName);
                                } else {
                                    logger.warn("CRITICAL: Could not replace existing file {}. Lock detected.", fileName);
                                }
                            } else {
                                logger.info("Replaced existing file (direct delete): {}", fileName);
                            }
                        }

                        callback.Continue(targetFile.getAbsolutePath(), false); return true;
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
                             String u = config.getPortalUsername();
                             String p = config.getPortalPassword();
                             String qpPassVal = config.getPortalQpPassword();
                             
                             String escapedUser = u != null ? u.replace("\\", "\\\\").replace("'", "\\'") : "";
                             String escapedPass = p != null ? p.replace("\\", "\\\\").replace("'", "\\'") : "";
                             String escapedQpPass = qpPassVal != null ? qpPassVal.replace("\\", "\\\\").replace("'", "\\'") : "";
                             
                             StringBuilder autofillBuilder = new StringBuilder();
                              if (u != null && !u.isEmpty() && p != null && !p.isEmpty()) {
                                  autofillBuilder.append(
                                      "    if (window.location.href.indexOf('collegeportal.uoc.ac.in') !== -1 || " +
                                      "        window.location.href.indexOf('Centralized') !== -1 || " +
                                      "        window.location.href.indexOf('Portal.html') !== -1) { " +
                                      "      var user = document.getElementById('id_username') || document.getElementById('username') || document.querySelector('input[name=\"username\"]'); " +
                                      "      var pass = document.getElementById('id_password') || document.getElementById('password') || document.querySelector('input[name=\"password\"]'); " +
                                      "      if (user && pass && !user.value) { " +
                                      "        user.value = '" + escapedUser + "'; " +
                                      "        pass.value = '" + escapedPass + "'; " +
                                      "        var evt1 = document.createEvent('HTMLEvents'); evt1.initEvent('input', true, true); user.dispatchEvent(evt1); " +
                                      "        var evt2 = document.createEvent('HTMLEvents'); evt2.initEvent('change', true, true); user.dispatchEvent(evt2); " +
                                      "        var evt3 = document.createEvent('HTMLEvents'); evt3.initEvent('input', true, true); pass.dispatchEvent(evt3); " +
                                      "        var evt4 = document.createEvent('HTMLEvents'); evt4.initEvent('change', true, true); pass.dispatchEvent(evt4); " +
                                      "      } " +
                                      "    } "
                                  );
                              }
                              if (qpPassVal != null && !qpPassVal.isEmpty()) {
                                  autofillBuilder.append(
                                      "    if (window.location.href.indexOf('valuation_camp/qpcollegehome') !== -1 || window.location.href.indexOf('QP%20Page/Centralized%20College%20Portal.html') !== -1 || window.location.href.indexOf('QP Page/Centralized College Portal.html') !== -1) { " +
                                      "      var qpPass = document.querySelector('input[type=\"password\"][name=\"password\"]'); " +
                                      "      var declare = document.getElementById('declare'); " +
                                      "      var submitBtn = document.getElementById('btn_submit'); " +
                                      "      if (qpPass && !qpPass.value) { " +
                                      "        qpPass.value = '" + escapedQpPass + "'; " +
                                      "        if (declare && !declare.checked) { " +
                                      "          declare.click(); " +
                                      "        } " +
                                      "        if (submitBtn) { " +
                                      "          submitBtn.removeAttribute('disabled'); " +
                                      "          setTimeout(function() { submitBtn.click(); }, 500); " +
                                      "        } " +
                                      "      } " +
                                      "    } "
                                  );
                              }
                              String autofillScript = autofillBuilder.toString();

                            String injectionScript = 
                                "(function() { " +
                                "  setInterval(function() { " +
                                "    try { " +
                                autofillScript +
                                "      var btns = document.querySelectorAll('.btn_download'); " +
                                "      if (btns.length > 0) { " +
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
                                "        var cols = (function() { " +
                                "          var hr = Array.from(document.querySelectorAll('table tr')).find(function(r){return r.querySelector('th');}) || document.querySelector('table tr'); " +
                                "          var hs = hr ? Array.from(hr.querySelectorAll('th, td')).map(function(h){return h.innerText.toLowerCase().trim();}) : []; " +
                                "          var q = 0, p = 1, t = 2; " +
                                "          for(var col=0; col<hs.length; col++) { " +
                                "            var txt = hs[col]; " +
                                "            if(txt.indexOf('qp') !== -1 || txt.indexOf('code') !== -1) q = col; " +
                                "            else if(txt.indexOf('paper') !== -1 || txt.indexOf('subject') !== -1 || txt.indexOf('title') !== -1) p = col; " +
                                "            else if(txt.indexOf('time') !== -1) t = col; " +
                                "          } " +
                                "          return {qp: q, paper: p, time: t}; " +
                                "        })(); " +
                                "        var tText = (function() { " +
                                "          var rows = document.querySelectorAll('table tr'); " +
                                "          for (var i = 0; i < rows.length; i++) { " +
                                "            if (!rows[i].querySelector('th') && rows[i].cells[cols.time]) return rows[i].cells[cols.time].innerText.trim(); " +
                                "          } " +
                                "          return ''; " +
                                "        })(); " +
                                "        if (raw && (window._lastDate !== raw || window._lastTime !== tText)) { " +
                                "          window._lastDate = raw; " +
                                "          window._lastTime = tText; " +
                                "          var dParts = raw.split(/[/\\-.]/); " +
                                "          var formattedDate = ''; " +
                                "          if (dParts.length === 3) { " +
                                "            if (dParts[0].length === 4) formattedDate = dParts[2] + '-' + dParts[1] + '-' + dParts[0]; " +
                                "            else formattedDate = dParts[0] + '-' + dParts[1] + '-' + dParts[2]; " +
                                "          } " +
                                "          if (formattedDate && tText) { " +
                                "            var sessionKey = formattedDate + ' | ' + tText; " +
                                "            window.cefQuery({ request: 'session:' + sessionKey }); " +
                                "          } " +
                                "        } " +
                                "        if (!document.getElementById('smart-bulk-header')) { " +
                                "          var h = document.createElement('div'); h.id = 'smart-bulk-header'; " +
                                "          h.style.cssText = 'background:#f8f9fa; padding:15px; margin-bottom:20px; border:1px solid #dee2e6; border-radius:8px; display:flex; align-items:center; justify-content:space-between; box-shadow:0 2px 4px rgba(0,0,0,0.05);'; " +
                                "          var t = document.createElement('span'); t.innerText = 'Smart Print: Found ' + btns.length + ' Question Papers'; t.style.fontWeight = 'bold'; " +
                                "          var b = document.createElement('button'); b.id = 'smart-bulk-btn'; b.innerText = 'Start Bulk Download & Queue'; " +
                                "          b.style.cssText = 'background:#28a745; color:white; padding:10px 20px; border-radius:5px; cursor:pointer; border:none; fontWeight:bold;'; " +
                                "          b.onclick = function() { " +
                                "            window.cefQuery({ request: 'check_session', onSuccess: function(res) { " +
                                "              if(res === 'MISSING') { alert('Please enter Detected Session name in the App first!'); return; } " +
                                "              if(!confirm('Start automated download for ' + btns.length + ' papers? (2s delay per file)')) return; " +
                                "              b.disabled = true; " +
                                "              var delayBase = 2000; " +
                                "              for (var i = 0; i < btns.length; i++) { " +
                                "                (function(idx) { setTimeout(function() { " +
                                "                  var progress = (idx + 1) + '/' + btns.length; " +
                                "                  b.innerText = '⌛ Processing ' + progress + '...'; " +
                                "                  var cur = btns[idx]; var r = cur.closest('tr'); " +
                                "                  var tVal = (r.cells[cols.time]) ? r.cells[cols.time].innerText.replace(/:/g, '_').replace(/\\\\s+/g, '_') : '00_00_AM'; " +
                                "                  var dp = (window._lastDate || '').replace(/[/\\\\-]/g, '.'); " +
                                "                  if (!dp) { var d = new Date(); dp = ('0' + d.getDate()).slice(-2) + '.' + ('0' + (d.getMonth()+1)).slice(-2) + '.' + (d.getFullYear()+'').substring(2); } " +
                                "                  var rowText = r.innerText.toUpperCase(); var prefix = (rowText.indexOf('EDE') !== -1 || rowText.indexOf('EXTERNAL') !== -1 || rowText.indexOf('SDE') !== -1) ? 'EDE' : 'REG'; " +
                                "                  var qpCode = (r.cells[cols.qp]) ? r.cells[cols.qp].innerText.trim() : '000000'; " +
                                "                  var paperName = (r.cells[cols.paper]) ? r.cells[cols.paper].innerText.replace(/[^a-z0-9]/gi, '_') : 'Subject'; " +
                                "                  var fname = prefix + '_' + dp + '_' + tVal + '_' + qpCode + '_' + paperName; " +
                                "                  window.cefQuery({ request: 'download:' + cur.value.trim() + '|' + fname }); " +
                                "                  if (idx === btns.length - 1) { b.innerText = '✓ All Files Queued'; setTimeout(function() { b.innerText = 'Start Bulk Download & Queue'; b.disabled = false; }, 3000); } " +
                                "                }, idx * delayBase); })(i); " +
                                "              } " +
                                "            }}); " +
                                "          }; " +
                                "          h.appendChild(t); h.appendChild(b); " +
                                "          var target = document.querySelector('.table-responsive') || document.querySelector('table') || document.body.firstChild; " +
                                "          if(target) target.parentNode.insertBefore(h, target); " +
                                "        } " +
                                "      } " +
                                "      if (window.location.href.indexOf('examflow-india.web.app') !== -1 || window.location.href.indexOf('localhost') !== -1) { " +
                                "        if (!window.receivePortalData) { " +
                                "          window.receivePortalData = function(text) { " +
                                "            var lines = text.split('\\n').map(function(l){return l.trim();}).filter(function(l){return l.length > 0;}); " +
                                "            var parsedPairs = []; " +
                                "            lines.forEach(function(line) { " +
                                "              var parts = line.split('\\t').map(function(p){return p.trim();}); " +
                                "              if (parts.length >= 2) { " +
                                "                var qpCode = parts[0]; " +
                                "                var paperName = parts[1]; " +
                                "                var finalQpCode = qpCode.toUpperCase().replace(/\\s+/g, ''); " +
                                "                parsedPairs.push({ " +
                                "                  searchText: paperName.toUpperCase(), " +
                                "                  code: finalQpCode, " +
                                "                  isEde: finalQpCode.endsWith('A') " +
                                "                }); " +
                                "              } " +
                                "            }); " +
                                "            if (parsedPairs.length === 0) return; " +
                                "            var matched = 0; " +
                                "            function sanitizeCourse(name) { " +
                                "              if (!name) return ''; " +
                                "              return name.replace(/[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]/g, ' ') " +
                                "                         .replace(/[^a-zA-Z0-9\\s()+\\-]/g, '') " +
                                "                         .replace(/\\s+/g, ' ') " +
                                "                         .trim(); " +
                                "            } " +
                                "            document.querySelectorAll('#qp-code-container input[data-course]').forEach(function(input) { " +
                                "              var uiCourseName = sanitizeCourse(input.dataset.course).trim().toUpperCase(); " +
                                "              var streamName = (input.dataset.stream || '').toUpperCase(); " +
                                "              var isEdeStream = streamName.indexOf('EDE') !== -1; " +
                                "              var validPairs = parsedPairs.filter(function(p){return p.isEde === isEdeStream;}); " +
                                "              if (validPairs.length === 0) validPairs = parsedPairs; " +
                                "              var bestMatch = null; " +
                                "              bestMatch = validPairs.find(function(p){return p.searchText.indexOf(uiCourseName) !== -1 || uiCourseName.indexOf(p.searchText) !== -1;}); " +
                                "              if (!bestMatch) { " +
                                "                var words = uiCourseName.split(/[\\s,.-]+/).filter(function(w){return w.length > 2;}); " +
                                "                if (words.length > 0) { " +
                                "                  var bestScore = 0; " +
                                "                  validPairs.forEach(function(p) { " +
                                "                    var score = 0; " +
                                "                    words.forEach(function(w){ if(p.searchText.indexOf(w) !== -1) score++; }); " +
                                "                    if (score > bestScore) { " +
                                "                      bestScore = score; " +
                                "                      bestMatch = p; " +
                                "                    } " +
                                "                  }); " +
                                "                  if (bestScore < 1) bestMatch = null; " +
                                "                } " +
                                "              } " +
                                "              if (bestMatch) { " +
                                "                input.value = bestMatch.code; " +
                                "                matched++; " +
                                "                var evt = document.createEvent('HTMLEvents'); " +
                                "                evt.initEvent('input', true, true); " +
                                "                input.dispatchEvent(evt); " +
                                "                var evt2 = document.createEvent('HTMLEvents'); " +
                                "                evt2.initEvent('change', true, true); " +
                                "                input.dispatchEvent(evt2); " +
                                "              } " +
                                "            }); " +
                                "            if (matched > 0) { " +
                                "              var st = document.getElementById('qp-code-status'); " +
                                "              if (st) { " +
                                "                st.style.color = '#28a745'; " +
                                "                st.innerText = '✅ Auto-filled ' + matched + ' codes from Portal. Click SAVE QP Codes below.'; " +
                                "              } " +
                                "            } " +
                                "          }; " +
                                "        } " +
                                "        window.portalError = function(msg) { " +
                                "             var fb = document.getElementById('smart-fetch-btn'); " +
                                "             if (fb) { " +
                                "                fb.innerText = '❌ ' + msg.toUpperCase(); " +
                                "                fb.style.background = '#f44336'; " +
                                "                setTimeout(function(){ fb.innerText = 'FETCH FROM PORTAL TAB'; fb.style.background = '#4CAF50'; }, 4000); " +
                                "             } " +
                                "          }; " +
                                "        var qpT = document.getElementById('view-qpcodes'); " +
                                "        if (qpT && !qpT.classList.contains('hidden')) { " +
                                "          if (!document.getElementById('smart-fetch-btn')) { " +
                                "            var header = qpT.querySelector('h2') || qpT.querySelector('h1'); " +
                                "            if (header) { " +
                                "              var fb = document.createElement('button'); fb.id = 'smart-fetch-btn'; fb.innerText = 'FETCH FROM PORTAL TAB'; " +
                                "              fb.style.cssText = 'font-size:12px; background:#4CAF50; color:white; border:none; padding:8px 15px; border-radius:4px; margin:10px 0; cursor:pointer; font-weight:bold; width:100%; display:block;'; " +
                                "              fb.onclick = function() { " +
                                "                fb.innerText = '⌛ Connecting...'; fb.style.background = '#ff9800'; " +
                                "                window.cefQuery({ request: 'request_portal_data', " +
                                "                  onSuccess: function() { fb.innerText = '✔ Sync Sent'; fb.style.background = '#4CAF50'; setTimeout(function(){ fb.innerText = 'FETCH FROM PORTAL TAB'; }, 3000); }, " +
                                "                  onFailure: function(e, m) { fb.innerText = '❌ ' + m.toUpperCase(); fb.style.background = '#f44336'; setTimeout(function(){ fb.innerText = 'FETCH FROM PORTAL TAB'; fb.style.background = '#4CAF50'; }, 4000); } " +
                                "                }); " +
                                "              }; " +
                                "              header.parentNode.insertBefore(fb, header.nextSibling); " +
                                "            } " +
                                "          } " +
                                "        } " +
                                "        var sync = Array.from(document.querySelectorAll('button')).find(function(el) { return el.innerText.indexOf('Sync to Print Manager') !== -1; }); " +
                                "        if (sync && !sync.getAttribute('data-jcef')) { " +
                                "          sync.setAttribute('data-jcef', 'true'); " +
                                "          sync.addEventListener('click', function() { " +
                                "            window.cefQuery({ request: 'examflow_sync_start' }); " +
                                "            setTimeout(function() { window.cefQuery({ request: 'examflow_sync' }); }, 2500); " +
                                "          }); " +
                                "        } " +
                                "      } " +
                                "    } catch(e) { console.error('Smart Injection Error:', e); } " +
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
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            if (appIcon != null) {
                try {
                    frame.setIconImage(javafx.embed.swing.SwingFXUtils.fromFXImage(appIcon, null));
                } catch (Exception ex) { logger.warn("Could not set Swing frame icon"); }
            }
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
                    frame.dispose();
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
        JButton syncBtn = new JButton("Sync Files");
        
        backBtn.addActionListener(e -> browser.goBack());
        forwardBtn.addActionListener(e -> browser.goForward());
        refreshBtn.addActionListener(e -> {
            logger.info("Browser reload triggered via browser.reloadIgnoreCache()");
            activityLogger.info("Reloading browser tab (Hard Refresh)...");
            browser.reloadIgnoreCache();
        });
        syncBtn.addActionListener(e -> {
            Platform.runLater(this::syncSessionFolder);
        });
        
        // Make address bar editable and clear
        addressBar.setEditable(true);
        addressBar.addActionListener(e -> {
            String targetUrl = addressBar.getText().trim();
            if (!targetUrl.isEmpty()) {
                if (!targetUrl.startsWith("http")) targetUrl = "https://" + targetUrl;
                activityLogger.info("Navigating to: " + targetUrl);
                browser.loadURL(targetUrl);
            }
        });
        
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(backBtn);
        toolBar.add(forwardBtn);
        toolBar.add(refreshBtn);
        toolBar.addSeparator();
        toolBar.add(syncBtn);
        toolBar.addSeparator();
        toolBar.add(addressBar);
        
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(toolBar, BorderLayout.NORTH);
        panel.add(browser.getUIComponent(), BorderLayout.CENTER);
        return panel;
    }

    private void syncSessionFolder() {
        File dir = getSessionDir();
        if (dir == null || !dir.exists()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "Session folder not found!").show());
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (files != null) {
            activityLogger.info("Syncing session folder: Found " + files.length + " PDFs.");
            for (File f : files) processFile(f);
        }
    }

    private javafx.scene.Parent createPortalView() {
        VBox layout = new VBox(15); layout.setAlignment(javafx.geometry.Pos.CENTER); layout.setPadding(new Insets(30));
        Label info = new Label("University Portal Downloader"); info.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        sessionNameField.setPromptText("Auto-detected after launch..."); sessionNameField.setPrefWidth(300);
        HBox sessionBox = new HBox(10, new Label("Detected Session:"), sessionNameField); sessionBox.setAlignment(javafx.geometry.Pos.CENTER);
        downloadPathField.setPromptText("Select Save Folder"); downloadPathField.setPrefWidth(400);
        downloadPathField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { config.setBaseDownloadPath(downloadPathField.getText().trim()); saveConfigs(); }
        });
        Button browseBtn = new Button("Browse...");
        browseBtn.setOnAction(e -> {
            javafx.stage.DirectoryChooser dc = new javafx.stage.DirectoryChooser(); dc.setTitle("Select Save Folder");
            File initial = new File(downloadPathField.getText()); if (initial.exists()) dc.setInitialDirectory(initial);
            File selected = dc.showDialog(null); if (selected != null) { downloadPathField.setText(selected.getAbsolutePath()); config.setBaseDownloadPath(selected.getAbsolutePath()); saveConfigs(); }
        });
        HBox pathBox = new HBox(10, new Label("Save Folder:"), downloadPathField, browseBtn); pathBox.setAlignment(javafx.geometry.Pos.CENTER);
        
        Button syncBtn = new Button("Sync Downloaded Files");
        syncBtn.setStyle("-fx-font-size: 16px; -fx-padding: 10 20;");
        syncBtn.setTooltip(new Tooltip("Scan the current session folder and re-process all PDFs (Overwrites existing queue entries)"));
        syncBtn.setOnAction(e -> syncSessionFolder());

        // Portal Login Credentials (Moved from Settings)
        TextField portalUserField = new TextField(config.getPortalUsername());
        portalUserField.setPromptText("University Portal Username");
        portalUserField.setPrefWidth(200);
        portalUserField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { config.setPortalUsername(portalUserField.getText().trim()); saveConfigs(); }
        });

        PasswordField portalPassField = new PasswordField();
        portalPassField.setText(config.getPortalPassword());
        portalPassField.setPromptText("University Portal Password");
        portalPassField.setPrefWidth(200);
        portalPassField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { config.setPortalPassword(portalPassField.getText()); saveConfigs(); }
        });

        PasswordField portalQpPassField = new PasswordField();
        portalQpPassField.setText(config.getPortalQpPassword());
        portalQpPassField.setPromptText("QP Module Password");
        portalQpPassField.setPrefWidth(200);
        portalQpPassField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { config.setPortalQpPassword(portalQpPassField.getText()); saveConfigs(); }
        });

        TextField portalQpPrefixField = new TextField(config.getPortalQpPrefix());
        portalQpPrefixField.setPromptText("QP Prefix (e.g. D)");
        portalQpPrefixField.setPrefWidth(120);
        portalQpPrefixField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (!newVal) { config.setPortalQpPrefix(portalQpPrefixField.getText().trim()); saveConfigs(); }
        });

        Button savePortalBtn = new Button("Save Credentials");
        savePortalBtn.setOnAction(e -> {
            config.setPortalUsername(portalUserField.getText().trim());
            config.setPortalPassword(portalPassField.getText());
            config.setPortalQpPassword(portalQpPassField.getText());
            config.setPortalQpPrefix(portalQpPrefixField.getText().trim());
            saveConfigs();
            activityLogger.info("Portal credentials saved.");
        });
        HBox portalCredsBox = new HBox(10, 
            new Label("Username:"), portalUserField, 
            new Label("Password:"), portalPassField, 
            new Label("QP Password:"), portalQpPassField, 
            new Label("QP Prefix:"), portalQpPrefixField,
            savePortalBtn
        );
        portalCredsBox.setAlignment(javafx.geometry.Pos.CENTER);

        Button launchBtn = new Button("Launch Smart Browser");
        launchBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 15 30;");
        launchBtn.setOnAction(e -> {
            if (downloadPathField.getText().isEmpty()) { new Alert(Alert.AlertType.WARNING, "Please select a Save Folder first!").show(); return; }
            openSmartBrowser();
        });
        layout.getChildren().addAll(info, sessionBox, pathBox, syncBtn, new Separator(), new Label("University Portal Login (Auto-fill):") {{ setStyle("-fx-font-weight: bold;"); }}, portalCredsBox, new Separator(), launchBtn);
        return layout;
    }

    public class BrowserBridge {
        public void downloadQP(String fileId, String fileName) {
            // Legacy method - replaced by browser-based download in onQuery
        }
    }

    private File getSessionDir() {
        String base = downloadPathField.getText();
        String session = sessionNameField.getText().trim();
        if (base.isEmpty() || session.isEmpty()) return null;
        
        String folderName = session;
        // Convert Examflow Key (DD-MM-YYYY | HH:MM AM) to Folder Name (DD.MM.YY FN/AN)
        if (session.contains("|")) {
            try {
                String[] parts = session.split("\\|");
                String datePart = parts[0].trim().replace("-", ".");
                String timePart = parts[1].trim();
                
                // datePart: 22.05.2026 -> 22.05.26
                String[] d = datePart.split("\\.");
                if (d.length == 3 && d[2].length() == 4) {
                    datePart = d[0] + "." + d[1] + "." + d[2].substring(2);
                }
                
                // timePart: 09:30 AM -> FN/AN
                String[] t = timePart.split(":");
                int hour = Integer.parseInt(t[0]);
                boolean isPm = timePart.toUpperCase().contains("PM");
                if (isPm && hour < 12) hour += 12;
                if (!isPm && hour == 12) hour = 0;
                String suf = (hour >= 13) ? "AN" : "FN";
                
                folderName = datePart + " " + suf;
            } catch (Exception e) {
                // Fallback: Sanitize illegal characters
                folderName = session.replace("|", "-").replace(":", "-").replace(" ", "_");
            }
        }
        
        File sessionDir = new File(base, folderName);
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
            unsafeSslContext = SSLContext.getInstance("SSL");
            unsafeSslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(unsafeSslContext.getSocketFactory());
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
            Runtime.getRuntime().exec("taskkill /F /IM jcef_helper.exe /T").waitFor();
            
            // Get cache dir logic (mirrored from getResolvedCacheDir)
            String userDir = System.getProperty("user.dir");
            String appData = System.getenv("APPDATA");
            File cacheDir = null;
            
            // Portable check
            boolean isPortable = false;
            try {
                Path testPath = Paths.get(userDir, ".write_test_" + System.currentTimeMillis());
                Files.createFile(testPath);
                Files.delete(testPath);
                isPortable = true;
            } catch (Exception e) {}
            
            if (isPortable && !userDir.toLowerCase().contains("program files")) {
                cacheDir = new File(userDir, "bin/chromium_cache");
            } else {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    cacheDir = new File(appData != null ? appData : System.getProperty("user.home"), "SmartQPPrintManager/chromium_cache");
                } else {
                    cacheDir = new File(System.getProperty("user.home"), ".smartqpprintmanager/chromium_cache");
                }
            }

            if (cacheDir != null && cacheDir.exists()) {
                new File(cacheDir, "SingletonLock").delete();
                new File(cacheDir, "SingletonSocket").delete();
                // SingletonCookie is KEPT to preserve session
            }
            Thread.sleep(500); // Brief pause for OS stability
        } catch (Exception e) {}

        bypassSSL();
        launch(args); 
    }
}
