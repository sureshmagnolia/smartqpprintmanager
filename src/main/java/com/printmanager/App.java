package com.printmanager;

import com.printmanager.model.*;
import com.printmanager.ai.*;
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
import org.cef.handler.CefJSDialogHandlerAdapter;
import org.cef.callback.CefJSDialogCallback;
import org.cef.handler.CefJSDialogHandler.JSDialogType;
import org.cef.misc.BoolRef;
import javax.swing.JOptionPane;
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
    private final java.util.concurrent.ScheduledExecutorService printerMonitorExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

    // Map to track and clean up listeners to prevent memory leaks
    private final Map<RoomGroup, javafx.collections.ListChangeListener<RoomItem>> roomGroupListeners = new HashMap<>();


    private ValidationService validationService;
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
    private final HBox healthCheckRibbon = new HBox();
    private final Label healthErrorCountLabel = new Label();
    private final CheckBox printCoverPageCbox = new CheckBox("Print Cover Page");
    private final ToggleButton aiRoutingBtn = new ToggleButton("AI Engine");
    private final AIRoutingAgent aiRoutingAgent = new AIRoutingAgent();

    private final Label roomMapAlert = new Label("\uD83D\uDDFA Maps!");
    private final Label roomStapleAlert = new Label("\uD83D\uDCCE Staple!");
    private final Label roomTotalCount = new Label("Total Rooms: 0");
    private final Label roomTotalPP = new Label("Total PP: 0");

    private final Label globalMapAlert = new Label("\uD83D\uDDFA Maps!");
    private final Label globalStapleAlert = new Label("\uD83D\uDCCE Staple!");

    private CefApp cefApp;
    private CefClient cefClient;
    private volatile boolean initializingCef = false;
    private volatile boolean autoOpenBrowser = false;
    private final Map<CefBrowser, JTextField> browserAddressBars = new HashMap<>();
    private final Map<String, String> downloadFilenameMap = new java.util.concurrent.ConcurrentHashMap<>();
    private final TextField urlField = new TextField("https://collegeportal.uoc.ac.in/");
    private final TextField sessionNameField = new TextField();
    private final TextField downloadPathField = new TextField();

    // Global Progress & Feedback
    private final DoubleProperty globalProgress = new SimpleDoubleProperty(0.0);
    private final BooleanProperty globalProgressVisible = new SimpleBooleanProperty(false);
    private final IntegerProperty pendingAnalysisTasks = new SimpleIntegerProperty(0);

    // Global Synchronization Engine
    private final DoubleProperty globalPulseOpacity = new SimpleDoubleProperty(1.0);
    private final BooleanProperty dualAlertPhase = new SimpleBooleanProperty(true); // true = Red, false = Black

    // UI Performance Debouncers
    private final javafx.animation.PauseTransition fileQueueDebouncer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(500));

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

        // Reset stuck statuses on startup to prevent UI from being locked in "Sending..."
        for (FileItem item : appState.getFileQueue()) {
            String s = item.getStatus();
            if (s != null && (s.contains("Sending") || s.contains("Printing"))) {
                item.setStatus("Ready");
            }
        }
        for (RoomGroup g : appState.getRoomGroups()) {
            String s = g.getStatus();
            if (s != null && (s.contains("Sending") || s.contains("Printing"))) {
                g.setStatus("Ready");
            }
            for (RoomItem ri : g.getItems()) {
                String rs = ri.getStatus();
                if (rs != null && (rs.contains("Sending") || rs.contains("Printing"))) {
                    ri.setStatus("Ready");
                }
            }
        }

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

        StackPane centerContainer = new StackPane();
        javafx.scene.Parent[] viewCache = new javafx.scene.Parent[7];
        viewCache[0] = createMainView(primaryStage);
        centerContainer.getChildren().add(viewCache[0]);

        VBox sidebar = new VBox(10);
        sidebar.setPadding(new Insets(20));
        sidebar.setPrefWidth(220);
        sidebar.setStyle("-fx-background-color: #2b2b2b; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 10, 0, 0, 0);");
        
        String navBtnStyle = "-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 14px; -fx-alignment: CENTER_LEFT; -fx-padding: 10 20;";
        String collapsedStyle = "-fx-background-color: transparent; -fx-text-fill: white; -fx-font-size: 20px; -fx-alignment: CENTER; -fx-padding: 10 0;";
        
        Button btnMain = new Button("\uD83D\uDDA5 Print Queue"); btnMain.setMaxWidth(Double.MAX_VALUE); btnMain.setStyle(navBtnStyle);
        Button btnRoom = new Button("\uD83D\uDCCB Room Router"); btnRoom.setMaxWidth(Double.MAX_VALUE); btnRoom.setStyle(navBtnStyle);
        Button btnPrinters = new Button("\uD83D\uDDA8 Printers"); btnPrinters.setMaxWidth(Double.MAX_VALUE); btnPrinters.setStyle(navBtnStyle);
        Button btnLogs = new Button("\uD83D\uDCDC Activity Logs"); btnLogs.setMaxWidth(Double.MAX_VALUE); btnLogs.setStyle(navBtnStyle);
        Button btnSettings = new Button("\u2699 Settings"); btnSettings.setMaxWidth(Double.MAX_VALUE); btnSettings.setStyle(navBtnStyle);
        Button btnAbout = new Button("\u2139 About"); btnAbout.setMaxWidth(Double.MAX_VALUE); btnAbout.setStyle(navBtnStyle);
        Button btnPortal = new Button("\uD83C\uDF10 Exam Portals"); btnPortal.setMaxWidth(Double.MAX_VALUE); btnPortal.setStyle(navBtnStyle);

        btnMain.setOnAction(e -> {
            if (viewCache[0] == null) viewCache[0] = createMainView(primaryStage);
            centerContainer.getChildren().setAll(viewCache[0]);
        });
        btnRoom.setOnAction(e -> {
            if (viewCache[1] == null) viewCache[1] = createRoomRouterView(primaryStage);
            centerContainer.getChildren().setAll(viewCache[1]);
        });
        btnPrinters.setOnAction(e -> {
            if (viewCache[2] == null) viewCache[2] = createPrinterDashboardView();
            centerContainer.getChildren().setAll(viewCache[2]);
        });
        btnLogs.setOnAction(e -> {
            if (viewCache[3] == null) viewCache[3] = createLogsView();
            centerContainer.getChildren().setAll(viewCache[3]);
        });
        btnSettings.setOnAction(e -> {
            if (viewCache[4] == null) viewCache[4] = createSettingsView();
            centerContainer.getChildren().setAll(viewCache[4]);
        });
        btnAbout.setOnAction(e -> {
            if (viewCache[5] == null) viewCache[5] = createAboutView();
            centerContainer.getChildren().setAll(viewCache[5]);
        });
        btnPortal.setOnAction(e -> {
            if (viewCache[6] == null) viewCache[6] = createPortalView();
            centerContainer.getChildren().setAll(viewCache[6]);
        });

        sidebar.getChildren().addAll(btnMain, btnRoom, btnPrinters, btnPortal, new Region(), btnLogs, btnSettings, btnAbout);
        VBox.setVgrow(sidebar.getChildren().get(4), Priority.ALWAYS); // Spacer

        BorderPane borderPane = new BorderPane();
        borderPane.setLeft(sidebar);
        borderPane.setCenter(centerContainer);

        simAlertHeader.setId("simulation-alert");
        simAlertHeader.getChildren().add(new Label("\u26A0 SIMULATION MODE ACTIVE: Actual printing is disabled. Change this in Settings."));
        simAlertHeader.setManaged(false);
        simAlertHeader.setVisible(false);

        healthCheckRibbon.setStyle("-fx-background-color: #ffebee; -fx-padding: 2;");
        healthCheckRibbon.setManaged(false);
        healthCheckRibbon.setVisible(false);

        HBox topRibbon = new HBox(10);
        topRibbon.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 5 15; -fx-border-color: #1a1a1a; -fx-border-width: 0 0 1 0;");
        topRibbon.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        BooleanProperty sidebarExpanded = new SimpleBooleanProperty(true);
        Button burgerBtn = new Button("\u2630");
        burgerBtn.setStyle("-fx-background-color: transparent; -fx-font-size: 18px; -fx-cursor: hand; -fx-text-fill: white;");
        burgerBtn.setOnAction(e -> {
            sidebarExpanded.set(!sidebarExpanded.get());
            if (sidebarExpanded.get()) {
                sidebar.setPrefWidth(220);
                sidebar.setPadding(new Insets(20));
                btnMain.setText("\uD83D\uDDA5 Print Queue"); btnMain.setStyle(navBtnStyle);
                btnRoom.setText("\uD83D\uDCCB Room Router"); btnRoom.setStyle(navBtnStyle);
                btnPrinters.setText("\uD83D\uDDA8 Printers"); btnPrinters.setStyle(navBtnStyle);
                btnLogs.setText("\uD83D\uDCDC Activity Logs"); btnLogs.setStyle(navBtnStyle);
                btnSettings.setText("\u2699 Settings"); btnSettings.setStyle(navBtnStyle);
                btnAbout.setText("\u2139 About"); btnAbout.setStyle(navBtnStyle);
                btnPortal.setText("\uD83C\uDF10 Exam Portals"); btnPortal.setStyle(navBtnStyle);
            } else {
                sidebar.setPrefWidth(60);
                sidebar.setPadding(new Insets(20, 0, 20, 0));
                btnMain.setText("\uD83D\uDDA5"); btnMain.setStyle(collapsedStyle);
                btnRoom.setText("\uD83D\uDCCB"); btnRoom.setStyle(collapsedStyle);
                btnPrinters.setText("\uD83D\uDDA8"); btnPrinters.setStyle(collapsedStyle);
                btnLogs.setText("\uD83D\uDCDC"); btnLogs.setStyle(collapsedStyle);
                btnSettings.setText("\u2699"); btnSettings.setStyle(collapsedStyle);
                btnAbout.setText("\u2139"); btnAbout.setStyle(collapsedStyle);
                btnPortal.setText("\uD83C\uDF10"); btnPortal.setStyle(collapsedStyle);
            }
        });
        
        Label appTitleLabel = new Label("Smart QP Print Manager - AI Engine V7.3");
        appTitleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white;");
        
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        
        topRibbon.getChildren().addAll(burgerBtn, appTitleLabel, topSpacer, healthCheckRibbon, simAlertHeader);

        VBox root = new VBox(topRibbon, borderPane, createStatusBarView());
        VBox.setVgrow(borderPane, Priority.ALWAYS);

        Scene scene = new Scene(root, 1200, 850);
        try {
            scene.getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } catch (Exception e) { logger.warn("Could not load CSS"); }
        
        primaryStage.setTitle("Smart QP Print Manager - AI Engine V7.3");
        
        // Ensure deep cleanup on exit
        primaryStage.setOnCloseRequest(e -> {
            saveConfigsSynchronously();
            killAllProcesses();
        });
        
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
            // (Skipped since we use BorderPane, view will refresh on click)

            fileQueue.addListener((javafx.collections.ListChangeListener<FileItem>) c -> {
                relinkRoomItems();
                saveConfigs();
            });
            roomGroupsList.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> {
                saveConfigs();
                while (c.next()) {
                    if (c.wasRemoved()) {
                        c.getRemoved().forEach(this::teardownRoomGroupListeners);
                    }
                    if (c.wasAdded()) {
                        c.getAddedSubList().forEach(this::setupRoomGroupListeners);
                    }
                }
            });
            roomGroupsList.forEach(this::setupRoomGroupListeners);
        });


        validationService = new ValidationService(this, roomGroupsList, fileQueue, activityLogger, healthCheckRibbon);
        activityLogger.info("Application started");

        startPrinterStatusMonitor();
        updateSimulationUI();
        
        // Perform Startup Health Check
        Platform.runLater(validationService::validateAppState);
    }

    private void updateSimulationUI() {
        boolean active = printService.isSimulationMode();
        simAlertHeader.setManaged(active);
        simAlertHeader.setVisible(active);
    }

    private final ObservableList<PrinterDisplay> printerDisplays = FXCollections.observableArrayList();

    private int printerMonitorErrorCount = 0;

    private void startPrinterStatusMonitor() {
        printerMonitorExecutor.scheduleWithFixedDelay(() -> {
            try {
                // Fetch detailed status once per cycle
                Map<String, Map<String, String>> detailed = printService.getPrintersDetailedStatus();
                
                Platform.runLater(() -> {
                    try {
                        // 1. Update simple status cache
                        java.util.Set<String> currentKeys = detailed.keySet();
                        printerStatusCache.keySet().removeIf(k -> !currentKeys.contains(k));
                        detailed.forEach((name, data) -> {
                            String health = "Ready";
                            for (PrinterDisplay pd : printerDisplays) {
                                if (pd.getName().equals(name)) {
                                    health = pd.healthStatusProperty().get();
                                    break;
                                }
                            }
                            String newStatus = "Offline".equalsIgnoreCase(health) ? "Offline" : data.get("status");
                            if (!newStatus.equals(printerStatusCache.get(name))) {
                                printerStatusCache.put(name, newStatus);
                            }
                        });
                        
                        // 2. Update dashboard displays
                        for (PrinterDisplay pd : printerDisplays) {
                            if (detailed.containsKey(pd.getName())) {
                                Map<String, String> data = detailed.get(pd.getName());
                                String health = pd.healthStatusProperty().get();
                                if ("Offline".equalsIgnoreCase(health)) {
                                    pd.setStatus("Offline");
                                    pd.setActiveJobs("-");
                                    pd.setCurrentTask("-");
                                } else {
                                    pd.setStatus(data.get("status"));
                                    pd.setActiveJobs(data.get("jobs"));
                                    pd.setCurrentTask(data.get("current"));
                                }
                            } else {
                                if (!"Offline".equals(pd.statusProperty().get())) {
                                    pd.setStatus("Not in API");
                                }
                            }
                        }
                        printerMonitorErrorCount = 0; // Reset on success
                    } catch (Exception err) {
                        for (PrinterDisplay pd : printerDisplays) {
                            pd.setStatus("ERR: " + err.getMessage());
                        }
                        logger.error("UI update failed in printer monitor", err);
                    }
                });
            } catch (Exception e) {
                printerMonitorErrorCount++;
                logger.error("Printer monitor error (count: {})", printerMonitorErrorCount, e);
                // Backoff logic: Sleep thread briefly if we're hitting consecutive errors
                if (printerMonitorErrorCount > 3) {
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }, 0, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        try {
            saveConfigs();
            activityLogger.info("Application stopping. Initiating deep cleanup...");
            
            // 1. Shutdown all thread pools
            printerMonitorExecutor.shutdownNow();
            analysisExecutor.shutdownNow();
            printQueueExecutor.shutdownNow();
            roomPrintExecutor.shutdownNow();
            
            // 2. Dispose of JCEF
            if (cefApp != null) {
                try {
                    cefApp.dispose();
                } catch (Exception e) {
                    logger.warn("CefApp disposal error", e);
                }
            }
            
            // 3. Kill all possible helper processes (JCEF and stray instances)
            // We target jcef_helper specifically and then our own process tree
            Runtime.getRuntime().exec("taskkill /F /IM jcef_helper.exe /T");
            
            // 4. Final aggressive cleanup for the entire process group
            // This ensures no child processes (like PDF renderers or health monitors) survive
            new Thread(() -> {
                try {
                    Thread.sleep(1000);
                    // Kill any java process that might be hanging from this run
                    // This is a safety net for when running via Maven or IDE
                    Runtime.getRuntime().exec("taskkill /F /IM java.exe /FI \"WINDOWTITLE eq Smart QP Print Manager*\" /T");
                    Runtime.getRuntime().exec("taskkill /F /IM javaw.exe /FI \"WINDOWTITLE eq Smart QP Print Manager*\" /T");
                    
                    // Kill the executable if it exists
                    Runtime.getRuntime().exec("taskkill /F /IM \"Smart QP Print Manager.exe\" /T");
                } catch (Exception e) {}
                System.exit(0);
            }).start();
            
            activityLogger.info("Application stopped");
        } catch (Exception e) {
            logger.error("Error during shutdown cleanup", e);
            System.exit(0);
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

        globalMapAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-color: #800020; -fx-padding: 6px 12px; -fx-background-radius: 5px; -fx-border-color: #ffeb3b; -fx-border-width: 2px; -fx-border-radius: 5px;");
        globalMapAlert.setVisible(false);
        globalMapAlert.managedProperty().bind(globalMapAlert.visibleProperty());
        globalMapAlert.opacityProperty().bind(globalPulseOpacity);

        globalStapleAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-background-color: #212121; -fx-padding: 6px 12px; -fx-background-radius: 5px; -fx-border-color: white; -fx-border-width: 2px; -fx-border-radius: 5px;");
        globalStapleAlert.setVisible(false);
        globalStapleAlert.managedProperty().bind(globalStapleAlert.visibleProperty());
        globalStapleAlert.opacityProperty().bind(globalPulseOpacity);

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

            refreshGlobalAlerts();
        };

        fileQueueDebouncer.setOnFinished(e -> {
            relinkRoomItems();
            updateQueueStats.run();
            saveConfigs();
        });

        fileQueue.addListener((javafx.collections.ListChangeListener<FileItem>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(item -> {
                        item.styleProperty().addListener((obs, old, nv) -> refreshGlobalAlerts());
                        item.pageCountProperty().addListener((obs, old, nv) -> refreshGlobalAlerts());
                        item.copiesProperty().addListener((obs, old, nv) -> updateQueueStats.run());
                    });
                }
            }
            // Trigger debounce to prevent UI stuttering
            fileQueueDebouncer.playFromStart();
        });

        // Initialize listeners for existing items
        fileQueue.forEach(item -> {
            item.styleProperty().addListener((obs, old, nv) -> refreshGlobalAlerts());
            item.pageCountProperty().addListener((obs, old, nv) -> refreshGlobalAlerts());
            item.copiesProperty().addListener((obs, old, nv) -> updateQueueStats.run());
        });

        updateQueueStats.run();

        // Immediate Ribbon Update Listener
        roomGroupsList.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> {
            refreshGlobalAlerts();
        });

        Thread statsThread = new Thread(() -> {
            while(true) {
                try {
                    long active = printerStatusCache.values().stream().filter(s -> "Ready".equalsIgnoreCase(s) || "Printing".equalsIgnoreCase(s)).count();
                    Platform.runLater(() -> {
                        activePrinters.setText("Active Printers: " + active);
                    });
                    refreshGlobalAlerts();
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

        HBox alertsBox = new HBox(12, globalMapAlert, globalStapleAlert);
        alertsBox.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);

        Label ribbonBranding = new Label("Product of Magnolia Creations");
        ribbonBranding.setStyle("-fx-text-fill: rgba(0, 0, 0, 0.4); -fx-font-size: 11px; -fx-font-style: italic; -fx-padding: 0 10 0 0;");

        statsDash.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statsDash.setPadding(new Insets(10, 20, 10, 20));
        statsDash.getChildren().addAll(totalQPs, totalCopies, printSheets, new Region() {{ HBox.setHgrow(this, Priority.ALWAYS); }}, activePrinters, totalPages, alertsBox, ribbonBranding, simWarning);

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
            
            private final javafx.collections.MapChangeListener<String, String> cacheListener = change -> updateDisplay();
            
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
                printerStatusCache.addListener(new javafx.collections.WeakMapChangeListener<>(cacheListener));
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
                rBtn.setOnAction(e -> {
                    FileItem item = getTableRow().getItem();
                    if (item != null) {
                        deleteIfTempFile(item);
                        fileQueue.remove(item);
                    }
                });
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
            initFileChooser(fc, "Add PDFs");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF Files", "*.pdf"));
            List<File> files = fc.showOpenMultipleDialog(stage);
            if (files != null && !files.isEmpty()) {
                updateLastDirectory(files.get(0));
                files.forEach(this::processFile);
            }
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
        clearBtn.setTooltip(new Tooltip("Remove all files from the queue and clear all room blocks"));
        clearBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Clear All Queue & Rooms");
            alert.setHeaderText("Are you sure you want to clear the entire print queue and all room blocks?");
            alert.setContentText("This will remove all PDF files and clear all room cards. This action cannot be undone.");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    // Physical cleanup of transient files
                    fileQueue.forEach(this::deleteIfTempFile);
                    
                    fileQueue.clear();
                    roomGroupsList.clear();
                    saveConfigs();
                    validationService.validateAppState();
                }
            });
        });

        Button loadJsonBtn = new Button("Upload JSON");
        loadJsonBtn.setId("load-json-btn");
        loadJsonBtn.setTooltip(new Tooltip("Upload a JSON file to automatically update copy counts based on QP codes"));
        loadJsonBtn.setOnAction(e -> loadJsonAndUpdateCopies(stage));

        Button fetchExamflowBtn = new Button("\u2601 Fetch from Examflow");
        fetchExamflowBtn.setId("fetch-examflow-btn");
        fetchExamflowBtn.setTooltip(new Tooltip("Fetch seating data directly from the cloud using your College ID"));
        fetchExamflowBtn.setOnAction(e -> fetchFromExamflow(stage));
        fetchExamflowBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        Button resetSpoolerBtn = new Button("\u26A0 Reset Spooler");
        resetSpoolerBtn.setTooltip(new Tooltip("Emergency reset of the Windows Print Spooler (Requires Admin)"));
        resetSpoolerBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
        resetSpoolerBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "This will force restart the Windows Print Spooler service. You may be prompted for Administrator permissions. Continue?", ButtonType.YES, ButtonType.NO);
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.YES) {
                    try {
                        new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Process powershell -ArgumentList '-NoProfile -Command Restart-Service -Name Spooler -Force' -Verb RunAs").start();
                        com.printmanager.ui.Toast.show(stage, "Spooler Reset command dispatched.", 4000);
                    } catch (Exception ex) {
                        logger.error("Failed to reset spooler", ex);
                        com.printmanager.ui.Toast.show(stage, "Error resetting spooler.", 4000);
                    }
                }
            });
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox btns = new HBox(15, addBtn, loadJsonBtn, fetchExamflowBtn, printBtn, clearBtn, spacer, resetSpoolerBtn);
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
            
            // Sync offline state immediately
            this.monitor.healthStatusProperty().addListener((obs, old, val) -> {
                if ("Offline".equalsIgnoreCase(val)) {
                    Platform.runLater(() -> {
                        this.status.set("Offline");
                        this.activeJobs.set("-");
                        this.currentTask.set("-");
                    });
                }
            });
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
        TableView<PrinterDisplay> table = new TableView<>(this.printerDisplays);
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

        if (printerDisplays.isEmpty()) {
            printService.getAvailablePrinters().stream()
                .filter(n -> !"None".equals(n))
                .forEach(n -> {
                    PrinterDisplay pd = new PrinterDisplay(n, "Checking...");
                    printerDisplays.add(pd);
                    pd.getMonitor().start();
                });
        }


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
            fileQueue.removeIf(item -> {
                if (item.getFile().getAbsolutePath().equalsIgnoreCase(file.getAbsolutePath())) {
                    deleteIfTempFile(item);
                    return true;
                }
                return false;
            });
            pendingAnalysisTasks.set(pendingAnalysisTasks.get() + 1);
            globalProgressVisible.set(true);
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
            } finally {
                Platform.runLater(() -> {
                    pendingAnalysisTasks.set(pendingAnalysisTasks.get() - 1);
                    if (pendingAnalysisTasks.get() == 0) {
                        globalProgressVisible.set(false);
                        updateStatus("Ready. " + fileQueue.size() + " files loaded.");
                    }
                });
            }
        });
    }

    private void routeSmartPart(File file, String originalName, SmartSplitRule rule, boolean isAfter) throws Exception {
        int pages = pdfService.getPageCount(file);
        String style = "Simplex"; String overlay = ""; File f = file;
        String prefix = isAfter ? rule.getAfterPrefix() : rule.getBeforePrefix();

        // NEW: If keyword contains SDE/DISTANCE markers, ensure the prefix reflects this 
        // to help the AI Routing Agent identify the stream, especially for the 'Main' part 
        // which may not have the keyword text on its first page.
        String kw = rule.getKeyword().toUpperCase();
        boolean isSdeRule = kw.contains("SDE") || kw.contains("DISTANCE") || kw.contains("EDE") || kw.contains("EXTERNAL");
        
        if (isSdeRule) {
            String upperPrefix = prefix.toUpperCase();
            String upperOriginal = originalName.toUpperCase();
            if (!upperPrefix.contains("SDE") && !upperOriginal.contains("SDE") &&
                !upperPrefix.contains("DISTANCE") && !upperOriginal.contains("DISTANCE")) {
                prefix = "SDE_" + prefix;
            }
        }

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
            // Main Part: Apply QP Overlay if enabled in Smart Split Rule
            if (rule.isApplyQpOverlay()) {
                String qp = null;
                try {
                    com.printmanager.ai.PDFDecrypter decrypter = new com.printmanager.ai.PDFDecrypter();
                    com.printmanager.ai.PDFDecrypter.PDFMetadata meta = decrypter.extractMetadata(file);
                    qp = meta.qpCode;
                } catch (Exception ignored) {}
                
                if (qp == null || qp.trim().isEmpty()) {
                    qp = extractQPFromFileName(originalName);
                }

                if (qp != null && !qp.trim().isEmpty()) {
                    qp = qp.trim().toUpperCase();
                    // Normalize QP by removing existing 'D' prefix if we're going to re-add it or use portal prefix
                    String baseQP = qp.replaceAll("^D", "");
                    String prefixFromPortal = config.getPortalQpPrefix() != null ? config.getPortalQpPrefix().trim() : "";
                    
                    // Priority: If filename has a prefix (like 'D'), keep it. Else use portal prefix.
                    String finalPrefix = "";
                    if (qp.startsWith("D")) finalPrefix = "D";
                    else if (!prefixFromPortal.isEmpty()) finalPrefix = prefixFromPortal;
                    
                    String finalQP = finalPrefix + baseQP;

                    // Add 'A' suffix if enabled
                    if (rule.isAddASuffix() && !finalQP.endsWith("A")) {
                        finalQP = finalQP + "A";
                    }

                    try {
                        File overlaid = printService.applyTopLeftOverlay(file, finalQP);
                        f = overlaid;
                        activityLogger.info("Applied Top-Left QP Overlay: " + finalQP);
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
            String finalName = finalPrefix + originalName;
            
            // If this is an SDE rule and we are applying an 'A' suffix (default for SDE),
            // ensure the filename also reflects this so the AI Routing Agent identifies it as SDE.
            if (isSdeRule && rule.isAddASuffix()) {
                String qpInName = extractQPFromFileName(finalName).toUpperCase();
                if (!qpInName.isEmpty() && !qpInName.endsWith("A")) {
                    if (finalName.toLowerCase().endsWith(".pdf")) {
                        finalName = finalName.substring(0, finalName.length() - 4) + "A.pdf";
                    } else {
                        finalName += "A";
                    }
                }
            }
            
            item.setFileName(finalName);
            item.setStyle(fs);
            fileQueue.add(item);
        });
    }

    private void addFileToQueue(File file, String manualStyle, String manualOverlay, String customName) {
        Platform.runLater(() -> {
            pendingAnalysisTasks.set(pendingAnalysisTasks.get() + 1);
            globalProgressVisible.set(true);
        });
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
            finally {
                Platform.runLater(() -> {
                    pendingAnalysisTasks.set(pendingAnalysisTasks.get() - 1);
                    if (pendingAnalysisTasks.get() == 0) {
                        globalProgressVisible.set(false);
                        updateStatus("Ready. " + fileQueue.size() + " files loaded.");
                    }
                });
            }
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
                String originalName = originalItem.getFileName();
                String baseName = originalName.endsWith(".pdf") ? originalName.substring(0, originalName.length() - 4) : originalName;

                // 1. Save the Split Part into the SYSTEM TEMP directory (Keep original folder untouched)
                String splitFileName = "Split_" + baseName + "_P" + start + "-" + end + ".pdf";
                File finalSplitFile = File.createTempFile("manual_split_", ".pdf");
                java.nio.file.Files.copy(splitPart.toPath(), finalSplitFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                Platform.runLater(() -> {
                    addFileToQueue(finalSplitFile, style, overlay, splitFileName);
                    activityLogger.info("Manual Split created in Temp: " + splitFileName);
                });

                // 2. Create the Remainder file in TEMP and update the queue entry
                File remainingTemp = pdfService.removePages(originalFile, start, end);
                if (remainingTemp != null) {
                    // We DO NOT overwrite the original file anymore.
                    // Instead, we create a persistent temp file for this session.
                    File persistentRemaining = File.createTempFile("remain_" + baseName + "_", ".pdf");
                    java.nio.file.Files.copy(remainingTemp.toPath(), persistentRemaining.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    int newCount = pdfService.getPageCount(persistentRemaining);

                    Platform.runLater(() -> {
                        // Point the existing queue item to the NEW temporary remainder file
                        originalItem.setFile(persistentRemaining);
                        originalItem.setPageCount(newCount);
                        
                        // Explicitly rename to Remain_ for visibility
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
                        activityLogger.success("Original untouched. Queue updated with Remainder (" + newCount + " pages).");
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
    private void deleteIfTempFile(FileItem item) {
        if (item == null || item.getFile() == null) return;
        String filePath = item.getFile().getAbsolutePath();
        String tempDir = System.getProperty("java.io.tmpdir");

        if (filePath.contains(tempDir) || filePath.contains(".gemini" + File.separator + "tmp")) {
            try {
                if (item.getFile().exists()) {
                    boolean deleted = item.getFile().delete();
                    if (deleted) logger.info("Cleanup: Deleted transient file: " + filePath);
                }
            } catch (Exception e) {
                logger.warn("Cleanup: Failed to delete " + filePath);
            }
        }
    }

    private void saveFileAs(FileItem item) {
        FileChooser fc = new FileChooser();
        initFileChooser(fc, "Save PDF");
        fc.setInitialFileName(item.getFileName());
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF", "*.pdf"));
        File t = fc.showSaveDialog(null);
        if (t != null) {
            updateLastDirectory(t);
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
                    printAllBtn.setText("âœ” Sent");
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
        CheckBox ssQpOverlay = new CheckBox("Apply QP Overlay on Main"); ssQpOverlay.setSelected(true);
        CheckBox ssAddSuffix = new CheckBox("Add 'A' Suffix"); ssAddSuffix.setSelected(true);
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
            new HBox(15, ssQpOverlay, ssAddSuffix),
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
        initFileChooser(fc, "Select QP Print Job JSON");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file == null) return;
        updateLastDirectory(file);
        
        activityLogger.info("Fetching data from JSON: " + file.getName());
        analysisExecutor.submit(() -> {
            try {
                updateStatus("Reading JSON: " + file.getName());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(file);
                int countUpdated = 0;
                Set<FileItem> matchedFiles = new HashSet<>();
                List<String> unmatchedFromJSON = new ArrayList<>();

                if (root.isArray()) {
                    for (JsonNode node : root) {
                        String qpCode = node.path("qpCode").asText("");
                        int count = node.path("count").asInt(1);
                        if (!qpCode.isEmpty()) {
                            boolean matched = false;

                            if (config.isAiRoutingEnabled()) {
                                // Use AI Agent for high-precision matching
                                RoomItem tempRoom = new RoomItem("", qpCode, "", count, -1);
                                List<MatchResult> aiResults = aiRoutingAgent.findAllMatchesForRoom(tempRoom, fileQueue);
                                for (MatchResult res : aiResults) {
                                    FileItem item = res.getMatchedFile();
                                    final int finalCount = count;
                                    Platform.runLater(() -> item.setCopies(finalCount));
                                    countUpdated++;
                                    matched = true;
                                    matchedFiles.add(item);
                                    activityLogger.info("Updated " + item.getFileName() + " copies to " + finalCount + " (AI Matched QP: " + qpCode + ")");
                                }
                            } else {
                                // Legacy matching
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
                            }
                            if (!matched) {
                                unmatchedFromJSON.add("âŒ QP [" + qpCode + "] File NOT Loaded (Found in JSON but missing in App Queue)");
                                activityLogger.error("No file found in queue for QP Code: " + qpCode);
                            }
                        }
                    }
                }

                // Alert for files in queue that have no routing in JSON
                List<String> unmatchedFromQueue = new ArrayList<>();
                for (FileItem item : fileQueue) {
                    if (!matchedFiles.contains(item)) {
                        String qp = extractQPFromFileName(item.getFileName());
                        unmatchedFromQueue.add("âŒ QP [" + qp + "] No routing data found in Uploaded JSON (File: " + item.getFileName() + ")");
                        activityLogger.warn("Queue File: " + item.getFileName() + " has NO routing entries in JSON.");
                    }
                }

                if (!unmatchedFromQueue.isEmpty() || !unmatchedFromJSON.isEmpty()) {
                    validationService.validateAppState(unmatchedFromJSON, unmatchedFromQueue, new ArrayList<>());
                } else {
                    validationService.validateAppState();
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
        Set<FileItem> updatedItems = new HashSet<>();
        List<String> unmatchedFromCloud = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : qpTotalCounts.entrySet()) {
            String qpCode = entry.getKey();
            int totalCount = entry.getValue();
            boolean qpMatched = false;
            
            if (config.isAiRoutingEnabled()) {
                // Use a temporary RoomItem to leverage the AI Agent's logic
                RoomItem tempRoom = new RoomItem("", qpCode, "", totalCount, -1);
                List<MatchResult> aiResults = aiRoutingAgent.findAllMatchesForRoom(tempRoom, fileQueue);
                for (MatchResult res : aiResults) {
                    FileItem item = res.getMatchedFile();
                    updatedItems.add(item);
                    final int finalCount = totalCount;
                    Platform.runLater(() -> item.setCopies(finalCount));
                    countUpdated++;
                    qpMatched = true;
                    activityLogger.info("Synced " + item.getFileName() + ": set copies to " + totalCount + " (AI Matched QP: " + qpCode + ")");
                }
            } else {
                for (FileItem item : fileQueue) {
                    String fileName = item.getFileName();
                    String extractedQP = extractQPFromFileName(fileName);
                    if (qpCode.equalsIgnoreCase(extractedQP) || fileName.contains("_" + qpCode + "_") || fileName.contains("_" + qpCode + ".")) {
                        updatedItems.add(item);
                        final int finalCount = totalCount;
                        Platform.runLater(() -> item.setCopies(finalCount));
                        countUpdated++;
                        qpMatched = true;
                        activityLogger.info("Synced " + fileName + ": set copies to " + totalCount + " (Matched QP: " + qpCode + ")");
                    }
                }
            }
            if (!qpMatched) {
                unmatchedFromCloud.add("âŒ QP [" + qpCode + "] File NOT Loaded (Found in Cloud Data but missing in App Queue)");
                activityLogger.error("Cloud Sync: No file found in queue for QP Code: " + qpCode);
            }
        }

        // Report unmatched files from queue
        List<String> unmatchedFromQueue = new ArrayList<>();
        for (FileItem item : fileQueue) {
            if (!updatedItems.contains(item)) {
                String qp = extractQPFromFileName(item.getFileName());
                unmatchedFromQueue.add("âŒ QP [" + qp + "] No routing data found in Cloud Data (File: " + item.getFileName() + ")");
                activityLogger.warn("Cloud Sync: File " + item.getFileName() + " has NO matching data in cloud.");
            }
        }

        if (!unmatchedFromQueue.isEmpty() || !unmatchedFromCloud.isEmpty()) {
            validationService.validateAppState(unmatchedFromCloud, unmatchedFromQueue, new ArrayList<>());
        } else {
            validationService.validateAppState();
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

    public String extractQPFromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return "";
        
        List<String> candidates = new ArrayList<>();
        // Match potential blocks: _143812_ or _140754A_ or _BCM4C04_
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("_([A-Z0-9\\.]+)[_\\.]", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(fileName);
        while (m.find()) {
            String c = m.group(1);
            // Filter out obvious dates (e.g. 19.05.26 or 2026-06-02)
            long dots = c.chars().filter(ch -> ch == '.').count();
            long dashes = c.chars().filter(ch -> ch == '-').count();
            if (dots < 2 && dashes < 2 && c.length() >= 5) {
                candidates.add(c.toUpperCase());
            }
        }

        // Fallback: search for any 5-8 digit block if no candidates found
        if (candidates.isEmpty()) {
            java.util.regex.Pattern pDigit = java.util.regex.Pattern.compile("(\\d{5,8})");
            java.util.regex.Matcher mDigit = pDigit.matcher(fileName);
            while (mDigit.find()) candidates.add(mDigit.group(1));
        }

        if (candidates.isEmpty()) return "";

        // Priority Selection:
        // 1. Prefer candidates with an 'A' suffix
        for (String c : candidates) if (c.endsWith("A")) return c;
        // 2. Prefer numeric-only candidates of 5-8 digits
        for (String c : candidates) if (c.matches("\\d{5,8}")) return c;
        // 3. Return the first valid candidate
        String qp = candidates.get(0);

        // Final SDE suffix check for split/manual files
        if (!qp.endsWith("A")) {
            String fn = fileName.toUpperCase();
            if (fn.contains("_A_") || fn.contains("_A.") || fn.contains(" SDE") || fn.contains("(SDE)") || fn.contains("MCQ")) {
                qp = qp + "A";
            }
        }
        return qp;
    }

    public int getSpecificityLevel(String fileName) {
        if (fileName == null) return 0;
        String name = fileName.toUpperCase();
        if (name.contains("SPLIT_") || name.contains("REMAIN_") || name.contains("PART_")) return 3;
        if (name.contains("MAIN_") || name.contains("MCQ_") || name.contains("_SDE_") || name.contains(" SDE")) return 2;
        return 1;
    }

    private void relinkRoomItems() {
        if (roomGroupsList.isEmpty()) return;
        logger.info("Relinking & Syncing Room Items (Queue Size: {})", fileQueue.size());

        // Phase 0: Clear stale matches and remove ghost items from previous splits
        for (RoomGroup g : roomGroupsList) {
            // First, clear matches that are physically gone from the current queue
            for (RoomItem ri : g.getItems()) {
                if (ri.getMatchedFile() != null && !fileQueue.contains(ri.getMatchedFile())) {
                    ri.setMatchedFile(null);
                    ri.setStatus("PND"); // Use shorter PND for UI space
                    ri.setPdfFileName("");
                }
            }
            
            // Second, remove redundant items with no match (usually leftovers from a previous split/re-merge)
            // But keep at least one item per QP code (the 'template' or original)
            java.util.Set<String> keptQPs = new java.util.HashSet<>();
            g.getItems().removeIf(ri -> {
                String qp = ri.getQpCode();
                if (qp == null || qp.isEmpty()) return false;
                if (ri.getMatchedFile() != null) {
                    keptQPs.add(qp);
                    return false;
                }
                // If we already have a matched item or a template for this QP in this group, remove this empty one
                if (keptQPs.contains(qp)) return true; 
                keptQPs.add(qp);
                return false; 
            });
        }
        
        for (RoomGroup g : roomGroupsList) {
            // Identify unique QP needs in this room
            java.util.Set<String> neededQPs = g.getItems().stream()
                .map(RoomItem::getQpCode)
                .filter(Objects::nonNull)
                .filter(qp -> !qp.isEmpty())
                .collect(Collectors.toSet());
            
            // Collect items with NO QP code to try filename matching
            List<RoomItem> noQpItems = g.getItems().stream()
                .filter(ri -> ri.getQpCode() == null || ri.getQpCode().isEmpty())
                .collect(Collectors.toList());

            // 1. Process items WITH QP codes (Standard Path)
            for (String qp : neededQPs) {
                List<FileItem> matches = new ArrayList<>();

                if (config.isAiRoutingEnabled()) {
                    RoomItem template = g.getItems().stream()
                        .filter(ri -> qp.equalsIgnoreCase(ri.getQpCode()))
                        .findFirst().orElse(null);
                    
                    if (template != null) {
                        List<MatchResult> aiResults = aiRoutingAgent.findAllMatchesForRoom(template, fileQueue);
                        double maxScore = aiResults.stream().mapToDouble(MatchResult::getConfidenceScore).max().orElse(0.0);
                        if (maxScore > 0) aiResults.removeIf(r -> r.getConfidenceScore() < maxScore);
                        
                        Set<String> seenNames = new HashSet<>();
                        for (MatchResult res : aiResults) {
                            FileItem fi = res.getMatchedFile();
                            if (!seenNames.contains(fi.getFileName().toLowerCase())) {
                                matches.add(fi);
                                seenNames.add(fi.getFileName().toLowerCase());
                            }
                        }
                    }
                } else {
                    // --- LEGACY PATH ---
                    List<FileItem> legacyMatches = fileQueue.stream()
                        .filter(f -> {
                            String extracted = extractQPFromFileName(f.getFileName());
                            return qp.equalsIgnoreCase(extracted) || f.getFileName().contains("_" + qp + "_") || f.getFileName().contains("_" + qp + ".");
                        })
                        .collect(Collectors.toList());
                    matches.addAll(legacyMatches);
                }
                
                // --- UNIVERSAL SPECIFICITY FILTERING ---
                // Regardless of AI or Legacy, always prioritize Children over Masters
                if (matches.size() > 1) {
                    int maxLevel = matches.stream()
                        .mapToInt(f -> getSpecificityLevel(f.getFileName()))
                        .max().orElse(1);
                    matches.removeIf(f -> getSpecificityLevel(f.getFileName()) < maxLevel);
                }

                for (FileItem f : matches) {
                    // CRITICAL: Always sync the room's printer to the file if it doesn't have one
                    if (f.getTargetPrinter() == null || "None".equals(f.getTargetPrinter()) || f.getTargetPrinter().isEmpty()) {
                        f.setTargetPrinter(g.getSelectedPrinter());
                    }

                    boolean linkExists = g.getItems().stream().anyMatch(ri -> ri.getMatchedFile() == f && qp.equalsIgnoreCase(ri.getQpCode()));
                    if (!linkExists) {
                        RoomItem proto = g.getItems().stream().filter(ri -> qp.equalsIgnoreCase(ri.getQpCode()) && ri.getMatchedFile() == null).findFirst().orElse(null);
                        if (proto != null) {
                            proto.setPdfFileName(f.getFileName());
                            proto.setMatchedFile(f);
                            proto.setStatus("Ready");
                        } else {
                            RoomItem baseItem = g.getItems().stream().filter(ri -> qp.equalsIgnoreCase(ri.getQpCode())).findFirst().orElse(null);
                            if (baseItem != null) {
                                RoomItem newItem = new RoomItem(g.getRoomSerial(), qp, f.getFileName(), baseItem.getCount(), baseItem.getSourceNodeId());
                                newItem.setCourseName(baseItem.getCourseName());
                                newItem.setStream(baseItem.getStream());
                                newItem.setMatchedFile(f);
                                newItem.setStatus("Ready");
                                g.getItems().add(newItem);
                            }
                        }
                    }
                }
            }

            // 2. Process items WITHOUT QP codes (Filename Fallback)
            for (RoomItem ri : noQpItems) {
                if (ri.getMatchedFile() != null && fileQueue.contains(ri.getMatchedFile())) continue;
                String targetName = ri.getPdfFileName();
                if (targetName == null || targetName.isEmpty()) continue;

                FileItem match = fileQueue.stream()
                    .filter(f -> f.getFileName().equalsIgnoreCase(targetName) || f.getFileName().contains(targetName) || targetName.contains(f.getFileName()))
                    .findFirst().orElse(null);
                
                if (match != null) {
                    ri.setMatchedFile(match);
                    ri.setStatus("Ready");
                }
            }
            
            // 3. Cleanup
            for (RoomItem i : g.getItems()) {
                FileItem currentMatch = i.getMatchedFile();
                if (currentMatch != null && !fileQueue.contains(currentMatch)) {
                    i.setMatchedFile(null);
                    i.setStatus("PND");
                }
            }
        }
    }

    private boolean isAlertItem(FileItem item) {
        if (item == null) return false;
        boolean isMap = hasMapKeywords(item);
        boolean isStaple = "Booklet".equals(item.getStyle()) && calculatePP(item) > 1;
        return isMap || isStaple;
    }

    private boolean isAlertRoomItem(RoomItem item) {
        if (item == null) return false;
        boolean isMap = item.getMatchedFile() != null && hasMapKeywords(item.getMatchedFile());
        boolean isStaple = false;
        if (item.getMatchedFile() != null) {
            FileItem f = item.getMatchedFile();
            isStaple = "Booklet".equals(f.getStyle()) && calculatePP(f) > 1;
        }
        return isMap || isStaple;
    }


    private static final java.util.regex.Pattern HISTORY_PATTERN = java.util.regex.Pattern.compile("\\bhistory\\b", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern MAP_PATTERN = java.util.regex.Pattern.compile("\\bmap\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean hasMapKeywords(FileItem item) {
        if (item == null || item.getContent() == null) return false;
        String content = item.getContent();
        return HISTORY_PATTERN.matcher(content).find() && MAP_PATTERN.matcher(content).find();
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

        boolean isMap = hasMapKeywords(item);
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

    private final ExecutorService persistenceExecutor = Executors.newSingleThreadExecutor();

    private void saveConfigs() {
        persistenceExecutor.submit(() -> {
            try {
                if (roomGroupsList.isEmpty() && fileQueue.isEmpty() && !rulesList.isEmpty()) {
                    // logger.warn("Prevented saveConfigs because both queue and room groups are empty (safety check).");
                    // return; // Commented out for now to see if this is the cause
                }
                // logger.info("Triggering saveConfigs in background. Queue size: {}, Room groups: {}", fileQueue.size(), roomGroupsList.size());
                
                // Save System Settings
                config.setRules(List.copyOf(rulesList));
                config.setSmartSplitRules(List.copyOf(smartSplitRulesList));
                configManager.saveConfig(config);
                
                // Save Dynamic Data
                appState.setFileQueue(new ArrayList<>(fileQueue));
                appState.setRoomGroups(new ArrayList<>(roomGroupsList));
                configManager.saveAppState(appState);
            } catch (Exception e) {
                logger.error("Failed to save configs in background", e);
            }
        });
    }

    private void saveConfigsSynchronously() {
        try {
            config.setRules(List.copyOf(rulesList));
            config.setSmartSplitRules(List.copyOf(smartSplitRulesList));
            configManager.saveConfig(config);
            appState.setFileQueue(new ArrayList<>(fileQueue));
            appState.setRoomGroups(new ArrayList<>(roomGroupsList));
            configManager.saveAppState(appState);
        } catch (Exception e) {
            logger.error("Failed to save configs synchronously", e);
        }
    }

    private VBox createRoomRouterView(Stage stage) {
        Button uploadBtn = new Button("Upload Room JSON");
        uploadBtn.setStyle("-fx-font-size: 13px; -fx-padding: 8 15; -fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-weight: bold;");
        uploadBtn.setPrefWidth(180);
        uploadBtn.setOnAction(e -> loadRoomWiseJson(stage));

        Button addRoomBtn = new Button("Manually Add Room");
        addRoomBtn.setStyle("-fx-font-size: 13px; -fx-padding: 8 15; -fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold;");
        addRoomBtn.setPrefWidth(180);
        addRoomBtn.setOnAction(e -> showManualRoomDialog());

        Button clearBlocksBtn = new Button("Clear All");
        clearBlocksBtn.setStyle("-fx-font-size: 13px; -fx-padding: 8 15; -fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold;");
        clearBlocksBtn.setPrefWidth(100);
        clearBlocksBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Clear All");
            alert.setHeaderText("Are you sure you want to clear all room blocks?");
            alert.setContentText("This action cannot be undone.");
            alert.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    roomGroupsList.clear();
                    saveConfigs();
                    validationService.validateAppState();
                }
            });
        });

        TextField roomSearch = new TextField();
        roomSearch.setPromptText("Filter rooms or QPs...");
        roomSearch.setPrefWidth(220);
        roomSearch.setStyle("-fx-font-size: 13px; -fx-padding: 8;");
        
        Button clearRoomSearchBtn = new Button("Clear");
        clearRoomSearchBtn.setStyle("-fx-font-size: 13px; -fx-padding: 8;");
        clearRoomSearchBtn.setOnAction(e -> roomSearch.clear());
        HBox roomSearchBox = new HBox(5, roomSearch, clearRoomSearchBtn);
        roomSearchBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        FilteredList<RoomGroup> filteredRooms = new FilteredList<>(roomGroupsList, p -> true);
        roomSearch.textProperty().addListener((obs, old, val) -> {
            String filter = val.toLowerCase();
            filteredRooms.setPredicate(g -> filter.isEmpty() || 
                g.getRoomSerial().toLowerCase().contains(filter) || 
                g.getItems().stream().anyMatch(i -> i.getQpCode().toLowerCase().contains(filter)));
        });

        
        java.util.Map<RoomGroup, VBox> cardCache = new java.util.WeakHashMap<>();
        javafx.scene.layout.FlowPane flowPane = new javafx.scene.layout.FlowPane();
        flowPane.setHgap(20);
        flowPane.setVgap(20);
        flowPane.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        flowPane.setPadding(new javafx.geometry.Insets(20));

        Runnable updateFlowPane = () -> {
            flowPane.getChildren().clear();
            for(RoomGroup g : filteredRooms) {
                flowPane.getChildren().add(cardCache.computeIfAbsent(g, grp -> createRoomCard(grp)));
            }
        };
        filteredRooms.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> updateFlowPane.run());
        updateFlowPane.run();

        ScrollPane scrollPane = new ScrollPane(flowPane);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");


        printCoverPageCbox.setSelected(config.isPrintCoverPage());
        printCoverPageCbox.setOnAction(e -> {
            config.setPrintCoverPage(printCoverPageCbox.isSelected());
            saveConfigs();
        });

        aiRoutingBtn.setSelected(config.isAiRoutingEnabled());
        Runnable updateAiBtnStyle = () -> {
            if (aiRoutingBtn.isSelected()) {
                aiRoutingBtn.setGraphic(new Label("\u2705 \uD83E\uDD16")); // Tick + Robot
                aiRoutingBtn.setStyle("-fx-background-color: #e8f5e9; -fx-text-fill: #2e7d32; -fx-font-weight: bold; -fx-border-color: #2e7d32; -fx-border-radius: 3; -fx-font-size: 13px;");
            } else {
                aiRoutingBtn.setGraphic(new Label("\u26AA \uD83E\uDD16")); // Empty circle + Robot
                aiRoutingBtn.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
            }
        };
        updateAiBtnStyle.run();

        aiRoutingBtn.setOnAction(e -> {
            config.setAiRoutingEnabled(aiRoutingBtn.isSelected());
            updateAiBtnStyle.run();
            saveConfigs();
            activityLogger.info("AI Engine: " + (aiRoutingBtn.isSelected() ? "ENABLED" : "DISABLED"));
        });

        roomMapAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-background-color: #800020; -fx-padding: 5px 10px; -fx-background-radius: 5px; -fx-border-color: #ffeb3b; -fx-border-width: 1px; -fx-border-radius: 5px;");
        roomMapAlert.setVisible(false);
        roomMapAlert.managedProperty().bind(roomMapAlert.visibleProperty());
        roomMapAlert.opacityProperty().bind(globalPulseOpacity);

        roomStapleAlert.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-background-color: #212121; -fx-padding: 5px 10px; -fx-background-radius: 5px; -fx-border-color: white; -fx-border-width: 1px; -fx-border-radius: 5px;");
        roomStapleAlert.setVisible(false);
        roomStapleAlert.managedProperty().bind(roomStapleAlert.visibleProperty());
        roomStapleAlert.opacityProperty().bind(globalPulseOpacity);

        roomTotalCount.setStyle("-fx-font-weight: bold; -fx-text-fill: #1976D2; -fx-font-size: 14px;");
        roomTotalPP.setStyle("-fx-font-weight: bold; -fx-text-fill: #E91E63; -fx-font-size: 14px;");

        roomGroupsList.addListener((javafx.collections.ListChangeListener<RoomGroup>) c -> {
            updateRoomAlerts();
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(g -> g.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c2 -> updateRoomAlerts()));
                }
            }
        });
        roomGroupsList.forEach(g -> g.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c -> updateRoomAlerts()));
        updateRoomAlerts();

        HBox statsRow = new HBox(20, roomTotalCount, roomTotalPP, roomMapAlert, roomStapleAlert);
        statsRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        statsRow.setPadding(new Insets(10, 0, 0, 0));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolsRow = new HBox(12, uploadBtn, addRoomBtn, clearBlocksBtn, roomSearchBox, spacer, printCoverPageCbox, aiRoutingBtn);
        toolsRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        VBox header = new VBox(15, toolsRow, statsRow);
        header.setPadding(new Insets(20));
        header.getStyleClass().add("glass-panel");

        VBox layout = new VBox(15, header, scrollPane);
        layout.setPadding(new Insets(15));
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        return layout;
    }

        private VBox createRoomCard(RoomGroup group) {
        VBox card = new VBox(8);
        
        String defaultStyle = "-fx-background-color: #ffffff; -fx-border-color: #e3e8ee; -fx-border-width: 1; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 15 10 15; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.06), 10, 0, 0, 5); -fx-cursor: hand;";
        String finishedStyle = "-fx-background-color: #f2fcf5; -fx-border-color: #4caf50; -fx-border-width: 2; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 10 15 10 15; -fx-effect: dropshadow(three-pass-box, rgba(76,175,80,0.2), 10, 0, 0, 5); -fx-cursor: hand;";
        
        card.setStyle(group.getStatus() != null && group.getStatus().contains("Finished") ? finishedStyle : defaultStyle);
        card.setPrefWidth(480);
        card.setMaxWidth(480);

        group.statusProperty().addListener((obs, old, val) -> {
            if (val != null && val.contains("Finished")) card.setStyle(finishedStyle);
            else card.setStyle(defaultStyle);
        });

        
        
        Label title = new Label("Room: " + group.getRoomSerial());
        Runnable recalculateRoomTotal = () -> {
            java.util.Map<Integer, Integer> nodeCounts = new java.util.HashMap<>();
            boolean staple = false;
            boolean map = false;
            for (RoomItem item : group.getItems()) {
                int nid = item.getSourceNodeId();
                if (nid >= 0) {
                    nodeCounts.put(nid, Math.max(nodeCounts.getOrDefault(nid, 0), item.getCount()));
                } else {
                    String key = (item.getQpCode() != null ? item.getQpCode() : "") + "|" + (item.getCourseName() != null ? item.getCourseName() : "");
                    nodeCounts.put(key.hashCode(), Math.max(nodeCounts.getOrDefault(key.hashCode(), 0), item.getCount()));
                }
                if (item.getMatchedFile() != null) {
                    if ("Booklet".equals(item.getMatchedFile().getStyle()) && calculatePP(item.getMatchedFile()) > 1) staple = true;
                    if (hasMapKeywords(item.getMatchedFile())) map = true;
                }
            }
            int sum = nodeCounts.values().stream().mapToInt(Integer::intValue).sum();
            final boolean finalStaple = staple;
            final boolean finalMap = map;
            Platform.runLater(() -> {
                if (group.getTotalStudents() != sum) {
                    group.setTotalStudents(sum);
                }
                
                String baseStyle = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-font-family: 'Segoe UI', sans-serif; -fx-padding: 2 6; -fx-background-radius: 4; ";
                if (finalMap && finalStaple) {
                    title.setStyle(baseStyle + "-fx-background-color: #800020; -fx-text-fill: white;");
                    try { title.opacityProperty().bind(globalPulseOpacity); } catch(Exception e){}
                } else if (finalMap) {
                    title.setStyle(baseStyle + "-fx-background-color: #800020; -fx-text-fill: white;");
                    try { title.opacityProperty().bind(globalPulseOpacity); } catch(Exception e){}
                } else if (finalStaple) {
                    title.setStyle(baseStyle + "-fx-background-color: #212121; -fx-text-fill: white;");
                    try { title.opacityProperty().bind(globalPulseOpacity); } catch(Exception e){}
                } else {
                    title.setStyle(baseStyle + "-fx-text-fill: #1a237e; -fx-background-color: transparent;");
                    title.opacityProperty().unbind();
                    title.setOpacity(1.0);
                }
            });
        };

    

        group.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c -> recalculateRoomTotal.run());
        recalculateRoomTotal.run();

        javafx.beans.binding.IntegerBinding totalQtyBinding = javafx.beans.binding.Bindings.createIntegerBinding(() -> {
            return group.getTotalStudents();
        }, group.totalStudentsProperty());

        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1a237e; -fx-font-family: 'Segoe UI', sans-serif;");
        
        Label subtitle = new Label();
        subtitle.textProperty().bind(javafx.beans.binding.Bindings.concat(totalQtyBinding.asString(), " Students"));
        subtitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: #2e7d32; -fx-padding: 3 8 3 8; -fx-background-radius: 12;");

        java.util.function.Consumer<RoomItem> attachListener = ri -> {
            ri.countProperty().addListener((obs, old, val) -> {
                group.getItems().stream()
                     .filter(other -> other != ri && other.getSourceNodeId() == ri.getSourceNodeId() && ri.getSourceNodeId() >= 0)
                     .forEach(other -> other.setCount(val.intValue()));
                recalculateRoomTotal.run();
            });
        };
        group.getItems().forEach(attachListener);
        group.getItems().addListener((javafx.collections.ListChangeListener<RoomItem>) c -> {
            while (c.next()) {
                if (c.wasAdded()) c.getAddedSubList().forEach(attachListener);
            }
        });
        recalculateRoomTotal.run();

        Button delRoomBtn = new Button("\uD83D\uDDD1");
        delRoomBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #f44336; -fx-font-size: 16px; -fx-cursor: hand; -fx-padding: 0;");
        delRoomBtn.setTooltip(new Tooltip("Delete Room Card"));
        delRoomBtn.setOnAction(e -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Delete Room");
            alert.setHeaderText("Remove Room Card: " + group.getRoomSerial());
            alert.setContentText("Are you sure you want to delete this room card and all its papers?");
            alert.showAndWait().ifPresent(res -> {
                if (res == ButtonType.OK) {
                    roomGroupsList.remove(group);
                    saveConfigs();
                    activityLogger.warn("Deleted Room Card: " + group.getRoomSerial());
                }
            });
        });

        HBox titleBox = new HBox(10, title, subtitle);
        titleBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        HBox headerArea = new HBox(titleBox, headerSpacer, delRoomBtn);
        headerArea.setAlignment(javafx.geometry.Pos.CENTER);
        
        ComboBox<String> printerCombo = new ComboBox<>(FXCollections.observableArrayList(printService.getAvailablePrinters()));
        printerCombo.setPromptText("Assign Printer");
        printerCombo.setMaxWidth(Double.MAX_VALUE);
        printerCombo.setStyle("-fx-font-size: 12px;");
        HBox.setHgrow(printerCombo, Priority.ALWAYS);
        
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
        printerCombo.setButtonCell(printerCombo.getCellFactory().call(null));
        printerCombo.valueProperty().bindBidirectional(group.selectedPrinterProperty());

        Button sendBtn = new Button("SEND TO PRINT");
        sendBtn.setMaxWidth(Double.MAX_VALUE);
        String defaultBtnStyle = "-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8; -fx-background-radius: 6;";
        String sendingBtnStyle = "-fx-background-color: #ff9800; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8; -fx-background-radius: 6;";
        String sentBtnStyle = "-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8; -fx-background-radius: 6;";
        String errorBtnStyle = "-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 8; -fx-background-radius: 6;";
        
        boolean initialDisabled = "None".equals(group.getSelectedPrinter()) || "Offline".equalsIgnoreCase(printerStatusCache.getOrDefault(group.getSelectedPrinter(), "Ready"));
        sendBtn.setDisable(initialDisabled);
        
        group.selectedPrinterProperty().addListener((obs, old, val) -> {
            Platform.runLater(() -> {
                if ("Sending...".equals(sendBtn.getText())) return;
                boolean disabled = "None".equals(val) || "Offline".equalsIgnoreCase(printerStatusCache.getOrDefault(val, "Ready"));
                sendBtn.setDisable(disabled);
            });
        });

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
                        sendBtn.setText("SEND TO PRINT");
                        sendBtn.setStyle(defaultBtnStyle);
                        if (!"Offline".equalsIgnoreCase(printerStatusCache.getOrDefault(group.getSelectedPrinter(), "Ready")) && !"None".equals(group.getSelectedPrinter())) {
                            sendBtn.setDisable(false);
                        } else {
                            sendBtn.setDisable(true);
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

        Label printerIndicator = new Label();
        printerIndicator.setStyle("-fx-font-size: 10px; -fx-font-weight: bold;");
        
        Runnable updateBtnState = () -> {
            String p = group.getSelectedPrinter();
            Platform.runLater(() -> {
                if ("Sending...".equals(sendBtn.getText())) return;
                if (p == null || "None".equals(p) || p.trim().isEmpty()) {
                    printerIndicator.setText("\u26A0 NO PRINTER SELECTED");
                    printerIndicator.setStyle("-fx-text-fill: #ff9800;");
                    sendBtn.setDisable(true);
                } else {
                    String s = printerStatusCache.getOrDefault(p, "Ready");
                    if ("Offline".equalsIgnoreCase(s) || "Other".equalsIgnoreCase(s) || "Unknown".equalsIgnoreCase(s)) {
                        printerIndicator.setText("\u26A0 PRINTER OFFLINE");
                        printerIndicator.setStyle("-fx-text-fill: #f44336;");
                        sendBtn.setDisable(true);
                    } else {
                        printerIndicator.setText("\u2714 Printer Ready");
                        printerIndicator.setStyle("-fx-text-fill: #4CAF50;");
                        sendBtn.setDisable(false);
                    }
                }
            });
        };

        javafx.collections.MapChangeListener<String, String> statusListener = change -> {
            String p = group.getSelectedPrinter();
            if (p != null && change.getKey().equals(p)) {
                updateBtnState.run();
            }
        };
        printerStatusCache.addListener(new javafx.collections.WeakMapChangeListener<>(statusListener));
        card.getProperties().put("statusListener", statusListener);
        
        group.selectedPrinterProperty().addListener((o, ov, nv) -> updateBtnState.run());

        HBox printerBox = new HBox(10, printerCombo, printerIndicator);
        printerBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        card.getChildren().addAll(headerArea, printerBox, sendBtn);

        card.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                showRoomDetailsModal(group);
            }
        });

        return card;
    }


    private void showRoomDetailsModal(RoomGroup group) {
        Stage modalStage = new Stage();
        modalStage.setTitle("Room Details - " + group.getRoomSerial());
        modalStage.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        
        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setStyle("-fx-background-color: white;");
        
        Label title = new Label("Room: " + group.getRoomSerial());
        title.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");
        
        Button addPaperBtn = new Button("+ Add Paper");
        addPaperBtn.setStyle("-fx-background-color: #e3f2fd; -fx-text-fill: #1976d2; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 6 12; -fx-background-radius: 6; -fx-cursor: hand;");
        addPaperBtn.setOnAction(e -> showManualFilePicker(group));
        
        HBox header = new HBox(20, title, addPaperBtn);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
            TableView<RoomItem> table = new TableView<>(group.getItems());
            table.setPrefHeight(230);
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
            table.setStyle("-fx-font-size: 13px; -fx-background-color: transparent;");
    
            TableColumn<RoomItem, String> qpCol = new TableColumn<>("Question Paper");
            qpCol.setCellValueFactory(d -> {
                RoomItem ri = d.getValue();
                return javafx.beans.binding.Bindings.createStringBinding(() -> ri.getDisplayName(), ri.matchedFileProperty());
            });
            qpCol.setPrefWidth(200);
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
            
            TableColumn<RoomItem, String> styleCol = new TableColumn<>("Mode"); // Mode
            styleCol.setCellValueFactory(d -> {
                RoomItem ri = d.getValue();
                return javafx.beans.binding.Bindings.createStringBinding(() -> {
                    FileItem f = ri.getMatchedFile();
                    return f != null ? f.getStyle() : "-";
                }, ri.matchedFileProperty());
            });
            styleCol.setPrefWidth(80);
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
    
            TableColumn<RoomItem, String> ppCol = new TableColumn<>("Printed Sheets"); // Printed Sheets
            ppCol.setPrefWidth(130);
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
    
            TableColumn<RoomItem, Integer> countCol = new TableColumn<>("Quantity"); // Qty
            countCol.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().getCount()));
            countCol.setPrefWidth(90);
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
    
            TableColumn<RoomItem, String> statusCol = new TableColumn<>("Status"); // Stat
            statusCol.setCellValueFactory(d -> d.getValue().statusProperty());
            statusCol.setPrefWidth(100);
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
    
            TableColumn<RoomItem, Void> editCol = new TableColumn<>("Edit");
            editCol.setPrefWidth(60);
            editCol.setCellFactory(tc -> new TableCell<RoomItem, Void>() {
                private final Button btn = new Button("E"); // Edit
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
                                    if (newQty > 0) {
                                        item.setCount(newQty); 
                                        activityLogger.info("Updated Qty for " + item.getQpCode() + " to " + newQty);
                                        saveConfigs();
                                        table.refresh();
                                        String p = group.getSelectedPrinter();
                                        if (p != null && !"None".equals(p) && !p.trim().isEmpty()) {
                                            printSingleRoomItem(item, p);
                                        } else {
                                            Alert alert = new Alert(Alert.AlertType.WARNING, "No printer assigned! Quantity updated but not printed.");
                                            alert.show();
                                        }
                                    }
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
                        setAlignment(javafx.geometry.Pos.CENTER);
                    }
                }
            });
    
            TableColumn<RoomItem, Void> logCol = new TableColumn<>("Logs");
            logCol.setPrefWidth(60);
            logCol.setCellFactory(tc -> new TableCell<RoomItem, Void>() {
                private final Button aiBtn = new Button("L"); // Logs
                {
                    aiBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #9c27b0; -fx-font-size: 14px; -fx-padding: 0; -fx-cursor: hand;");
                    aiBtn.setOnAction(e -> {
                        RoomItem item = getTableRow().getItem();
                        if (item != null && item.getMatchedFile() != null) {
                            showAiLogs(item.getMatchedFile());
                        }
                    });
                }
                @Override protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty) { setGraphic(null); setStyle(""); }
                    else {
                        RoomItem ri = getTableRow().getItem();
                        boolean hasLogs = ri != null && ri.getMatchedFile() != null && ri.getMatchedFile().getAiLogs() != null && !ri.getMatchedFile().getAiLogs().trim().isEmpty();
                        aiBtn.setVisible(hasLogs);
                        aiBtn.setManaged(hasLogs);
                        setGraphic(aiBtn);
                        setAlignment(javafx.geometry.Pos.CENTER);
                    }
                }
            });
    
            TableColumn<RoomItem, Void> delCol = new TableColumn<>("Delete");
            delCol.setPrefWidth(70);
            delCol.setCellFactory(tc -> new TableCell<RoomItem, Void>() {
                private final Button btn = new Button("\u2715"); // X
                {
                    btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #f44336; -fx-font-weight: bold; -fx-padding: 0; -fx-cursor: hand;");
                    btn.setOnAction(e -> {
                        RoomItem item = getTableRow().getItem();
                        if (item != null) {
                            group.getItems().remove(item);
                            activityLogger.warn("Manually removed entry from Room " + group.getRoomSerial());
                            saveConfigs();
                        }
                    });
                }
                @Override protected void updateItem(Void item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty) { setGraphic(null); setStyle(""); }
                    else {
                        RoomItem ri = getTableRow().getItem();
                        boolean needStaple = ri != null && ri.getMatchedFile() != null && "Booklet".equals(ri.getMatchedFile().getStyle()) && calculatePP(ri.getMatchedFile()) > 1;
                        btn.setStyle("-fx-background-color: transparent; -fx-text-fill: " + (needStaple ? "white" : "#f44336") + "; -fx-font-weight: bold; -fx-padding: 0; -fx-cursor: hand;");
                        setGraphic(btn);
                        setAlignment(javafx.geometry.Pos.CENTER);
                    }
                }
            });
    
            table.getColumns().addAll(qpCol, styleCol, ppCol, countCol, statusCol, editCol, logCol, delCol);
    
            table.setRowFactory(tv -> {
                TableRow<RoomItem> row = new TableRow<RoomItem>();
                
                // Helper to update row style based on item properties
                Runnable updateRowStyle = () -> {
                    RoomItem item = row.getItem();
                    row.styleProperty().unbind();
                    row.opacityProperty().unbind();
                    row.setOpacity(1.0);
    
                    if (item == null) { row.setStyle(""); return; }
    
                    boolean isMap = item.getMatchedFile() != null && hasMapKeywords(item.getMatchedFile());
                    
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

        table.setPrefHeight(400);
        
        Button closeBtn = new Button("Close");
        closeBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 20; -fx-background-radius: 4;");
        closeBtn.setOnAction(e -> modalStage.close());
        
        HBox bottom = new HBox(closeBtn);
        bottom.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        
        root.getChildren().addAll(header, table, bottom);
        
        javafx.scene.Scene scene = new javafx.scene.Scene(root, 900, 550);
        modalStage.setScene(scene);
        modalStage.show();
    }


    private void killAllProcesses() {
        try {
            activityLogger.info("Executing ultimate process purge...");
            
            // 1. Dispose CEF App correctly
            if (cefApp != null) {
                try { cefApp.dispose(); } catch (Exception e) {}
            }
            
            // 2. Kill Chromium helpers
            Runtime.getRuntime().exec("taskkill /F /IM jcef_helper.exe /T");
            
            // 3. Force kill this Java instance and all others with the same window title
            // This is the most reliable way to clear stale "ghost" windows
            new Thread(() -> {
                try {
                    Thread.sleep(800);
                    // Kill by window title (case sensitive to match primaryStage.setTitle)
                    String targetTitle = "Smart QP Print Manager - AI Engine V7.3";
                    Runtime.getRuntime().exec("taskkill /F /FI \"WINDOWTITLE eq " + targetTitle + "*\" /T");
                    
                    // Kill the executable and generic javaw if they persist
                    Runtime.getRuntime().exec("taskkill /F /IM \"Smart QP Print Manager.exe\" /T");
                    Runtime.getRuntime().exec("taskkill /F /IM javaw.exe /FI \"WINDOWTITLE eq " + targetTitle + "*\" /T");
                    
                    Thread.sleep(200);
                    System.exit(0);
                } catch (Exception e) {
                    System.exit(0);
                }
            }).start();
            
        } catch (Exception e) {
            System.exit(0);
        }
    }

    private javafx.scene.Parent createAboutView() {
        VBox mainLayout = new VBox(0);
        mainLayout.setStyle("-fx-background-color: #ffffff;");

        // --- PREMIUM GRADIENT HEADER ---
        VBox header = new VBox(25);
        header.setPadding(new Insets(80, 20, 80, 20));
        header.setAlignment(javafx.geometry.Pos.CENTER);
        header.setStyle("-fx-background-color: linear-gradient(to bottom right, #0d1b2a, #1b263b, #415a77);");

        Label title = new Label("Smart QP Print Manager");
        title.setStyle("-fx-font-size: 52px; -fx-font-weight: bold; -fx-text-fill: white; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 20, 0.5, 0, 5);");
        
        Label version = new Label("AI-Powered Examination Logistics | v7.3 Enterprise");
        version.setStyle("-fx-font-size: 24px; -fx-text-fill: #e0e1dd; -fx-font-weight: bold; -fx-letter-spacing: 1.5px;");
        
        Label branding = new Label("A Premium Product of Magnolia Creations");
        branding.setStyle("-fx-font-size: 20px; -fx-font-style: italic; -fx-text-fill: rgba(255, 255, 255, 0.9); -fx-padding: 20 0 0 0;");
        
        header.getChildren().addAll(title, version, branding);

        // --- SCROLLABLE CONTENT AREA ---
        VBox content = new VBox(60);
        content.setPadding(new Insets(60, 80, 80, 80));
        content.setMaxWidth(1300);
        content.setAlignment(javafx.geometry.Pos.TOP_CENTER);

        // --- 1. MISSION STATEMENT ---
        VBox mission = new VBox(15);
        mission.setAlignment(javafx.geometry.Pos.CENTER);
        Label missionTitle = new Label("Precision Printing for High-Stakes Exams");
        missionTitle.setStyle("-fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: #1b263b;");
        Label missionText = new Label("Smart QP Print Manager is designed specifically for University Examination Departments. " +
                                     "It eliminates the chaos of manual counting, sorting, and routing, allowing you to focus on the exam instead of the printer.");
        missionText.setWrapText(true);
        missionText.setMaxWidth(900);
        missionText.setStyle("-fx-font-size: 18px; -fx-text-fill: #455a64; -fx-text-alignment: center; -fx-line-spacing: 5;");
        mission.getChildren().addAll(missionTitle, missionText);

        // --- 2. THE THREE-STEP WORKFLOW ---
        HBox steps = new HBox(40);
        steps.setAlignment(javafx.geometry.Pos.CENTER);
        steps.getChildren().addAll(
            createStepCard("Step 1: INPUT", "Drag your PDF files into the Queue. The AI immediately analyzes page counts, detects MCQ parts, and identifies unique Paper Codes hidden in the files.", "#e3f2fd", "#1565c0"),
            createStepCard("Step 2: SYNC", "Connect to Examflow Cloud or upload a seating JSON. The system instantly maps every student to their specific paper across hundreds of exam rooms.", "#f1f8e9", "#2e7d32"),
            createStepCard("Step 3: EXECUTE", "One click to print. The manager chooses the best printer, applies the correct style (Booklet/Duplex), and even stamps the Paper Code for you.", "#fff3e0", "#e65100")
        );

        // --- 3. DETAILED FEATURE BREAKDOWN ---
        VBox featureDetailSection = new VBox(40);
        Label detailTitle = new Label("Deep-Dive: How the Intelligence Works");
        detailTitle.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: #1b263b;");
        
        javafx.scene.layout.GridPane detailGrid = new javafx.scene.layout.GridPane();
        detailGrid.setHgap(60);
        detailGrid.setVgap(50);
        detailGrid.setAlignment(javafx.geometry.Pos.CENTER);
        
        detailGrid.add(createDetailItem("Advanced MCQ & SDE Detection", 
            "The engine scans the internal text of every PDF. If it finds 'MCQ' or 'SDE' keywords, it automatically 'Subtracts' those pages into a separate job. " +
            "This ensures that Main Papers and MCQ parts are printed with the correct settings (e.g., Booklet for Main, Simplex for MCQ) automatically."), 0, 0);
            
        detailGrid.add(createDetailItem("The 'Temp-First' Safety Vault", 
            "We prioritize your data integrity. Every operation - splitting, page removal, or adding Paper Code stamps - is performed on a temporary copy. " +
            "Your original master PDFs in your Downloads or Archive folders remain 100% untouched and original."), 1, 0);

        detailGrid.add(createDetailItem("Aggressive QP Pattern Matching", 
            "Our AI uses multi-layer regex logic to find Paper Codes. Even if the code is missing from the JSON or hidden in a complex filename (like 'EDE_22.05.26_143812_Sub'), " +
            "the system will find the 5-8 digit code and link it correctly."), 0, 1);

        detailGrid.add(createDetailItem("Examflow Cloud Integration", 
            "Skip the manual JSON files! Sync your app with the Examflow platform using your College ID. " +
            "The app fetches real-time student counts and room assignments directly from our servers with one click."), 1, 1);

        detailGrid.add(createDetailItem("Dynamic QR & Text Overlays", 
            "The system can automatically 'Stamp' the Paper Code on the top-left of every page. " +
            "This ensures that even if papers get mixed up, your staff can identify which subject the paper belongs to instantly."), 0, 2);

        detailGrid.add(createDetailItem("Hardware Health Dashboard", 
            "Integrated with Windows Print Services to monitor 'Live' hardware status. " +
            "The dashboard alerts you to low toner, paper jams, or offline status BEFORE you start a large batch print job."), 1, 2);

        featureDetailSection.getChildren().addAll(new Separator(), detailTitle, detailGrid);

        // --- 4. TROUBLESHOOTING & FAQ ---
        VBox faqSection = new VBox(25);
        faqSection.setPadding(new Insets(40, 60, 40, 60));
        faqSection.setStyle("-fx-background-color: #f8f9fa; -fx-background-radius: 20;");
        
        Label faqTitle = new Label("Common Questions & Troubleshooting");
        faqTitle.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: #37474f;");
        
        VBox faqItems = new VBox(20);
        faqItems.getChildren().addAll(
            createFaqRow("Q: Why is a room card showing 'Pending' (Red Icon)?", "A: This means the Paper Code requested in the JSON/Cloud wasn't found in your Print Queue. Add the PDF file, and it will auto-link."),
            createFaqRow("Q: Can I change the printer for just one room?", "A: Yes! Use the 'Assign Printer' dropdown on each individual Room Card to override the default system settings."),
            createFaqRow("Q: What is 'Simulation Mode'?", "A: Accessible in Settings, this allows you to test the entire workflow (splitting, routing, cloud sync) without actually using any paper or ink."),
            createFaqRow("Q: How do I clean up the temporary files?", "A: Simply click 'Clear All' or close the application. The system will automatically purge the 'Safety Vault' to save disk space.")
        );
        
        faqSection.getChildren().addAll(faqTitle, faqItems);

        // --- 5. SYSTEM INFO FOOTER ---
        VBox footer = new VBox(15);
        footer.setAlignment(javafx.geometry.Pos.CENTER);
        footer.setPadding(new Insets(60, 0, 40, 0));
        
        Label copyright = new Label("\u00A9 2026 Magnolia Creations. All Rights Reserved.");
        copyright.setStyle("-fx-font-size: 14px; -fx-text-fill: #90a4ae;");
        
        HBox configInfo = new HBox(10, new Label("Configuration Storage:"), new TextField(configManager.getConfigPath()) {{ setEditable(false); setPrefWidth(500); setStyle("-fx-background-color: #eceff1; -fx-text-fill: #546e7a; -fx-font-size: 11px;"); }});
        configInfo.setAlignment(javafx.geometry.Pos.CENTER);
        
        footer.getChildren().addAll(new Separator(), configInfo, copyright);

        content.getChildren().addAll(mission, steps, featureDetailSection, faqSection, footer);

        ScrollPane sp = new ScrollPane(new VBox(header, new StackPane(content) {{ setAlignment(javafx.geometry.Pos.TOP_CENTER); }}));
        sp.setFitToWidth(true);
        sp.setStyle("-fx-background-color: transparent; -fx-background: #ffffff;");
        return sp;
    }

    private VBox createStepCard(String step, String desc, String bgColor, String accentColor) {
        VBox card = new VBox(20);
        card.setPadding(new Insets(35));
        card.setPrefWidth(350);
        card.setStyle("-fx-background-color: " + bgColor + "; -fx-background-radius: 25; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 15, 0, 0, 10); -fx-border-color: " + accentColor + "; -fx-border-width: 1; -fx-border-radius: 25;");
        
        Label lblStep = new Label(step);
        lblStep.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: " + accentColor + ";");
        
        Label lblDesc = new Label(desc);
        lblDesc.setWrapText(true);
        lblDesc.setStyle("-fx-font-size: 16px; -fx-text-fill: #263238; -fx-line-spacing: 4;");
        
        card.getChildren().addAll(lblStep, lblDesc);
        return card;
    }

    private VBox createDetailItem(String title, String desc) {
        VBox box = new VBox(12);
        Label lblTitle = new Label("> " + title);
        lblTitle.setStyle("-fx-font-size: 19px; -fx-font-weight: bold; -fx-text-fill: #1b263b;");
        Label lblDesc = new Label(desc);
        lblDesc.setWrapText(true);
        lblDesc.setStyle("-fx-font-size: 15px; -fx-text-fill: #546e7a; -fx-line-spacing: 3;");
        box.getChildren().addAll(lblTitle, lblDesc);
        box.setPrefWidth(500);
        return box;
    }

    private VBox createFaqRow(String q, String a) {
        VBox row = new VBox(5);
        Label lblQ = new Label(q);
        lblQ.setStyle("-fx-font-weight: bold; -fx-text-fill: #1565c0; -fx-font-size: 15px;");
        Label lblA = new Label(a);
        lblA.setWrapText(true);
        lblA.setStyle("-fx-text-fill: #455a64; -fx-font-size: 14px;");
        row.getChildren().addAll(lblQ, lblA);
        return row;
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

    private void updateRoomAlerts() {
        Platform.runLater(() -> {
            boolean hasHistory = roomGroupsList.stream().flatMap(g -> g.getItems().stream()).anyMatch(i -> i.getMatchedFile() != null && hasMapKeywords(i.getMatchedFile()));
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
            roomTotalCount.setText("Total Rooms: " + roomGroupsList.size());
            roomTotalPP.setText("Total PP: " + totalPPVal);
        });
    }

    private void refreshGlobalAlerts() {
        Platform.runLater(() -> {
            boolean hasHistory = fileQueue.stream().anyMatch(f -> hasMapKeywords(f)) || roomGroupsList.stream().flatMap(g -> g.getItems().stream()).anyMatch(i -> i.getMatchedFile() != null && hasMapKeywords(i.getMatchedFile()));
            
            boolean hasStaple = fileQueue.stream().anyMatch(f -> "Booklet".equals(f.getStyle()) && calculatePP(f) > 1) ||
                                 roomGroupsList.stream().flatMap(g -> g.getItems().stream()).anyMatch(i -> i.getMatchedFile() != null && "Booklet".equals(i.getMatchedFile().getStyle()) && calculatePP(i.getMatchedFile()) > 1);

            globalMapAlert.setVisible(hasHistory);
            globalStapleAlert.setVisible(hasStaple);
            
            roomMapAlert.setVisible(hasHistory);
            roomStapleAlert.setVisible(hasStaple);
        });
    }

    private void previewRoomItem(RoomItem item) {
        if (item.getMatchedFile() == null) { updateStatus("No matched file to preview."); return; }
        previewFile(item.getMatchedFile(), true, true);
    }

    private boolean isSDEStream(String stream) {
        if (stream == null) return false;
        String s = stream.toUpperCase();
        return s.contains("SDE") || s.contains("EDE") || s.contains("DISTANCE") || s.contains("EXTERNAL");
    }

    private String extractCourseCodeFromText(String text) {
        if (text == null) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\(([A-Z0-9().-]+)\\)");
        java.util.regex.Matcher m = p.matcher(text);
        if (m.find()) return m.group(1).toUpperCase();
        return "";
    }

    private void showAiLogs(FileItem item) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("AI Routing Logs");
        alert.setHeaderText("AI Matching Reasoning for: " + item.getFileName());
        
        TextArea textArea = new TextArea(item.getAiLogs());
        textArea.setEditable(false);
        textArea.setWrapText(true);
        textArea.setPrefHeight(400);
        textArea.setPrefWidth(600);
        textArea.setStyle("-fx-font-family: 'Consolas', 'Monospace'; -fx-font-size: 12px;");
        
        VBox content = new VBox(10, new Label("Step-by-step logical evaluation:"), textArea);
        alert.getDialogPane().setContent(content);
        alert.showAndWait();
    }

    private void initFileChooser(FileChooser fc, String title) {
        fc.setTitle(title);
        if (config.getLastDirectory() != null && !config.getLastDirectory().isEmpty()) {
            File lastDir = new File(config.getLastDirectory());
            if (lastDir.exists() && lastDir.isDirectory()) {
                fc.setInitialDirectory(lastDir);
            }
        }
    }

    private void updateLastDirectory(File file) {
        if (file != null) {
            File dir = file.getParentFile();
            if (dir != null && dir.exists()) {
                config.setLastDirectory(dir.getAbsolutePath());
                saveConfigs();
            }
        }
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
        initFileChooser(fc, "Select Room JSON");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        File file = fc.showOpenDialog(stage);
        if (file != null) {
            updateLastDirectory(file);
            processRoomWiseJsonFile(file);
        }
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
        // PASS 1: CONSOLIDATION
        Map<String, RoomGroup> groups = new LinkedHashMap<>();
        for (RoomGroup g : roomGroupsList) groups.put(g.getRoomSerial(), g);
        Map<String, Map<String, RoomItem>> consolidationMap = new LinkedHashMap<>();
        Set<String> processedGroups = new HashSet<>();
        int nodeIdCounter = 0;

        if (root.isArray()) {
            for (JsonNode node : root) {
                String roomSerial = node.path("roomSerial").asText("Unknown").trim();
                String qpCode = node.path("qpCode").asText("").trim();
                String pdfFileName = node.path("pdfFileName").asText("");
                String courseName = node.path("courseName").asText(pdfFileName).trim();
                int count = node.path("count").asInt(0);
                String stream = node.path("stream").asText("Regular").trim();

                RoomGroup group = groups.computeIfAbsent(roomSerial, RoomGroup::new);
                
                // CRITICAL FIX: Clear existing items on reload to prevent duplicates
                if (!processedGroups.contains(roomSerial)) {
                    group.getItems().clear();
                    processedGroups.add(roomSerial);
                }
                
                if (qpCode.isEmpty() && !pdfFileName.isEmpty()) {
                    qpCode = extractQPFromFileName(pdfFileName);
                }

                String groupSubKey = qpCode.isEmpty() ? courseName.toUpperCase() : qpCode.toUpperCase();
                String qpKey = groupSubKey + "|" + stream.toUpperCase();
                
                Map<String, RoomItem> qpMap = consolidationMap.computeIfAbsent(roomSerial, k -> new LinkedHashMap<>());
                if (qpMap.containsKey(qpKey)) {
                    RoomItem existing = qpMap.get(qpKey);
                    existing.setCount(existing.getCount() + count);
                    if (!courseName.isEmpty() && !existing.getCourseName().contains(courseName)) {
                        existing.setCourseName(existing.getCourseName() + ", " + courseName);
                    }
                } else {
                    RoomItem roomItem = new RoomItem(roomSerial, qpCode, pdfFileName, count, nodeIdCounter++);
                    roomItem.setCourseName(courseName);
                    roomItem.setStream(stream);
                    qpMap.put(qpKey, roomItem);
                }
            }
        }

        // PASS 2: MATCHING
        int matchedItems = 0;
        List<Map.Entry<String, Map<String, RoomItem>>> roomEntries = new ArrayList<>(consolidationMap.entrySet());
        int totalRooms = roomEntries.size();
        Platform.runLater(() -> { globalProgress.set(0.0); globalProgressVisible.set(true); });

        for (int i = 0; i < totalRooms; i++) {
            Map.Entry<String, Map<String, RoomItem>> roomEntry = roomEntries.get(i);
            String roomSerial = roomEntry.getKey();
            RoomGroup group = groups.get(roomSerial);
            
            final int currentRoomIdx = i + 1;
            final double prog = (double) currentRoomIdx / (totalRooms > 0 ? totalRooms : 1);
            updateStatus("Processing Room " + currentRoomIdx + " of " + totalRooms + "...");
            Platform.runLater(() -> globalProgress.set(prog));

            for (RoomItem roomItem : roomEntry.getValue().values()) {
                String courseName = roomItem.getCourseName();
                int count = roomItem.getCount();

                boolean matched = false;
                if (config.isAiRoutingEnabled()) {
                    List<MatchResult> aiResults = aiRoutingAgent.findAllMatchesForRoom(roomItem, fileQueue);
                    
                    // Filter splits
                    boolean hasSplits = aiResults.stream().anyMatch(res -> {
                        String name = res.getMatchedFile().getFileName();
                        return name.startsWith("Split_") || name.startsWith("MCQ_") || 
                               name.startsWith("Main_") || name.startsWith("Remain_") ||
                               (res.getMatchedFile().getOverlayText() != null && !res.getMatchedFile().getOverlayText().isEmpty());
                    });
                    
                    if (hasSplits) {
                        aiResults.removeIf(res -> {
                            String name = res.getMatchedFile().getFileName();
                            boolean isProduct = name.startsWith("Split_") || name.startsWith("MCQ_") || 
                                              name.startsWith("Main_") || name.startsWith("Remain_") ||
                                              (res.getMatchedFile().getOverlayText() != null && !res.getMatchedFile().getOverlayText().isEmpty());
                            return !isProduct;
                        });
                    }

                    // Filter: Keep only the best scores (Ties allowed if they are products)
                    double maxScore = aiResults.stream().mapToDouble(MatchResult::getConfidenceScore).max().orElse(0.0);
                    if (maxScore > 0) {
                        aiResults.removeIf(res -> res.getConfidenceScore() < maxScore);
                    }

                    // Deduplicate AI results by physical file path AND filename property
                    List<MatchResult> uniqueAi = new ArrayList<>();
                    Set<String> seenPaths = new HashSet<>();
                    Set<String> seenNames = new HashSet<>();
                    for (MatchResult res : aiResults) {
                        String pathKey = res.getMatchedFile().getFile().getAbsolutePath().toLowerCase();
                        String nameKey = res.getMatchedFile().getFileName().toLowerCase();
                        if (!seenPaths.contains(pathKey) && !seenNames.contains(nameKey)) {
                            uniqueAi.add(res); 
                            seenPaths.add(pathKey);
                            seenNames.add(nameKey);
                        }
                    }

                    // NEW: Decisive Single-Match Rule (V7.0)
                    // If no split components are detected, we MUST only have one match per room item.
                    if (!hasSplits && uniqueAi.size() > 1) {
                        // Keep only the first one (already filtered by maxScore)
                        MatchResult best = uniqueAi.get(0);
                        uniqueAi.clear();
                        uniqueAi.add(best);
                    }

                    boolean firstMatch = true;
                    for (MatchResult res : uniqueAi) {
                        FileItem fi = res.getMatchedFile();
                        RoomItem partItem;
                        if (firstMatch) {
                            partItem = roomItem;
                            firstMatch = false;
                        } else {
                            partItem = new RoomItem(roomSerial, roomItem.getQpCode(), roomItem.getPdfFileName(), count, roomItem.getSourceNodeId());
                            partItem.setCourseName(courseName);
                            partItem.setStream(roomItem.getStream());
                        }
                        
                        partItem.setMatchedFile(fi);
                        partItem.setStatus("Ready");
                        
                        // Auto-Fill missing QP code from file
                        if (partItem.getQpCode() == null || partItem.getQpCode().isEmpty()) {
                            String fqp = extractQPFromFileName(fi.getFileName());
                            if (fqp != null && !fqp.isEmpty()) partItem.setQpCode(fqp);
                        }

                        group.getItems().add(partItem);
                        matched = true;
                        matchedItems++;
                        activityLogger.info("Room " + roomSerial + ": AI Matched [" + fi.getFileName() + "] for " + count + " students");
                    }
                } else {
                    // LEGACY PATH
                    List<FileItem> legacyMatches = new ArrayList<>();
                    Set<String> seenLegacy = new HashSet<>();
                    for (FileItem fi : fileQueue) {
                        String fn = fi.getFileName();
                        String eqp = extractQPFromFileName(fn);
                        String qpCode = roomItem.getQpCode();
                        if (!qpCode.isEmpty() && (qpCode.equalsIgnoreCase(eqp) || fn.contains("_" + qpCode + "_") || fn.contains("_" + qpCode + "."))) {
                            String pathKey = fi.getFile().getAbsolutePath().toLowerCase();
                            if (!seenLegacy.contains(pathKey)) { legacyMatches.add(fi); seenLegacy.add(pathKey); }
                        }
                    }

                    boolean firstMatch = true;
                    for (FileItem fileItem : legacyMatches) {
                        RoomItem partItem;
                        if (firstMatch) {
                            partItem = roomItem;
                            firstMatch = false;
                        } else {
                            partItem = new RoomItem(roomSerial, roomItem.getQpCode(), roomItem.getPdfFileName(), count, roomItem.getSourceNodeId());
                            partItem.setCourseName(courseName);
                            partItem.setStream(roomItem.getStream());
                        }
                        partItem.setMatchedFile(fileItem);
                        partItem.setStatus("Ready");
                        group.getItems().add(partItem);
                        matched = true;
                        matchedItems++;
                        activityLogger.info("Room " + roomSerial + ": Matched " + fileItem.getFileName() + " (" + count + ")");
                    }
                }

                if (!matched) {
                    roomItem.setStatus("Pending");
                    group.getItems().add(roomItem);
                    activityLogger.error("Room " + roomSerial + ": File NOT FOUND for " + courseName);
                }
            }
        }

        // --- FINAL UI REFRESH ---
        final int finalMatched = matchedItems;
        final int finalGroupSize = groups.size();
        Platform.runLater(() -> {
            roomGroupsList.setAll(groups.values());
            refreshGlobalAlerts();
            validationService.validateAppState(); // Trigger Health Check after room load
            activityLogger.success("Room Routing complete: " + finalGroupSize + " rooms, " + finalMatched + " matched.");
            updateStatus("Ready. " + finalGroupSize + " rooms loaded.");
            globalProgressVisible.set(false);
        });
    }

    private void teardownRoomGroupListeners(RoomGroup group) {
        javafx.collections.ListChangeListener<RoomItem> listener = roomGroupListeners.remove(group);
        if (listener != null) {
            group.getItems().removeListener(listener);
        }
    }

    private void setupRoomGroupListeners(RoomGroup group) {
        // Prevent duplicate listener registration
        if (roomGroupListeners.containsKey(group)) return;

        // Helper to recalculate room total from items, avoiding double-counting splits
        Runnable recalculateRoomTotal = () -> {
            // Group by node or key to ensure Main/MCQ splits are counted as one student set
            java.util.Map<Object, Integer> nodeCounts = new java.util.HashMap<>();
            for (RoomItem item : group.getItems()) {
                Object key;
                int nid = item.getSourceNodeId();
                if (nid >= 0) {
                    key = nid;
                } else {
                    // Manual/Testing key: Unique per Subject + Stream
                    key = (item.getCourseName() != null ? item.getCourseName() : "") + "|" + (item.getStream() != null ? item.getStream() : "");
                }
                nodeCounts.put(key, Math.max(nodeCounts.getOrDefault(key, 0), item.getCount()));
            }
            int sum = nodeCounts.values().stream().mapToInt(Integer::intValue).sum();
            if (group.getTotalStudents() != sum) {
                Platform.runLater(() -> group.setTotalStudents(sum));
            }
        };

        javafx.collections.ListChangeListener<RoomItem> listener = c -> {
            recalculateRoomTotal.run();
            saveConfigs();
            while (c.next()) {
                if (c.wasAdded()) {
                    c.getAddedSubList().forEach(this::attachRoomItemListeners);
                }
            }
        };
        recalculateRoomTotal.run();
    }

    private void showManualRoomDialog() {
        Dialog<RoomGroup> dialog = new Dialog<>();
        dialog.setTitle("Manually Add Room");
        dialog.setHeaderText("Enter Room details to create a new card:");

        ButtonType createType = new ButtonType("Create Room", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);

        TextField roomNo = new TextField(); roomNo.setPromptText("e.g. 101 or Lab A");
        TextField totalStd = new TextField(); totalStd.setPromptText("Total Students");

        VBox content = new VBox(10, new Label("Room Number/Serial:"), roomNo, new Label("Total Student Count:"), totalStd);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(bt -> {
            if (bt == createType && !roomNo.getText().isEmpty()) {
                RoomGroup g = new RoomGroup(roomNo.getText());
                try { g.setTotalStudents(Integer.parseInt(totalStd.getText())); } catch (Exception e) {}
                return g;
            }
            return null;
        });

        dialog.showAndWait().ifPresent(g -> {
            roomGroupsList.add(g);
            saveConfigs();
            activityLogger.success("Manually created Room " + g.getRoomSerial());
        });
    }

    private void showManualFilePicker(RoomGroup group) {
        Dialog<RoomItem> dialog = new Dialog<>();
        dialog.setTitle("Manual Add: Room " + group.getRoomSerial());
        dialog.setHeaderText("Select a file from the Print Queue to add to this room:");

        ButtonType addButtonType = new ButtonType("Add to Room", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(addButtonType, ButtonType.CANCEL);

        TextField search = new TextField();
        search.setPromptText("Search by filename or QP...");

        FilteredList<FileItem> filtered = new FilteredList<>(fileQueue, p -> true);
        search.textProperty().addListener((obs, old, val) -> {
            filtered.setPredicate(item -> {
                if (val == null || val.isEmpty()) return true;
                String low = val.toLowerCase();
                return item.getFileName().toLowerCase().contains(low);
            });
        });

        ListView<FileItem> list = new ListView<>(filtered);
        list.setPrefHeight(250);
        list.setCellFactory(lv -> new ListCell<FileItem>() {
            @Override protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(item.getFileName() + " (" + item.getPageCount() + " pages)");
            }
        });

        Spinner<Integer> qty = new Spinner<>(1, 999, group.getTotalStudents() > 0 ? group.getTotalStudents() : 1);
        qty.setEditable(true);

        VBox content = new VBox(10, new Label("Filter Queue:"), search, list, new Label("Set Copy Count for this room:"), qty);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        final Button okBtn = (Button) dialog.getDialogPane().lookupButton(addButtonType);
        okBtn.setDisable(true);
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, nv) -> okBtn.setDisable(nv == null));

        dialog.setResultConverter(bt -> {
            if (bt == addButtonType) {
                FileItem sel = list.getSelectionModel().getSelectedItem();
                if (sel != null) {
                    String qp = extractQPFromFileName(sel.getFileName());
                    RoomItem ri = new RoomItem(group.getRoomSerial(), qp, sel.getFileName(), qty.getValue(), -1);
                    ri.setMatchedFile(sel);
                    ri.setCourseName("MANUAL: " + sel.getFileName());
                    ri.setStatus("Ready");
                    return ri;
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(newItem -> {
            group.getItems().add(newItem);
            activityLogger.success("Manually added " + newItem.getPdfFileName() + " to Room " + group.getRoomSerial());
            saveConfigs();
        });
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
                    
                    // --- SAFETY RECOVERY: If match is missing or stale, try a last-second QP-based re-link ---
                    if (fileItem == null || !fileQueue.contains(fileItem)) {
                        String qp = roomItem.getQpCode();
                        if (qp != null && !qp.isEmpty()) {
                            FileItem surrogate = fileQueue.stream()
                                .filter(f -> {
                                    String eqp = extractQPFromFileName(f.getFileName());
                                    return qp.equalsIgnoreCase(eqp) || f.getFileName().contains("_" + qp + "_") || f.getFileName().contains("_" + qp + ".");
                                })
                                .findFirst().orElse(null);
                            
                            if (surrogate != null) {
                                fileItem = surrogate;
                                final FileItem finalS = surrogate;
                                Platform.runLater(() -> {
                                    roomItem.setMatchedFile(finalS);
                                    roomItem.setStatus("Ready (Recovered)");
                                });
                                activityLogger.info("Room " + group.getRoomSerial() + ": Auto-recovered match for QP " + qp + " -> " + surrogate.getFileName());
                            }
                        }
                    }
                    
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
                cefClient.addJSDialogHandler(new CefJSDialogHandlerAdapter() {
                      @Override
                      public boolean onJSDialog(CefBrowser browser, String origin_url, JSDialogType dialog_type, String message_text, String default_prompt_text, CefJSDialogCallback callback, BoolRef suppress_message) {
                          SwingUtilities.invokeLater(() -> {
                              if (dialog_type == JSDialogType.JSDIALOGTYPE_ALERT) {
                                  JOptionPane.showMessageDialog(null, message_text, "Browser Alert", JOptionPane.WARNING_MESSAGE);
                                  callback.Continue(true, "");
                              } else if (dialog_type == JSDialogType.JSDIALOGTYPE_CONFIRM) {
                                  int res = JOptionPane.showConfirmDialog(null, message_text, "Browser Confirmation", JOptionPane.YES_NO_OPTION);
                                  callback.Continue(res == JOptionPane.YES_OPTION, "");
                              } else if (dialog_type == JSDialogType.JSDIALOGTYPE_PROMPT) {
                                  String input = JOptionPane.showInputDialog(null, message_text, default_prompt_text);
                                  callback.Continue(input != null, input != null ? input : "");
                              } else {
                                  callback.Continue(true, "");
                              }
                          });
                          return true;
                      }

                      @Override
                      public boolean onBeforeUnloadDialog(CefBrowser browser, String message_text, boolean is_reload, CefJSDialogCallback callback) {
                          SwingUtilities.invokeLater(() -> {
                              String promptMessage = message_text;
                              if (promptMessage == null || promptMessage.trim().isEmpty()) {
                                  promptMessage = is_reload ? "The page that you're looking for used information that you entered. Returning to that page might cause any action you took to be repeated. Do you want to continue?" : "Do you want to leave this site?\nChanges you made may not be saved.";
                              }
                              int res = JOptionPane.showConfirmDialog(null, promptMessage, "Confirm Navigation", JOptionPane.YES_NO_OPTION);
                              callback.Continue(res == JOptionPane.YES_OPTION, "");
                          });
                          return true;
                      }
                  });
                
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
                                // Trigger standard browser download by injecting a click on a download link
                                String pdfUrl = "https://collegeportal.uoc.ac.in/valuation_camp/downloadqp_file?fileid=" + fileId;
                                String js = "(function() { var iframe = document.createElement('iframe'); iframe.style.display = 'none'; iframe.src = '" + pdfUrl + "'; document.body.appendChild(iframe); })();";
                                browser.executeJavaScript(js, "", 0);
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
                                    "    var pageText = document.body.innerText.toUpperCase(); " +
                                    "    var isPageEde = pageText.indexOf('SDE') !== -1 || pageText.indexOf('DISTANCE') !== -1 || pageText.indexOf('EDE') !== -1 || pageText.indexOf('EXTERNAL') !== -1; " +
                                    "    rows.forEach(function(row) { " +
                                    "      var cells = row.querySelectorAll('td'); " +
                                    "      if (cells.length >= 2) { " +
                                    "        var qpVal = ''; var pVal = ''; var syVal = ''; " +
                                    "        var rowText = row.innerText.toUpperCase(); " +
                                    "        var isEde = isPageEde || rowText.indexOf('SDE') !== -1 || rowText.indexOf('EDE') !== -1 || rowText.indexOf('DISTANCE') !== -1 || rowText.indexOf('EXTERNAL') !== -1; " +
                                    "        if (row.cells[cols.qp] && /^\\d+[A-Za-z]?$/.test(row.cells[cols.qp].innerText.trim())) { " +
                                    "          qpVal = row.cells[cols.qp].innerText.trim(); " +
                                    "          if (isEde && !qpVal.endsWith('A')) qpVal += 'A'; " +
                                    "          pVal = row.cells[cols.paper] ? row.cells[cols.paper].innerText.trim() : ''; " +
                                    "          syVal = (cols.syllabus !== -1 && row.cells[cols.syllabus]) ? row.cells[cols.syllabus].innerText.trim().replace(/[()]/g, '') : ''; " +
                                    "          if (syVal && pVal.indexOf(syVal) === -1) pVal = pVal + ' ' + syVal; " +
                                    "          results.push(prefix + qpVal + '\\t' + pVal); " +
                                    "        } else if (/^\\d+[A-Za-z]?$/.test(cells[0].innerText.trim())) { " +
                                    "          qpVal = cells[0].innerText.trim(); " +
                                    "          if (isEde && !qpVal.endsWith('A')) qpVal += 'A'; " +
                                    "          pVal = cells[1].innerText.trim(); " +
                                    "          syVal = (cols.syllabus !== -1 && cells.length > cols.syllabus) ? cells[cols.syllabus].innerText.trim().replace(/[()]/g, '') : ''; " +
                                    "          if (syVal && pVal.indexOf(syVal) === -1) pVal = pVal + ' ' + syVal; " +
                                    "          results.push(prefix + qpVal + '\\t' + pVal); " +
                                    "        } else if (/^\\d+[A-Za-z]?$/.test(cells[1].innerText.trim()) && cells.length >= 3) { " +
                                    "          qpVal = cells[1].innerText.trim(); " +
                                    "          if (isEde && !qpVal.endsWith('A')) qpVal += 'A'; " +
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
                                "              var uniqueIds = {}; for(var j=0; j<btns.length; j++) uniqueIds[btns[j].value.trim()] = true; var uCount = Object.keys(uniqueIds).length; " +
                                "              if(!confirm('Start automated download for ' + uCount + ' unique papers (out of ' + btns.length + ')? (2s delay per file)')) return; " +
                                "              b.disabled = true; " +
                                "              var delayBase = 2000; " +
                                "              var seenIds = {}; var qCount = 0; " +
                                "              for (var i = 0; i < btns.length; i++) { " +
                                "                var cur = btns[i]; var fid = cur.value.trim(); " +
                                "                if (seenIds[fid]) continue; seenIds[fid] = true; " +
                                "                (function(idx, currentBtn, currentQ) { setTimeout(function() { " +
                                "                  var progress = (currentQ + 1) + '/' + uCount; " +
                                "                  b.innerText = '\\u231B Processing ' + progress + '...'; " +
                                "                  var r = currentBtn.closest('tr'); " +
                                "                  var tVal = (r.cells[cols.time]) ? r.cells[cols.time].innerText.replace(/:/g, '_').replace(/\\\\s+/g, '_') : '00_00_AM'; " +
                                "                  var dp = (window._lastDate || '').replace(/[/\\\\-]/g, '.'); " +
                                "                  if (!dp) { var d = new Date(); dp = ('0' + d.getDate()).slice(-2) + '.' + ('0' + (d.getMonth()+1)).slice(-2) + '.' + (d.getFullYear()+'').substring(2); } " +
                                "                  var rowText = r.innerText.toUpperCase(); " +
                                "                  var isEde = rowText.indexOf('EDE') !== -1 || rowText.indexOf('EXTERNAL') !== -1 || rowText.indexOf('SDE') !== -1 || rowText.indexOf('DISTANCE') !== -1 || (r.cells[cols.qp] && r.cells[cols.qp].innerText.trim().toUpperCase().endsWith('A')); " +
                                "                  var prefix = isEde ? 'EDE' : 'REG'; " +
                                "                  var qpCode = (r.cells[cols.qp]) ? r.cells[cols.qp].innerText.trim() : '000000'; " +
                                "                  if (isEde && !qpCode.endsWith('A')) qpCode += 'A'; " +
                                "                  var paperName = (r.cells[cols.paper]) ? r.cells[cols.paper].innerText.replace(/[^a-z0-9]/gi, '_') : 'Subject'; " +
                                "                  var fname = prefix + '_' + dp + '_' + tVal + '_' + qpCode + '_' + paperName; " +
                                "                  window.cefQuery({ request: 'download:' + currentBtn.value.trim() + '|' + fname }); " +
                                "                  if (currentQ === uCount - 1) { b.innerText = '\\u2713 All Files Queued'; setTimeout(function() { b.innerText = 'Start Bulk Download & Queue'; b.disabled = false; }, 3000); } " +
                                "                }, currentQ * delayBase); })(i, cur, qCount); " +
                                "                qCount++; " +
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
"            var inputs = Array.from(document.querySelectorAll('#qp-code-container input[data-course]')); " +
"            var usedPairs = new Set(); " +
"            var matchedInputs = new Set(); " +
"            function sanitizeCourse(name) { " +
"              if (!name) return ''; " +
"              return name.replace(/[\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]/g, ' ') " +
"                         .replace(/[^a-zA-Z0-9\\s()+\\-]/g, '') " +
"                         .replace(/\\s+/g, ' ') " +
"                         .trim(); " +
"            } " +
"            inputs.forEach(function(input) { " +
"              var uiCourseName = sanitizeCourse(input.dataset.course).trim().toUpperCase(); " +
"              var streamName = (input.dataset.stream || '').toUpperCase(); " +
"              var isEdeStream = streamName.indexOf('EDE') !== -1 || streamName.indexOf('SDE') !== -1 || streamName.indexOf('DISTANCE') !== -1 || streamName.indexOf('EXTERNAL') !== -1; " +
"              var validPairs = parsedPairs.filter(function(p){return p.isEde === isEdeStream;}); " +
"              if (validPairs.length === 0) validPairs = parsedPairs; " +
"              var perfectMatch = validPairs.find(function(p){return p.searchText === uiCourseName;}); " +
"              if (perfectMatch) { " +
"                var finalCode = perfectMatch.code; " +
"                if (isEdeStream && !finalCode.endsWith('A')) finalCode += 'A'; " +
"                input.value = finalCode; " +
"                usedPairs.add(perfectMatch); " +
"                matchedInputs.add(input); " +
"                matched++; " +
"              } " +
"            }); " +
"            inputs.forEach(function(input) { " +
"              if (matchedInputs.has(input)) return; " +
"              var uiCourseName = sanitizeCourse(input.dataset.course).trim().toUpperCase(); " +
"              var streamName = (input.dataset.stream || '').toUpperCase(); " +
"              var isEdeStream = streamName.indexOf('EDE') !== -1 || streamName.indexOf('SDE') !== -1 || streamName.indexOf('DISTANCE') !== -1 || streamName.indexOf('EXTERNAL') !== -1; " +
"              var validPairs = parsedPairs.filter(function(p){return p.isEde === isEdeStream;}); " +
"              if (validPairs.length === 0 && isEdeStream) validPairs = parsedPairs; " +
"              var bestMatch = null; " +
"              var maxLength = 0; " +
"              validPairs.forEach(function(p) { " +
"                if (p.searchText.indexOf(uiCourseName) !== -1 || uiCourseName.indexOf(p.searchText) !== -1) { " +
"                  if (p.searchText.length > maxLength) { " +
"                    maxLength = p.searchText.length; " +
"                    bestMatch = p; " +
"                  } " +
"                } " +
"              }); " +
"              if (bestMatch) { " +
"                var finalCode = bestMatch.code; " +
"                if (isEdeStream && !finalCode.endsWith('A')) finalCode += 'A'; " +
"                input.value = finalCode; " +
"                usedPairs.add(bestMatch); " +
"                matchedInputs.add(input); " +
"                matched++; " +
"              } " +
"            }); " +
"            inputs.forEach(function(input) { " +
"              if (matchedInputs.has(input)) return; " +
"              var uiCourseName = sanitizeCourse(input.dataset.course).trim().toUpperCase(); " +
"              var streamName = (input.dataset.stream || '').toUpperCase(); " +
"              var isEdeStream = streamName.indexOf('EDE') !== -1 || streamName.indexOf('SDE') !== -1 || streamName.indexOf('DISTANCE') !== -1 || streamName.indexOf('EXTERNAL') !== -1; " +
"              var validPairs = parsedPairs.filter(function(p){return p.isEde === isEdeStream;}); " +
"              if (validPairs.length === 0 && isEdeStream) validPairs = parsedPairs; " +
"              var words = uiCourseName.split(/[\\s,.\\-\\[\\]()]+/).filter(function(w){return w.length > 2 && !w.match(/^20\\d{2}$/);}); " +
"              var ignoreWords = ['SYLLABUS', 'PART', 'PAPER', 'BASIC', 'COMMON', 'COURSE', 'PROGRAMME', 'EXAMINATION', 'CORE', 'COMPLEMENTARY', 'OPEN', 'ELECTIVE']; " +
"              var coreWords = words.filter(function(w){return ignoreWords.indexOf(w) === -1 && isNaN(w);}); " +
"              var uiYearMatch = uiCourseName.match(/20\\d{2}/); " +
"              var uiYear = uiYearMatch ? uiYearMatch[0] : null; " +
"              if (words.length > 0) { " +
"                var candidates = []; " +
"                validPairs.forEach(function(p) { " +
"                  var pYearMatch = p.searchText.match(/20\\d{2}/); " +
"                  var pYear = pYearMatch ? pYearMatch[0] : null; " +
"                  var score = 0; " +
"                  var coreScore = 0; " +
"                  var consecutiveMatches = 0; " +
"                  var prevMatchedIndex = -1; " +
"                  var lastFoundIndex = -1; " +
"                  var portalWords = p.searchText.split(/[\\s,.\\-\\[\\]()]+/).filter(function(w){return w.length > 2;}); " +
"                  words.forEach(function(w) { " +
"                    var startIndex = lastFoundIndex === -1 ? 0 : lastFoundIndex + 1; " +
"                    var pIdx = portalWords.indexOf(w, startIndex); " +
"                    if (pIdx !== -1) { " +
"                      score++; " +
"                      if (coreWords.indexOf(w) !== -1) coreScore++; " +
"                      if (prevMatchedIndex !== -1 && pIdx === prevMatchedIndex + 1) { " +
"                        consecutiveMatches++; " +
"                      } " +
"                      lastFoundIndex = pIdx; " +
"                      prevMatchedIndex = pIdx; " +
"                    } " +
"                  }); " +
"                  var totalScore = coreScore + (consecutiveMatches * 2); " +
"                  var coreRatio = coreWords.length > 0 ? coreScore / coreWords.length : 0; " +
"                  if ((coreRatio > 0.6 || coreWords.length === 0) && totalScore > 0) { " +
"                    candidates.push({ p: p, totalScore: totalScore, pYear: pYear }); " +
"                  } " +
"                }); " +
"                var bestMatch = null; " +
"                if (candidates.length === 1) { " +
"                    bestMatch = candidates[0].p; " +
"                } else if (candidates.length > 1) { " +
"                    var yearMatched = candidates.filter(function(c) { return !uiYear || !c.pYear || c.pYear === uiYear; }); " +
"                    if (yearMatched.length > 0) { " +
"                        yearMatched.sort(function(a, b) { return b.totalScore - a.totalScore; }); " +
"                        bestMatch = yearMatched[0].p; " +
"                    } " +
"                } " +
"                if (bestMatch) { " +
"                  var finalCode = bestMatch.code; " +
"                  if (isEdeStream && !finalCode.endsWith('A')) finalCode += 'A'; " +
"                  input.value = finalCode; " +
"                  usedPairs.add(bestMatch); " +
"                  matchedInputs.add(input); " +
"                  matched++; " +
"                } " +
"              } " +
"            }); " +
"            var missingInPortal = []; " +
"            inputs.forEach(function(input) { " +
"              if (!input.value) { " +
"                missingInPortal.push(input.dataset.course); " +
"              } " +
"            }); " +
"            var missingInExamflow = []; " +
"            parsedPairs.forEach(function(p) { " +
"              if (!usedPairs.has(p)) { " +
"                missingInExamflow.push(p.searchText); " +
"              } " +
"            }); " +
"            if (missingInPortal.length > 0 || missingInExamflow.length > 0) { " +
"              var alertMsg = 'âš ï¸ MATCHING REPORT âš ï¸\\n\\n'; " +
"              if (missingInPortal.length > 0) { " +
"                alertMsg += 'âŒ MISSING IN PORTAL (These courses need manual mapping):\\n' + missingInPortal.join('\\n') + '\\n\\n'; " +
"              } " +
"              if (missingInExamflow.length > 0) { " +
"                var uniqueMissing = []; " +
"                missingInExamflow.forEach(function(m){ if(uniqueMissing.indexOf(m)===-1) uniqueMissing.push(m); }); " +
"                alertMsg += 'âŒ MISSING IN EXAMFLOW (These portal codes were not used):\\n' + uniqueMissing.join('\\n') + '\\n'; " +
"              } " +
"              setTimeout(function(){ alert(alertMsg); }, 500); " +
"            } " +
"            inputs.forEach(function(input) { " +
"              if (input.value) { " +
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
                                "                st.innerText = 'âœ… Auto-filled ' + matched + ' codes from Portal. Click SAVE QP Codes below.'; " +
                                "              } " +
                                "            } " +
                                "          }; " +
                                "        } " +
                                "        window.portalError = function(msg) { " +
                                "             var fb = document.getElementById('smart-fetch-btn'); " +
                                "             if (fb) { " +
                                  "                fb.innerText = '\\u26A0 ' + msg.toUpperCase(); " +
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
                                "                fb.innerText = 'âŒ› Connecting...'; fb.style.background = '#ff9800'; " +
                                "                window.cefQuery({ request: 'request_portal_data', " +
                                "                  onSuccess: function() { fb.innerText = 'âœ” Sync Sent'; fb.style.background = '#4CAF50'; setTimeout(function(){ fb.innerText = 'FETCH FROM PORTAL TAB'; }, 3000); }, " +
                                "                  onFailure: function(e, m) { fb.innerText = 'âŒ ' + m.toUpperCase(); fb.style.background = '#f44336'; setTimeout(function(){ fb.innerText = 'FETCH FROM PORTAL TAB'; fb.style.background = '#4CAF50'; }, 4000); } " +
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

        final java.util.concurrent.atomic.AtomicReference<String> lastPostUrlRef = new java.util.concurrent.atomic.AtomicReference<>(null);
        final java.util.concurrent.atomic.AtomicReference<String> lastPostDataRef = new java.util.concurrent.atomic.AtomicReference<>(null);

        cefClient.addRequestHandler(new org.cef.handler.CefRequestHandlerAdapter() {
            @Override
            public boolean onBeforeBrowse(CefBrowser browser, org.cef.browser.CefFrame frame, org.cef.network.CefRequest request, boolean user_gesture, boolean is_redirect) {
                if (frame.isMain()) {
                    if ("POST".equalsIgnoreCase(request.getMethod())) {
                        lastPostUrlRef.set(request.getURL());
                        org.cef.network.CefPostData originalPostData = request.getPostData();
                        String postStr = "";
                        if (originalPostData != null) {
                            java.util.Vector<org.cef.network.CefPostDataElement> elements = new java.util.Vector<>();
                            originalPostData.getElements(elements);
                            for (org.cef.network.CefPostDataElement el : elements) {
                                if (el.getType() == org.cef.network.CefPostDataElement.Type.PDE_TYPE_BYTES) {
                                    int bytesCount = el.getBytesCount();
                                    if (bytesCount > 0) {
                                        byte[] bytes = new byte[bytesCount];
                                        el.getBytes(bytesCount, bytes);
                                        postStr = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                                        break;
                                    }
                                }
                            }
                        }
                        lastPostDataRef.set(postStr);
                    } else {
                        lastPostUrlRef.set(null);
                        lastPostDataRef.set(null);
                    }
                }
                return false;
            }
        });

        refreshBtn.addActionListener(e -> {
            logger.info("Browser reload triggered");
            String lastUrl = lastPostUrlRef.get();
            String postDataStr = lastPostDataRef.get();
            
            if (lastUrl != null && postDataStr != null) {
                int res = JOptionPane.showConfirmDialog(null, 
                    "The page that you're looking for used information that you entered.\nReturning to that page might cause any action you took to be repeated.\nDo you want to continue?", 
                    "Confirm Form Resubmission", JOptionPane.YES_NO_OPTION);
                if (res == JOptionPane.YES_OPTION) {
                    activityLogger.info("Resubmitting POST data natively...");
                    if (!postDataStr.isEmpty()) {
                        String js = "var f = document.createElement('form');" +
                                    "f.method = 'POST';" +
                                    "f.action = '" + lastUrl + "';" +
                                    "var p = '" + postDataStr.replace("'", "\\'") + "';" +
                                    "p.split('&').forEach(function(pair) {" +
                                    "    var eq = pair.indexOf('=');" +
                                    "    var key = eq > -1 ? pair.substring(0, eq) : pair;" +
                                    "    var val = eq > -1 ? pair.substring(eq + 1) : '';" +
                                    "    if (key) {" +
                                    "        var i = document.createElement('input');" +
                                    "        i.type = 'hidden';" +
                                    "        i.name = decodeURIComponent(key.replace(/\\+/g, '%20'));" +
                                    "        i.value = decodeURIComponent(val.replace(/\\+/g, '%20'));" +
                                    "        f.appendChild(i);" +
                                    "    }" +
                                    "});" +
                                    "document.body.appendChild(f);" +
                                    "f.submit();";
                        browser.executeJavaScript(js, lastUrl, 0);
                    } else {
                        browser.reload();
                    }
                }
            } else {
                activityLogger.info("Reloading browser tab...");
                browser.reload();
            }
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

    public File getSessionDir() {
        String base = downloadPathField.getText();
        if (base == null || base.isEmpty()) return null;
        String session = sessionNameField.getText().trim();
        if (session.isEmpty()) return new File(base);
        
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
