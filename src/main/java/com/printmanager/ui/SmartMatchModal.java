package com.printmanager.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.printmanager.matcher.PDFHeaderRenderer;
import com.printmanager.matcher.QPMatcherEngine;
import com.printmanager.matcher.QPMatcherEngine.MatchDecision;
import com.printmanager.matcher.QPMatcherEngine.QPRecord;
import com.printmanager.matcher.QPMatcherEngine.SeatingRequirement;
import javafx.util.StringConverter;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.function.Consumer;

/**
 * Interactive Symmetrical Question Paper Matcher & Visual Review Dialog.
 * Supports dual-perspective matching:
 * 1. PDF Files in Folder -> Preview first page & Match to JSON Course
 * 2. JSON Courses -> Inspect requirements & Match to PDF File
 * Highlights unmatched items in both directions and enables instant visual verification.
 */
public class SmartMatchModal {
    public static Node createWarningIcon(double size, String color) {
        SVGPath svg = new SVGPath();
        svg.setContent("M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z");
        svg.setFill(Color.web(color));
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        Group group = new Group(svg);
        StackPane pane = new StackPane(group);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }

    public static Node createCheckIcon(double size, String color) {
        SVGPath svg = new SVGPath();
        svg.setContent("M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z");
        svg.setFill(Color.web(color));
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        Group group = new Group(svg);
        StackPane pane = new StackPane(group);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }

    public static Node createCrossIcon(double size, String color) {
        SVGPath svg = new SVGPath();
        svg.setContent("M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z");
        svg.setFill(Color.web(color));
        double scale = size / 24.0;
        svg.setScaleX(scale);
        svg.setScaleY(scale);
        Group group = new Group(svg);
        StackPane pane = new StackPane(group);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setMaxSize(size, size);
        pane.setAlignment(Pos.CENTER);
        return pane;
    }

    private static final Logger logger = LoggerFactory.getLogger(SmartMatchModal.class);

    // Persistent state cache across dialog opens
    private static File sLastJsonFile;
    private static JsonNode sLastJsonRoot;
    private static File sLastPdfFolder;
    private static List<QPRecord> sCachedQpRecords = null;
    private static List<MatchDecision> sCachedDecisions = null;

    private final Stage stage;
    private final Stage parentStage;
    private File currentJsonFile;
    private JsonNode currentJsonRoot;
    private File currentPdfFolder;
    private final Consumer<JsonNode> onApplyCallback;

    private final QPMatcherEngine engine = new QPMatcherEngine();
    private List<QPRecord> availableQpRecords = new ArrayList<>();

    // Data Collections
    private final ObservableList<MatchDecision> decisionsList = FXCollections.observableArrayList();
    private FilteredList<MatchDecision> filteredDecisions;

    private final ObservableList<PDFItem> pdfItemsList = FXCollections.observableArrayList();
    private FilteredList<PDFItem> filteredPdfItems;

    // Filter states
    private String courseFilterMode = "ALL"; // ALL, UNMATCHED, MATCHED
    private String pdfFilterMode = "ALL";    // ALL, UNASSIGNED, ASSIGNED
    private String courseSearchQuery = "";
    private String pdfSearchQuery = "";

    // UI Structure
    private TabPane mainTabPane;
    private Tab tabPdfs;
    private Tab tabCourses;

    // Header Controls
    private Label statusSummaryLabel;
    private Button btnMissingCoursesPill;
    private Button btnUnassignedPdfsPill;
    private Label lblJsonInfo;
    private Label lblFolderInfo;
    private ComboBox<String> modeCombo;

    // Course Tab Controls
    private TableView<MatchDecision> reqTable;
    private Button btnFilterCourseAll;
    private Button btnFilterCourseUnmatched;
    private Button btnFilterCourseMatched;

    // PDF Tab Controls
    private TableView<PDFItem> pdfTable;
    private Button btnFilterPdfAll;
    private Button btnFilterPdfUnassigned;
    private Button btnFilterPdfAssigned;

    // Right Preview Panel Controls
    private static double sPreferredPreviewHeight = 240.0;
    private Label lblSelectedTitle;
    private Label lblSelectedSubtitle;
    private Label lblMatchStatusBadge;
    private ScrollPane previewScroll;
    private Slider previewHeightSlider;
    private Label lblHeightDisplay;
    private ImageView previewImageView;
    private Label lblPreviewPlaceholder;
    private GridPane gridDetails;
    private VBox actionCard;
    private TextArea txtReasons;

    // Detail Action components
    private ComboBox<MatchDecision> comboAssignCourse;
    private Button btnAssignPdfToCourse;
    private Button btnClearPdfAssignment;

    private ComboBox<QPRecord> comboManualOverride;
    private Button btnApplyManual;
    private Button btnClearMatch;

    // Current selection tracking
    private enum SelectionContext { NONE, COURSE, PDF }
    private SelectionContext currentContext = SelectionContext.NONE;
    private MatchDecision currentSelectedDecision = null;
    private PDFItem currentSelectedPdfItem = null;

    /**
     * Wrapper model representing a scanned PDF file and its matched status.
     */
    public static class PDFItem {
        public final QPRecord qpRecord;
        public MatchDecision assignedDecision; // null if unassigned

        public PDFItem(QPRecord qpRecord, MatchDecision assignedDecision) {
            this.qpRecord = qpRecord;
            this.assignedDecision = assignedDecision;
        }

        public boolean isAssigned() {
            return assignedDecision != null && !"UNMATCHED".equalsIgnoreCase(assignedDecision.status);
        }

        public String getStatus() {
            return isAssigned() ? "ASSIGNED" : "UNASSIGNED";
        }

        public String getFileName() {
            String name = qpRecord.file != null ? qpRecord.file.getName() : qpRecord.filename;
            if (qpRecord.duplicateCount > 0) {
                name += " (+" + qpRecord.duplicateCount + " dup)";
            }
            return name;
        }

        public String getQpCode() {
            return (qpRecord.qpCode != null && !qpRecord.qpCode.isEmpty()) ? qpRecord.qpCode : "No QP";
        }

        public String getDetectedHeader() {
            String streamTag = (qpRecord.stream != null && !qpRecord.stream.isEmpty()) ? "[" + qpRecord.stream + "] " : "";
            StringBuilder sb = new StringBuilder();
            if (qpRecord.courseCode != null && !qpRecord.courseCode.isEmpty()) {
                sb.append(qpRecord.courseCode).append(" — ");
            }
            if (qpRecord.courseName != null && !qpRecord.courseName.isEmpty()) {
                sb.append(qpRecord.courseName);
            }
            if (sb.length() > 0) {
                if (qpRecord.syllabusYear != null && !qpRecord.syllabusYear.isEmpty()) {
                    sb.append(" [").append(qpRecord.syllabusYear).append("]");
                }
                return streamTag + sb.toString();
            }
            String base = getFileName();
            if (qpRecord.syllabusYear != null && !qpRecord.syllabusYear.isEmpty()) {
                base += " [" + qpRecord.syllabusYear + "]";
            }
            return streamTag + base;
        }
    }

    private List<File> extraPdfFiles = new ArrayList<>();

    public SmartMatchModal(Stage parent, File jsonFile, JsonNode jsonRoot, File initialPdfFolder, Consumer<JsonNode> onApply) {
        this(parent, jsonFile, jsonRoot, initialPdfFolder, null, onApply);
    }

    public SmartMatchModal(Stage parent, File jsonFile, JsonNode jsonRoot, File initialPdfFolder, List<File> extraPdfFiles, Consumer<JsonNode> onApply) {
        this.parentStage = parent;
        this.currentJsonFile = jsonFile;
        this.currentJsonRoot = jsonRoot;
        this.currentPdfFolder = initialPdfFolder;
        this.extraPdfFiles = (extraPdfFiles != null) ? new ArrayList<>(extraPdfFiles) : new ArrayList<>();
        this.onApplyCallback = onApply;

        stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initOwner(parent);
        stage.setTitle("🧩 Smart Question Paper Matcher & Visual Review");
        
        javafx.geometry.Rectangle2D screenBounds = javafx.stage.Screen.getPrimary().getVisualBounds();
        double targetHeight = Math.min(760, screenBounds.getHeight() - 50);
        double targetWidth = Math.min(1280, screenBounds.getWidth() - 40);
        stage.setWidth(targetWidth);
        stage.setHeight(targetHeight);
        stage.setMinWidth(960);
        stage.setMinHeight(520);
        stage.setOnCloseRequest(e -> saveStateToCache());

        buildUI();

        boolean canRestore = currentJsonFile != null && currentJsonFile.equals(sLastJsonFile)
                          && currentPdfFolder != null && currentPdfFolder.equals(sLastPdfFolder)
                          && sCachedDecisions != null && !sCachedDecisions.isEmpty()
                          && sCachedQpRecords != null && !sCachedQpRecords.isEmpty();

        if (canRestore) {
            this.availableQpRecords = new ArrayList<>(sCachedQpRecords);
            this.decisionsList.setAll(sCachedDecisions);
            syncAllState();
            if (lblJsonInfo != null && currentJsonFile != null) {
                lblJsonInfo.setText("📄 " + currentJsonFile.getName());
            }
            if (lblFolderInfo != null && currentPdfFolder != null) {
                lblFolderInfo.setText("📁 " + currentPdfFolder.getAbsolutePath());
            }
            statusSummaryLabel.setText("Restored " + decisionsList.size() + " matches from active session.");
        } else if (currentJsonRoot != null && currentPdfFolder != null) {
            runScanAndMatch();
        }
    }

    private void saveStateToCache() {
        sLastJsonFile = currentJsonFile;
        sLastJsonRoot = currentJsonRoot;
        sLastPdfFolder = currentPdfFolder;
        if (availableQpRecords != null && !availableQpRecords.isEmpty()) {
            sCachedQpRecords = new ArrayList<>(availableQpRecords);
        }
        if (decisionsList != null && !decisionsList.isEmpty()) {
            sCachedDecisions = new ArrayList<>(decisionsList);
        }
    }

    public static void clearSessionCache() {
        sLastJsonFile = null;
        sLastJsonRoot = null;
        sLastPdfFolder = null;
        sCachedQpRecords = null;
        sCachedDecisions = null;
    }

    public static void show(Stage parent, File jsonFile, JsonNode jsonRoot, File initialPdfFolder, Consumer<JsonNode> onApply) {
        show(parent, jsonFile, jsonRoot, initialPdfFolder, null, onApply);
    }

    public static void show(Stage parent, File jsonFile, JsonNode jsonRoot, File initialPdfFolder, List<File> extraPdfFiles, Consumer<JsonNode> onApply) {
        if (jsonFile != null && sLastJsonFile != null && !jsonFile.getAbsolutePath().equalsIgnoreCase(sLastJsonFile.getAbsolutePath())) {
            clearSessionCache();
        }
        if (jsonFile == null && sLastJsonFile != null) {
            jsonFile = sLastJsonFile;
            jsonRoot = sLastJsonRoot;
        }
        if (initialPdfFolder == null && sLastPdfFolder != null) {
            initialPdfFolder = sLastPdfFolder;
        }
        SmartMatchModal modal = new SmartMatchModal(parent, jsonFile, jsonRoot, initialPdfFolder, extraPdfFiles, onApply);
        modal.stage.show();
    }

    private void buildUI() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f8fafc;");

        // Top Header
        root.setTop(createTopHeader());

        // Center SplitPane: Left TabPane (PDFs & Courses) | Right Detail Preview
        SplitPane splitPane = new SplitPane();
        Node leftPane = createLeftDualPane();
        Node rightPane = createRightPreviewPanel();
        SplitPane.setResizableWithParent(leftPane, true);
        SplitPane.setResizableWithParent(rightPane, true);
        splitPane.getItems().addAll(leftPane, rightPane);
        splitPane.setDividerPositions(0.48);
        root.setCenter(splitPane);

        // Bottom Action Bar
        root.setBottom(createBottomBar());

        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.centerOnScreen();
    }

    private VBox createTopHeader() {
        VBox topBox = new VBox(8);
        topBox.setPadding(new Insets(12, 16, 12, 16));
        topBox.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 0 0 1 0;");

        HBox row1 = new HBox(12);
        row1.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Smart Question Paper Matcher & Visual Review");
        title.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Region spacer1 = new Region();
        HBox.setHgrow(spacer1, Priority.ALWAYS);

        statusSummaryLabel = new Label("Ready to scan");
        statusSummaryLabel.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-color: #f1f5f9; -fx-background-radius: 12; -fx-text-fill: #475569;");

        btnMissingCoursesPill = new Button("0 Courses Missing PDF");
        btnMissingCoursesPill.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-color: #fee2e2; -fx-text-fill: #991b1b; -fx-background-radius: 12; -fx-cursor: hand;");
        btnMissingCoursesPill.setOnAction(e -> {
            mainTabPane.getSelectionModel().select(tabCourses);
            setCourseFilter("UNMATCHED");
        });

        btnUnassignedPdfsPill = new Button("0 Unassigned PDFs");
        btnUnassignedPdfsPill.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-color: #fef3c7; -fx-text-fill: #92400e; -fx-background-radius: 12; -fx-cursor: hand;");
        btnUnassignedPdfsPill.setOnAction(e -> {
            mainTabPane.getSelectionModel().select(tabPdfs);
            setPdfFilter("UNASSIGNED");
        });

        Button btnTopLoad = new Button("✓ Load Matches into App");
        btnTopLoad.setStyle("-fx-font-size: 13px; -fx-padding: 6 18; -fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6;");
        btnTopLoad.setTooltip(new Tooltip("Apply matched QP codes to seating and load all matched PDFs into Print Queue and Room Router"));
        btnTopLoad.setOnAction(e -> applyAndLoadIntoOuterUI());

        row1.getChildren().addAll(title, spacer1, statusSummaryLabel, btnMissingCoursesPill, btnUnassignedPdfsPill, btnTopLoad);

        HBox row2 = new HBox(10);
        row2.setAlignment(Pos.CENTER_LEFT);

        lblJsonInfo = new Label(currentJsonFile != null ? "📄 " + currentJsonFile.getName() : "📄 No JSON Loaded");
        lblJsonInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Button btnBrowseJson = new Button("Browse JSON...");
        btnBrowseJson.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        btnBrowseJson.setOnAction(e -> browseJsonFile());

        Separator sep1 = new Separator(javafx.geometry.Orientation.VERTICAL);

        lblFolderInfo = new Label(currentPdfFolder != null ? "📁 " + currentPdfFolder.getAbsolutePath() : "📁 No PDF Folder Selected");
        lblFolderInfo.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        Button btnBrowseFolder = new Button("Change PDF Folder...");
        btnBrowseFolder.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        btnBrowseFolder.setOnAction(e -> browsePdfFolder());

        Separator sep2 = new Separator(javafx.geometry.Orientation.VERTICAL);

        Label lblMode = new Label("Mode:");
        lblMode.setStyle("-fx-font-size: 12px; -fx-font-weight: bold;");

        modeCombo = new ComboBox<>();
        modeCombo.getItems().addAll("Strict 3-Key Verification", "Department Allocation");
        modeCombo.setValue("Strict 3-Key Verification");
        modeCombo.setStyle("-fx-font-size: 11px;");
        modeCombo.setOnAction(e -> runScanAndMatch());

        Button btnRescan = new Button("🔄 Re-Match");
        btnRescan.setStyle("-fx-font-size: 11px; -fx-padding: 4 10; -fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold;");
        btnRescan.setOnAction(e -> runScanAndMatch());

        row2.getChildren().addAll(lblJsonInfo, btnBrowseJson, sep1, lblFolderInfo, btnBrowseFolder, sep2, lblMode, modeCombo, btnRescan);

        topBox.getChildren().addAll(row1, row2);
        return topBox;
    }

    private VBox createLeftDualPane() {
        VBox container = new VBox();
        container.setStyle("-fx-background-color: white;");

        mainTabPane = new TabPane();
        mainTabPane.setStyle("-fx-background-color: white;");
        VBox.setVgrow(mainTabPane, Priority.ALWAYS);

        tabPdfs = new Tab("📁 PDF Files in Folder");
        tabPdfs.setClosable(false);
        tabPdfs.setContent(createPdfTabContent());

        tabCourses = new Tab("📋 JSON Courses Required");
        tabCourses.setClosable(false);
        tabCourses.setContent(createCourseTabContent());

        mainTabPane.getTabs().addAll(tabPdfs, tabCourses);

        // When switching tabs, update detail view to the current selection of that tab
        mainTabPane.getSelectionModel().selectedItemProperty().addListener((obs, old, newTab) -> {
            if (newTab == tabPdfs) {
                PDFItem sel = pdfTable.getSelectionModel().getSelectedItem();
                updateDetailViewForPdf(sel);
            } else if (newTab == tabCourses) {
                MatchDecision sel = reqTable.getSelectionModel().getSelectedItem();
                updateDetailViewForCourse(sel);
            }
        });

        container.getChildren().add(mainTabPane);
        return container;
    }

    // -------------------------------------------------------------------------
    // TAB 1: PDF Files in Folder
    // -------------------------------------------------------------------------

    private VBox createPdfTabContent() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        // Filter Pills Row
        HBox filterRow = new HBox(6);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        btnFilterPdfAll = new Button("All PDFs (0)");
        btnFilterPdfUnassigned = new Button("Unassigned Files (0)");
        btnFilterPdfUnassigned.setGraphic(createWarningIcon(13, "#b45309"));
        btnFilterPdfAssigned = new Button("Assigned Files (0)");
        btnFilterPdfAssigned.setGraphic(createCheckIcon(13, "#16a34a"));

        setupFilterBtnStyle(btnFilterPdfAll, true);
        setupFilterBtnStyle(btnFilterPdfUnassigned, false);
        setupFilterBtnStyle(btnFilterPdfAssigned, false);

        btnFilterPdfAll.setOnAction(e -> setPdfFilter("ALL"));
        btnFilterPdfUnassigned.setOnAction(e -> setPdfFilter("UNASSIGNED"));
        btnFilterPdfAssigned.setOnAction(e -> setPdfFilter("ASSIGNED"));

        filterRow.getChildren().addAll(btnFilterPdfAll, btnFilterPdfUnassigned, btnFilterPdfAssigned);

        // Search Field
        TextField searchField = new TextField();
        searchField.setPromptText("Filter PDF filename, QP code, title...");
        searchField.setStyle("-fx-font-size: 12px; -fx-padding: 5 8; -fx-background-radius: 6;");
        searchField.textProperty().addListener((obs, old, val) -> {
            pdfSearchQuery = (val == null) ? "" : val.trim().toLowerCase();
            applyPdfFilters();
        });

        // PDF Table
        pdfTable = new TableView<>();
        pdfTable.setStyle("-fx-font-size: 12px; -fx-border-color: #e2e8f0;");
        VBox.setVgrow(pdfTable, Priority.ALWAYS);

        TableColumn<PDFItem, String> colQp = new TableColumn<>("QP Code");
        colQp.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getQpCode()));
        colQp.setPrefWidth(95);

        TableColumn<PDFItem, String> colFile = new TableColumn<>("PDF File");
        colFile.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getFileName()));
        colFile.setPrefWidth(210);

        TableColumn<PDFItem, String> colDetected = new TableColumn<>("Detected Title / Code");
        colDetected.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getDetectedHeader()));
        colDetected.setPrefWidth(240);

        TableColumn<PDFItem, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getStatus()));
        colStatus.setPrefWidth(145);
        colStatus.setCellFactory(col -> new TableCell<PDFItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    PDFItem pItem = getTableView().getItems().get(getIndex());
                    Label pill = new Label();
                    pill.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3 8; -fx-background-radius: 10;");
                    if (pItem.isAssigned()) {
                        String target = pItem.assignedDecision.requirement.extractedCourseCode;
                        if (target == null || target.isEmpty()) target = pItem.assignedDecision.requirement.extractedTitle;
                        String streamTag = (pItem.assignedDecision.requirement.stream != null) ? "[" + pItem.assignedDecision.requirement.stream + "] " : "";
                        pill.setText(streamTag + (target != null ? target : "Matched"));
                        pill.setGraphic(createCheckIcon(11, "#166534"));
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #dcfce7; -fx-text-fill: #166534;");
                    } else {
                        String streamTag = (pItem.qpRecord.stream != null) ? "[" + pItem.qpRecord.stream + "] " : "";
                        pill.setText(streamTag + "UNASSIGNED");
                        pill.setGraphic(createWarningIcon(11, "#92400e"));
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #fef3c7; -fx-text-fill: #92400e;");
                    }
                    setGraphic(pill);
                    setText(null);
                }
            }
        });

        pdfTable.getColumns().addAll(colQp, colFile, colDetected, colStatus);

        filteredPdfItems = new FilteredList<>(pdfItemsList, p -> true);
        pdfTable.setItems(filteredPdfItems);
        pdfTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> updateDetailViewForPdf(sel));

        box.getChildren().addAll(filterRow, searchField, pdfTable);
        return box;
    }

    private void setPdfFilter(String mode) {
        this.pdfFilterMode = mode;
        setupFilterBtnStyle(btnFilterPdfAll, "ALL".equalsIgnoreCase(mode));
        setupFilterBtnStyle(btnFilterPdfUnassigned, "UNASSIGNED".equalsIgnoreCase(mode));
        setupFilterBtnStyle(btnFilterPdfAssigned, "ASSIGNED".equalsIgnoreCase(mode));
        applyPdfFilters();
    }

    private void applyPdfFilters() {
        filteredPdfItems.setPredicate(item -> {
            if ("UNASSIGNED".equalsIgnoreCase(pdfFilterMode) && item.isAssigned()) return false;
            if ("ASSIGNED".equalsIgnoreCase(pdfFilterMode) && !item.isAssigned()) return false;

            if (pdfSearchQuery.isEmpty()) return true;
            String fn = item.getFileName().toLowerCase();
            String qp = item.getQpCode().toLowerCase();
            String header = item.getDetectedHeader().toLowerCase();
            return fn.contains(pdfSearchQuery) || qp.contains(pdfSearchQuery) || header.contains(pdfSearchQuery);
        });
    }

    // -------------------------------------------------------------------------
    // TAB 2: JSON Courses Required
    // -------------------------------------------------------------------------

    private VBox createCourseTabContent() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10));

        // Filter Pills Row
        HBox filterRow = new HBox(6);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        btnFilterCourseAll = new Button("All Courses (0)");
        btnFilterCourseUnmatched = new Button("Missing PDF (0)");
        btnFilterCourseUnmatched.setGraphic(createCrossIcon(13, "#dc2626"));
        btnFilterCourseMatched = new Button("Matched (0)");
        btnFilterCourseMatched.setGraphic(createCheckIcon(13, "#16a34a"));

        setupFilterBtnStyle(btnFilterCourseAll, true);
        setupFilterBtnStyle(btnFilterCourseUnmatched, false);
        setupFilterBtnStyle(btnFilterCourseMatched, false);

        btnFilterCourseAll.setOnAction(e -> setCourseFilter("ALL"));
        btnFilterCourseUnmatched.setOnAction(e -> setCourseFilter("UNMATCHED"));
        btnFilterCourseMatched.setOnAction(e -> setCourseFilter("MATCHED"));

        filterRow.getChildren().addAll(btnFilterCourseAll, btnFilterCourseUnmatched, btnFilterCourseMatched);

        // Search Field
        TextField searchField = new TextField();
        searchField.setPromptText("Filter courses, codes, or rooms...");
        searchField.setStyle("-fx-font-size: 12px; -fx-padding: 5 8; -fx-background-radius: 6;");
        searchField.textProperty().addListener((obs, old, val) -> {
            courseSearchQuery = (val == null) ? "" : val.trim().toLowerCase();
            applyCourseFilters();
        });

        // Course Table
        reqTable = new TableView<>();
        reqTable.setStyle("-fx-font-size: 12px; -fx-border-color: #e2e8f0;");
        VBox.setVgrow(reqTable, Priority.ALWAYS);

        TableColumn<MatchDecision, String> colCourse = new TableColumn<>("Required Course & Code");
        colCourse.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().requirement.getDisplayName()));
        colCourse.setPrefWidth(230);

        TableColumn<MatchDecision, String> colStream = new TableColumn<>("Stream");
        colStream.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().requirement.stream != null ? data.getValue().requirement.stream : "Regular"));
        colStream.setPrefWidth(90);
        colStream.setCellFactory(col -> new TableCell<MatchDecision, String>() {
            @Override
            protected void updateItem(String stream, boolean empty) {
                super.updateItem(stream, empty);
                if (empty || stream == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    Label pill = new Label(stream);
                    pill.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 2 8; -fx-background-radius: 8;");
                    if ("Regular".equalsIgnoreCase(stream)) {
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #f1f5f9; -fx-text-fill: #475569;");
                    } else {
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #ede9fe; -fx-text-fill: #6d28d9;");
                    }
                    setGraphic(pill);
                    setText(null);
                }
            }
        });

        TableColumn<MatchDecision, String> colCount = new TableColumn<>("QPs (Rooms)");
        colCount.setCellValueFactory(data -> {
            SeatingRequirement r = data.getValue().requirement;
            return new SimpleStringProperty(r.totalCandidates + " QPs (" + r.roomSerials.size() + " Rms)");
        });
        colCount.setPrefWidth(100);

        TableColumn<MatchDecision, String> colStatus = new TableColumn<>("Status");
        colStatus.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().status));
        colStatus.setPrefWidth(130);
        colStatus.setCellFactory(col -> new TableCell<MatchDecision, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    MatchDecision md = getTableView().getItems().get(getIndex());
                    Label pill = new Label();
                    pill.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 3 8; -fx-background-radius: 10;");
                    String streamTag = (md.requirement != null && md.requirement.stream != null) ? "[" + md.requirement.stream + "] " : "";
                    if ("EXACT".equalsIgnoreCase(item)) {
                        pill.setText(streamTag + "✓ " + md.getMatchedQpCode());
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #dcfce7; -fx-text-fill: #166534;");
                    } else if ("MANUAL".equalsIgnoreCase(item)) {
                        pill.setText(streamTag + "✎ " + md.getMatchedQpCode());
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #fef3c7; -fx-text-fill: #92400e;");
                    } else {
                        pill.setText(streamTag + "❌ NO PDF");
                        pill.setStyle(pill.getStyle() + "-fx-background-color: #fee2e2; -fx-text-fill: #991b1b;");
                    }
                    setGraphic(pill);
                    setText(null);
                }
            }
        });

        reqTable.getColumns().addAll(colCourse, colStream, colCount, colStatus);

        filteredDecisions = new FilteredList<>(decisionsList, p -> true);
        reqTable.setItems(filteredDecisions);
        reqTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> updateDetailViewForCourse(sel));

        box.getChildren().addAll(filterRow, searchField, reqTable);
        return box;
    }

    private void setCourseFilter(String mode) {
        this.courseFilterMode = mode;
        setupFilterBtnStyle(btnFilterCourseAll, "ALL".equalsIgnoreCase(mode));
        setupFilterBtnStyle(btnFilterCourseUnmatched, "UNMATCHED".equalsIgnoreCase(mode));
        setupFilterBtnStyle(btnFilterCourseMatched, "MATCHED".equalsIgnoreCase(mode));
        applyCourseFilters();
    }

    private void applyCourseFilters() {
        filteredDecisions.setPredicate(d -> {
            boolean isUnmatched = "UNMATCHED".equalsIgnoreCase(d.status);
            if ("UNMATCHED".equalsIgnoreCase(courseFilterMode) && !isUnmatched) return false;
            if ("MATCHED".equalsIgnoreCase(courseFilterMode) && isUnmatched) return false;

            if (courseSearchQuery.isEmpty()) return true;
            SeatingRequirement r = d.requirement;
            return r.originalCourseName.toLowerCase().contains(courseSearchQuery) ||
                   (r.extractedCourseCode != null && r.extractedCourseCode.toLowerCase().contains(courseSearchQuery)) ||
                   (d.getMatchedQpCode().toLowerCase().contains(courseSearchQuery)) ||
                   (d.getMatchedFileName().toLowerCase().contains(courseSearchQuery));
        });
    }

    private void setupFilterBtnStyle(Button btn, boolean active) {
        if (active) {
            btn.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 3 10; -fx-background-color: #0284c7; -fx-text-fill: white; -fx-background-radius: 12;");
        } else {
            btn.setStyle("-fx-font-size: 11px; -fx-padding: 3 10; -fx-background-color: #f1f5f9; -fx-text-fill: #475569; -fx-background-radius: 12; -fx-border-color: #cbd5e1; -fx-border-radius: 12;");
        }
    }

    // -------------------------------------------------------------------------
    // RIGHT PANEL: Visual Header Preview & Action Card
    // -------------------------------------------------------------------------

    private Node createRightPreviewPanel() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: #f8fafc;");

        // Header & Status Pill
        VBox titleContainer = new VBox(2);
        lblSelectedTitle = new Label("Select a PDF file or JSON course to inspect");
        lblSelectedTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #0f172a;");
        lblSelectedTitle.setWrapText(true);

        lblSelectedSubtitle = new Label("Click on items in either tab to preview and pair.");
        lblSelectedSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #64748b;");

        HBox headerRow = new HBox(10);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(titleContainer, Priority.ALWAYS);

        lblMatchStatusBadge = new Label("");
        lblMatchStatusBadge.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");

        titleContainer.getChildren().addAll(lblSelectedTitle, lblSelectedSubtitle);
        headerRow.getChildren().addAll(titleContainer, lblMatchStatusBadge);

        // Visual Preview Card (Full-Width Focused Header Preview with User-Adjustable Height)
        VBox previewCard = new VBox(6);
        previewCard.setPadding(new Insets(10));
        previewCard.setStyle("-fx-background-color: white; -fx-border-color: #cbd5e1; -fx-border-radius: 8; -fx-background-radius: 8;");

        // Header bar with Title + Preset buttons + Slider + Height label
        HBox previewHeader = new HBox(8);
        previewHeader.setAlignment(Pos.CENTER_LEFT);

        Label previewTitle = new Label("Question Paper Page Preview:");
        previewTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        Region spacerPreview = new Region();
        HBox.setHgrow(spacerPreview, Priority.ALWAYS);

        Label lblHeightTag = new Label("Height:");
        lblHeightTag.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b; -fx-font-weight: bold;");

        Button btnHCompact = new Button("Compact");
        btnHCompact.setStyle("-fx-font-size: 10px; -fx-padding: 2 6; -fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 3; -fx-cursor: hand;");
        btnHCompact.setTooltip(new Tooltip("Set preview height to 150px (Compact)"));
        btnHCompact.setOnAction(e -> setPreviewHeight(150));

        Button btnHMedium = new Button("Medium");
        btnHMedium.setStyle("-fx-font-size: 10px; -fx-padding: 2 6; -fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 3; -fx-cursor: hand;");
        btnHMedium.setTooltip(new Tooltip("Set preview height to 250px (Medium)"));
        btnHMedium.setOnAction(e -> setPreviewHeight(250));

        Button btnHLarge = new Button("Large");
        btnHLarge.setStyle("-fx-font-size: 10px; -fx-padding: 2 6; -fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 3; -fx-cursor: hand;");
        btnHLarge.setTooltip(new Tooltip("Set preview height to 420px (Large)"));
        btnHLarge.setOnAction(e -> setPreviewHeight(420));

        Button btnHFull = new Button("Full");
        btnHFull.setStyle("-fx-font-size: 10px; -fx-padding: 2 6; -fx-background-color: #f1f5f9; -fx-border-color: #cbd5e1; -fx-border-radius: 3; -fx-cursor: hand;");
        btnHFull.setTooltip(new Tooltip("Set preview height to 620px (Full Page View)"));
        btnHFull.setOnAction(e -> setPreviewHeight(620));

        previewHeightSlider = new Slider(120, 800, sPreferredPreviewHeight);
        previewHeightSlider.setPrefWidth(90);
        previewHeightSlider.setTooltip(new Tooltip("Drag to smoothly adjust preview height (120px - 800px)"));
        previewHeightSlider.valueProperty().addListener((obs, oldV, newV) -> setPreviewHeight(newV.doubleValue()));

        lblHeightDisplay = new Label((int)sPreferredPreviewHeight + "px");
        lblHeightDisplay.setStyle("-fx-font-size: 11px; -fx-text-fill: #475569; -fx-font-weight: bold; -fx-min-width: 38px;");

        previewHeader.getChildren().addAll(previewTitle, spacerPreview, lblHeightTag, btnHCompact, btnHMedium, btnHLarge, btnHFull, previewHeightSlider, lblHeightDisplay);

        previewScroll = new ScrollPane();
        previewScroll.setFitToWidth(true);
        previewScroll.setPrefHeight(sPreferredPreviewHeight);
        previewScroll.setMinHeight(120);
        previewScroll.setMaxHeight(Double.MAX_VALUE);
        previewScroll.setStyle("-fx-background-color: #ffffff; -fx-border-color: #cbd5e1; -fx-border-radius: 4; -fx-padding: 0;");

        StackPane previewStack = new StackPane();
        previewStack.setStyle("-fx-background-color: #ffffff;");

        previewImageView = new ImageView();
        previewImageView.setPreserveRatio(true);
        previewImageView.setSmooth(true);
        // Bind width to ScrollPane width so image fills 100% full width
        previewImageView.fitWidthProperty().bind(previewScroll.widthProperty().subtract(16));

        lblPreviewPlaceholder = new Label("No preview available");
        lblPreviewPlaceholder.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px; -fx-padding: 30;");

        previewStack.getChildren().addAll(lblPreviewPlaceholder, previewImageView);
        previewScroll.setContent(previewStack);

        // Interactive Bottom Draggable Resize Handle
        HBox dragHandle = new HBox();
        dragHandle.setAlignment(Pos.CENTER);
        dragHandle.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0; -fx-padding: 3 0; -fx-cursor: v-resize;");
        Label dragLabel = new Label("⋯ ⇕ Drag or use slider above to adjust preview height ⋯");
        dragLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: #94a3b8; -fx-font-weight: bold;");
        dragHandle.getChildren().add(dragLabel);

        final double[] dragStartY = new double[1];
        final double[] initialHeight = new double[1];

        dragHandle.setOnMousePressed(e -> {
            dragStartY[0] = e.getScreenY();
            initialHeight[0] = previewScroll.getPrefHeight();
            dragHandle.setStyle("-fx-background-color: #e0f2fe; -fx-border-color: #38bdf8; -fx-border-width: 1 0 0 0; -fx-padding: 3 0; -fx-cursor: v-resize;");
        });

        dragHandle.setOnMouseDragged(e -> {
            double deltaY = e.getScreenY() - dragStartY[0];
            setPreviewHeight(initialHeight[0] + deltaY);
        });

        dragHandle.setOnMouseReleased(e -> {
            dragHandle.setStyle("-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0; -fx-padding: 3 0; -fx-cursor: v-resize;");
        });

        previewCard.getChildren().addAll(previewHeader, previewScroll, dragHandle);

        // Action Card Container (Placed RIGHT below Preview for maximum visibility & convenience!)
        actionCard = new VBox(8);
        actionCard.setPadding(new Insets(10, 12, 10, 12));
        actionCard.setStyle("-fx-background-color: white; -fx-border-color: #0284c7; -fx-border-width: 1.5; -fx-border-radius: 8; -fx-background-radius: 8;");

        // Details / Comparison Grid
        gridDetails = new GridPane();
        gridDetails.setHgap(16);
        gridDetails.setVgap(4);
        gridDetails.setPadding(new Insets(8, 10, 8, 10));
        gridDetails.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-radius: 8; -fx-background-radius: 8;");

        // Reasons & Diagnostics
        txtReasons = new TextArea();
        txtReasons.setEditable(false);
        txtReasons.setPrefRowCount(2);
        txtReasons.setMaxHeight(65);
        txtReasons.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; -fx-text-fill: #475569;");

        box.getChildren().addAll(headerRow, previewCard, actionCard, gridDetails, txtReasons);

        // Wrap entire right panel in a ScrollPane so all elements scroll seamlessly when window is resized!
        ScrollPane rightScroll = new ScrollPane(box);
        rightScroll.setFitToWidth(true);
        rightScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rightScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        rightScroll.setStyle("-fx-background-color: transparent; -fx-background: #f8fafc;");
        return rightScroll;
    }

    private void setPreviewHeight(double newHeight) {
        double clamped = Math.max(120.0, Math.min(850.0, newHeight));
        sPreferredPreviewHeight = clamped;
        if (previewScroll != null) {
            previewScroll.setPrefHeight(clamped);
        }
        if (previewHeightSlider != null && Math.abs(previewHeightSlider.getValue() - clamped) > 1.0) {
            previewHeightSlider.setValue(clamped);
        }
        if (lblHeightDisplay != null) {
            lblHeightDisplay.setText((int)clamped + "px");
        }
    }

    private HBox createBottomBar() {
        HBox bar = new HBox(12);
        bar.setPadding(new Insets(12, 16, 12, 16));
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setStyle("-fx-background-color: white; -fx-border-color: #e2e8f0; -fx-border-width: 1 0 0 0;");

        Button btnExport = new Button("💾 Export Populated JSON...");
        btnExport.setStyle("-fx-font-size: 13px; -fx-padding: 8 16; -fx-background-color: #64748b; -fx-text-fill: white; -fx-font-weight: bold;");
        btnExport.setOnAction(e -> exportPopulatedJson());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btnCancel = new Button("Cancel");
        btnCancel.setStyle("-fx-font-size: 13px; -fx-padding: 8 18;");
        btnCancel.setOnAction(e -> stage.close());

        Button btnApply = new Button("✓ Load Matches into App");
        btnApply.setStyle("-fx-font-size: 13px; -fx-padding: 8 22; -fx-background-color: #16a34a; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6;");
        btnApply.setMinWidth(Region.USE_PREF_SIZE);
        btnApply.setOnAction(e -> applyAndLoadIntoOuterUI());

        bar.getChildren().addAll(btnExport, spacer, btnCancel, btnApply);
        return bar;
    }

    // =========================================================================
    // UPDATE DETAIL VIEWS
    // =========================================================================

    /**
     * Mode 1: User selected a PDF file from Tab 1.
     * Shows this PDF's header preview and allows matching it to a JSON course!
     */
    private void updateDetailViewForPdf(PDFItem pdfItem) {
        if (pdfItem == null) return;
        this.currentContext = SelectionContext.PDF;
        this.currentSelectedPdfItem = pdfItem;
        this.currentSelectedDecision = pdfItem.assignedDecision;

        QPRecord qp = pdfItem.qpRecord;
        lblSelectedTitle.setText("PDF File: " + pdfItem.getFileName());
        lblSelectedSubtitle.setText("Extracted: " + pdfItem.getDetectedHeader());

        // Status Badge
        if (pdfItem.isAssigned()) {
            lblMatchStatusBadge.setText("ASSIGNED TO: " + pdfItem.assignedDecision.requirement.extractedCourseCode);
            lblMatchStatusBadge.setGraphic(createCheckIcon(13, "#166534"));
            lblMatchStatusBadge.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        } else {
            lblMatchStatusBadge.setText("UNASSIGNED FILE (NO JSON MATCH)");
            lblMatchStatusBadge.setGraphic(createWarningIcon(13, "#92400e"));
            lblMatchStatusBadge.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #92400e; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        }

        // Render PDF First Page Header Preview
        renderPdfPreview(qp.file);

        // Populate Grid Details
        gridDetails.getChildren().clear();
        addGridRow(gridDetails, 0, "Property", "Detected PDF Header Value", "Assigned JSON Requirement", true);
        addGridRow(gridDetails, 1, "QP Code", qp.qpCode != null ? qp.qpCode : "None Detected", 
                   pdfItem.isAssigned() ? pdfItem.assignedDecision.getMatchedQpCode() : "—", false);
        addGridRow(gridDetails, 2, "Course Code", qp.courseCode != null ? qp.courseCode : "None Detected", 
                   pdfItem.isAssigned() ? pdfItem.assignedDecision.requirement.extractedCourseCode : "—", false);
        addGridRow(gridDetails, 3, "Course Title", qp.courseName != null ? qp.courseName : "None Detected", 
                   pdfItem.isAssigned() ? pdfItem.assignedDecision.requirement.extractedTitle : "—", false);
        addGridRow(gridDetails, 4, "Syllabus Year", qp.syllabusYear != null ? qp.syllabusYear : "Neutral", 
                   pdfItem.isAssigned() ? pdfItem.assignedDecision.requirement.syllabusYear : "—", false);
        addGridRow(gridDetails, 5, "Stream", qp.stream != null ? qp.stream : "Regular", 
                   pdfItem.isAssigned() ? pdfItem.assignedDecision.requirement.stream : "—", false);
        if (qp.duplicateCount > 0) {
            addGridRow(gridDetails, 6, "Duplicates", qp.duplicateCount + " identical files collapsed", 
                       String.join(", ", qp.duplicateFilenames), false);
        }

        // Action Card: Pair THIS PDF with a JSON Course
        actionCard.getChildren().clear();

        Label lblActionTitle = new Label("Match this PDF File with a JSON Course:");
        lblActionTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        // Row 1: Search Field + Clear Button + Quick Filter Button
        HBox searchCourseBar = new HBox(8);
        searchCourseBar.setAlignment(Pos.CENTER_LEFT);

        TextField txtCourseSearch = new TextField();
        txtCourseSearch.setPromptText("🔍 Type to search course name or code (e.g. HIS, BCM, History)...");
        txtCourseSearch.setStyle("-fx-font-size: 12px; -fx-padding: 6 10; -fx-background-radius: 6;");
        HBox.setHgrow(txtCourseSearch, Priority.ALWAYS);

        Button btnClearSearch = new Button("✕");
        btnClearSearch.setStyle("-fx-font-size: 11px; -fx-padding: 5 8;");
        btnClearSearch.setOnAction(e -> txtCourseSearch.clear());

        searchCourseBar.getChildren().addAll(txtCourseSearch, btnClearSearch);

        // Pre-fill / quick button if detected course code or title exists
        String detectedCode = (qp.courseCode != null && !qp.courseCode.isEmpty()) ? qp.courseCode : "";
        if (detectedCode.isEmpty() && qp.courseName != null && !qp.courseName.isEmpty()) {
            detectedCode = qp.courseName.split("[—\\-\\s]")[0];
        }
        if (!detectedCode.isEmpty()) {
            final String finalCode = detectedCode;
            Button btnQuickFilter = new Button("⚡ Quick Filter: \"" + finalCode + "\"");
            btnQuickFilter.setStyle("-fx-font-size: 11px; -fx-background-color: #e0f2fe; -fx-text-fill: #0369a1; -fx-background-radius: 10; -fx-cursor: hand; -fx-font-weight: bold;");
            btnQuickFilter.setOnAction(e -> txtCourseSearch.setText(finalCode));
            searchCourseBar.getChildren().add(btnQuickFilter);
        }

        // Prepare sorted decisions: Pending courses first, then Assigned courses, then alphabetical
        List<MatchDecision> sortedDecisions = new ArrayList<>(decisionsList);
        sortedDecisions.sort((d1, d2) -> {
            boolean p1 = (d1.matchedRecord == null || "UNMATCHED".equalsIgnoreCase(d1.status));
            boolean p2 = (d2.matchedRecord == null || "UNMATCHED".equalsIgnoreCase(d2.status));
            if (p1 && !p2) return -1;
            if (!p1 && p2) return 1;
            return d1.requirement.getDisplayName().compareToIgnoreCase(d2.requirement.getDisplayName());
        });

        ObservableList<MatchDecision> masterCourseList = FXCollections.observableArrayList(sortedDecisions);
        FilteredList<MatchDecision> filteredCourses = new FilteredList<>(masterCourseList, p -> true);

        comboAssignCourse = new ComboBox<>(filteredCourses);
        comboAssignCourse.setPromptText("Select a course from seating JSON...");
        comboAssignCourse.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(comboAssignCourse, Priority.ALWAYS);
        comboAssignCourse.setStyle("-fx-font-size: 12px;");

        // StringConverter ensures proper conversion & display
        comboAssignCourse.setConverter(new StringConverter<MatchDecision>() {
            @Override
            public String toString(MatchDecision d) {
                if (d == null || d.requirement == null) return "";
                boolean isPending = (d.matchedRecord == null || "UNMATCHED".equalsIgnoreCase(d.status));
                String prefix = isPending ? "[PENDING] " : "[ASSIGNED to " + (d.matchedRecord != null ? d.matchedRecord.filename : "PDF") + "] ";
                return prefix + d.requirement.getDisplayName();
            }

            @Override
            public MatchDecision fromString(String string) {
                return null;
            }
        });

        comboAssignCourse.setCellFactory(lv -> new ListCell<MatchDecision>() {
            @Override
            protected void updateItem(MatchDecision item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.requirement == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    boolean isPending = (item.matchedRecord == null || "UNMATCHED".equalsIgnoreCase(item.status));
                    String prefix = isPending ? "[PENDING] " : "[ASSIGNED to " + (item.matchedRecord != null ? item.matchedRecord.filename : "PDF") + "] ";
                    setText(prefix + item.requirement.getDisplayName() + " (" + item.requirement.totalCandidates + " QPs, " + item.requirement.roomSerials.size() + " Rms)");
                }
            }
        });

        // Search listener: filter in real-time
        txtCourseSearch.textProperty().addListener((obs, oldVal, newVal) -> {
            String q = (newVal == null) ? "" : newVal.trim().toLowerCase();
            filteredCourses.setPredicate(d -> {
                if (q.isEmpty()) return true;
                String name = d.requirement.getDisplayName().toLowerCase();
                String orig = d.requirement.originalCourseName.toLowerCase();
                String code = (d.requirement.extractedCourseCode != null) ? d.requirement.extractedCourseCode.toLowerCase() : "";
                return name.contains(q) || orig.contains(q) || code.contains(q);
            });
            if (filteredCourses.size() == 1) {
                comboAssignCourse.setValue(filteredCourses.get(0));
            }
        });

        if (pdfItem.isAssigned()) {
            comboAssignCourse.setValue(pdfItem.assignedDecision);
        }

        comboAssignCourse.setMaxWidth(Double.MAX_VALUE);

        btnAssignPdfToCourse = new Button("✓ Match PDF to Selected Course");
        btnAssignPdfToCourse.setStyle("-fx-font-size: 13px; -fx-padding: 8 18; -fx-background-color: #0284c7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6;");
        btnAssignPdfToCourse.setMinWidth(Region.USE_PREF_SIZE);
        btnAssignPdfToCourse.setOnAction(e -> assignPdfToSelectedCourse(pdfItem, comboAssignCourse.getValue()));

        btnClearPdfAssignment = new Button("✕ Clear Match");
        btnClearPdfAssignment.setStyle("-fx-font-size: 12px; -fx-padding: 8 14; -fx-background-color: #f1f5f9; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");
        btnClearPdfAssignment.setMinWidth(Region.USE_PREF_SIZE);
        btnClearPdfAssignment.setDisable(!pdfItem.isAssigned());
        btnClearPdfAssignment.setOnAction(e -> clearPdfAssignment(pdfItem));

        HBox btnRow = new HBox(10, btnAssignPdfToCourse, btnClearPdfAssignment);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        actionCard.getChildren().addAll(lblActionTitle, searchCourseBar, comboAssignCourse, btnRow);

        // Diagnostics
        if (pdfItem.isAssigned()) {
            txtReasons.setText(String.join("\n", pdfItem.assignedDecision.reasons));
        } else {
            txtReasons.setText("This file is in the folder but does not match any course in the seating JSON automatically.\nReview the first page preview above and select the appropriate course from the dropdown.");
        }
    }

    /**
     * Mode 2: User selected a JSON Course from Tab 2.
     * Shows course requirements and matched PDF preview (or allows picking an unassigned PDF).
     */
    private void updateDetailViewForCourse(MatchDecision decision) {
        if (decision == null) return;
        this.currentContext = SelectionContext.COURSE;
        this.currentSelectedDecision = decision;

        SeatingRequirement req = decision.requirement;
        QPRecord qp = decision.matchedRecord;

        lblSelectedTitle.setText("Required Course: " + req.getDisplayName());
        lblSelectedSubtitle.setText(req.totalCandidates + " Candidates across " + req.roomSerials.size() + " Rooms (" + req.stream + ")");

        // Status Badge
        if ("EXACT".equalsIgnoreCase(decision.status)) {
            lblMatchStatusBadge.setText("✓ EXACT 3-KEY MATCH");
            lblMatchStatusBadge.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        } else if ("SHARED_STREAM".equalsIgnoreCase(decision.status)) {
            lblMatchStatusBadge.setText("🔗 SHARED QUESTION PAPER (" + req.stream + ")");
            lblMatchStatusBadge.setStyle("-fx-background-color: #e0e7ff; -fx-text-fill: #3730a3; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        } else if ("MANUAL".equalsIgnoreCase(decision.status)) {
            lblMatchStatusBadge.setText("✎ MANUAL ASSIGNMENT");
            lblMatchStatusBadge.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #92400e; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        } else {
            lblMatchStatusBadge.setText("❌ NO PDF MATCHED");
            lblMatchStatusBadge.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #991b1b; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        }

        // Render preview if matched
        if (qp != null && qp.file != null) {
            renderPdfPreview(qp.file);
        } else {
            previewImageView.setImage(null);
            lblPreviewPlaceholder.setText("❌ No question paper PDF matched for this course yet.");
            lblPreviewPlaceholder.setVisible(true);
        }

        // Populate Grid Details
        gridDetails.getChildren().clear();
        addGridRow(gridDetails, 0, "Parameter", "JSON Seating Requirement", "Matched PDF Header", true);
        addGridRow(gridDetails, 1, "Course Code", req.extractedCourseCode != null ? req.extractedCourseCode : "N/A", 
                   qp != null ? (qp.courseCode != null ? qp.courseCode : "N/A") : "—", false);
        addGridRow(gridDetails, 2, "Course Title", req.extractedTitle != null ? req.extractedTitle : "N/A", 
                   qp != null ? (qp.courseName != null ? qp.courseName : "N/A") : "—", false);
        addGridRow(gridDetails, 3, "Syllabus Year", req.syllabusYear != null ? req.syllabusYear : "Neutral", 
                   qp != null ? (qp.syllabusYear != null ? qp.syllabusYear : "Neutral") : "—", false);
        addGridRow(gridDetails, 4, "Stream", req.stream != null ? req.stream : "Regular", 
                   qp != null ? (qp.stream != null ? qp.stream : "Regular") : "—", false);
        addGridRow(gridDetails, 5, "QP Code", "—", qp != null ? qp.qpCode : "—", false);
        if (qp != null && qp.duplicateCount > 0) {
            addGridRow(gridDetails, 6, "Duplicates", qp.duplicateCount + " identical files collapsed", 
                       String.join(", ", qp.duplicateFilenames), false);
        }

        // Action Card: Pair THIS Course with a PDF File
        actionCard.getChildren().clear();

        Label lblActionTitle = new Label("Choose PDF File to Pair with this Course:");
        lblActionTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #1e293b;");

        // Row 1: Search Field for PDF
        HBox searchPdfBar = new HBox(8);
        searchPdfBar.setAlignment(Pos.CENTER_LEFT);

        TextField txtPdfSearch = new TextField();
        txtPdfSearch.setPromptText("🔍 Type to search PDF filename or QP code (e.g. 152087, BOT)...");
        txtPdfSearch.setStyle("-fx-font-size: 12px; -fx-padding: 6 10; -fx-background-radius: 6;");
        HBox.setHgrow(txtPdfSearch, Priority.ALWAYS);

        Button btnClearPdfSearch = new Button("✕");
        btnClearPdfSearch.setStyle("-fx-font-size: 11px; -fx-padding: 5 8;");
        btnClearPdfSearch.setOnAction(e -> txtPdfSearch.clear());

        searchPdfBar.getChildren().addAll(txtPdfSearch, btnClearPdfSearch);

        // Sort: Unassigned PDFs first, then others
        Set<QPRecord> assignedQps = new HashSet<>();
        for (MatchDecision d : decisionsList) {
            if (d.matchedRecord != null) assignedQps.add(d.matchedRecord);
        }

        List<QPRecord> sortedPdfs = new ArrayList<>(availableQpRecords);
        sortedPdfs.sort((r1, r2) -> {
            boolean a1 = assignedQps.contains(r1);
            boolean a2 = assignedQps.contains(r2);
            if (!a1 && a2) return -1;
            if (a1 && !a2) return 1;
            return r1.filename.compareToIgnoreCase(r2.filename);
        });

        ObservableList<QPRecord> masterPdfList = FXCollections.observableArrayList(sortedPdfs);
        FilteredList<QPRecord> filteredPdfs = new FilteredList<>(masterPdfList, p -> true);

        comboManualOverride = new ComboBox<>(filteredPdfs);
        comboManualOverride.setPromptText("Choose a PDF file from folder...");
        comboManualOverride.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(comboManualOverride, Priority.ALWAYS);
        comboManualOverride.setStyle("-fx-font-size: 12px;");

        Map<QPRecord, MatchDecision> qpToDecisionMap = new HashMap<>();
        for (MatchDecision d : decisionsList) {
            if (d.matchedRecord != null) qpToDecisionMap.put(d.matchedRecord, d);
        }

        comboManualOverride.setConverter(new StringConverter<QPRecord>() {
            @Override
            public String toString(QPRecord item) {
                if (item == null) return "";
                MatchDecision targetD = qpToDecisionMap.get(item);
                if (targetD == null || targetD.requirement == null) {
                    return "[UNASSIGNED] " + item.filename + " (" + (item.qpCode != null ? item.qpCode : "No QP") + ")";
                } else {
                    String cCode = targetD.requirement.extractedCourseCode;
                    if (cCode == null || cCode.isEmpty()) cCode = targetD.requirement.extractedTitle;
                    return "[ASSIGNED to " + cCode + "] " + item.filename + " (" + (item.qpCode != null ? item.qpCode : "No QP") + ")";
                }
            }

            @Override
            public QPRecord fromString(String string) {
                return null;
            }
        });

        comboManualOverride.setCellFactory(lv -> new ListCell<QPRecord>() {
            @Override
            protected void updateItem(QPRecord item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    MatchDecision targetD = qpToDecisionMap.get(item);
                    if (targetD == null || targetD.requirement == null) {
                        setGraphic(createWarningIcon(11, "#d97706"));
                        setText("[UNASSIGNED] " + item.filename + " (" + (item.qpCode != null ? item.qpCode : "No QP") + ")");
                    } else {
                        String cCode = targetD.requirement.extractedCourseCode;
                        if (cCode == null || cCode.isEmpty()) cCode = targetD.requirement.extractedTitle;
                        setGraphic(createCheckIcon(11, "#16a34a"));
                        setText("[ASSIGNED to " + cCode + "] " + item.filename + " (" + (item.qpCode != null ? item.qpCode : "No QP") + ")");
                    }
                }
            }
        });

        // Search listener for PDF dropdown
        txtPdfSearch.textProperty().addListener((obs, oldVal, newVal) -> {
            String q = (newVal == null) ? "" : newVal.trim().toLowerCase();
            filteredPdfs.setPredicate(r -> {
                if (q.isEmpty()) return true;
                String fn = r.filename.toLowerCase();
                String qpCode = (r.qpCode != null) ? r.qpCode.toLowerCase() : "";
                String cCode = (r.courseCode != null) ? r.courseCode.toLowerCase() : "";
                String cName = (r.courseName != null) ? r.courseName.toLowerCase() : "";
                return fn.contains(q) || qpCode.contains(q) || cCode.contains(q) || cName.contains(q);
            });
            if (filteredPdfs.size() == 1) {
                comboManualOverride.setValue(filteredPdfs.get(0));
            }
        });

        if (qp != null) {
            comboManualOverride.setValue(qp);
        }

        comboManualOverride.setMaxWidth(Double.MAX_VALUE);

        btnApplyManual = new Button("✓ Apply Assignment");
        btnApplyManual.setStyle("-fx-font-size: 13px; -fx-padding: 8 18; -fx-background-color: #f59e0b; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6;");
        btnApplyManual.setMinWidth(Region.USE_PREF_SIZE);
        btnApplyManual.setOnAction(e -> applyManualAssignment(decision, comboManualOverride.getValue()));

        btnClearMatch = new Button("✕ Clear Match");
        btnClearMatch.setStyle("-fx-font-size: 12px; -fx-padding: 8 14; -fx-background-color: #f1f5f9; -fx-text-fill: #64748b; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 6; -fx-border-color: #cbd5e1; -fx-border-radius: 6;");
        btnClearMatch.setMinWidth(Region.USE_PREF_SIZE);
        btnClearMatch.setDisable(decision.matchedRecord == null);
        btnClearMatch.setOnAction(e -> clearCurrentMatch(decision));

        HBox btnRow = new HBox(10, btnApplyManual, btnClearMatch);
        btnRow.setAlignment(Pos.CENTER_LEFT);

        actionCard.getChildren().addAll(lblActionTitle, searchPdfBar, comboManualOverride, btnRow);

        // Diagnostics
        txtReasons.setText(String.join("\n", decision.reasons));
    }

    private void renderPdfPreview(File file) {
        if (file == null || !file.exists()) {
            previewImageView.setImage(null);
            lblPreviewPlaceholder.setText("File not found on disk");
            lblPreviewPlaceholder.setVisible(true);
            return;
        }

        lblPreviewPlaceholder.setText("Rendering page header preview...");
        lblPreviewPlaceholder.setVisible(true);
        previewImageView.setImage(null);

        PDFHeaderRenderer.renderHeaderAsync(file).thenAccept(img -> {
            Platform.runLater(() -> {
                if (img != null) {
                    previewImageView.setImage(img);
                    lblPreviewPlaceholder.setVisible(false);
                    if (previewScroll != null) {
                        previewScroll.setVvalue(0.0);
                        Platform.runLater(() -> previewScroll.setVvalue(0.0));
                    }
                } else {
                    lblPreviewPlaceholder.setText("Could not render PDF preview");
                    lblPreviewPlaceholder.setVisible(true);
                }
            });
        });
    }

    private void addGridRow(GridPane grid, int row, String col0, String col1, String col2, boolean isHeader) {
        Label l0 = new Label(col0);
        Label l1 = new Label(col1);
        Label l2 = new Label(col2);

        String style = isHeader ? "-fx-font-weight: bold; -fx-text-fill: #1e293b; -fx-font-size: 12px;" :
                                  "-fx-text-fill: #475569; -fx-font-size: 12px;";
        l0.setStyle(style);
        l1.setStyle(style);
        l2.setStyle(style);

        l0.setPrefWidth(100);
        l1.setPrefWidth(220);
        l2.setPrefWidth(240);

        grid.add(l0, 0, row);
        grid.add(l1, 1, row);
        grid.add(l2, 2, row);
    }

    // =========================================================================
    // BIDIRECTIONAL ASSIGNMENT ACTIONS
    // =========================================================================

    /**
     * Match a PDF file from Tab 1 to a chosen JSON course.
     */
    private void assignPdfToSelectedCourse(PDFItem pdfItem, MatchDecision chosenDecision) {
        if (pdfItem == null || chosenDecision == null) return;

        QPRecord targetQp = pdfItem.qpRecord;

        // 1. If another decision was using this PDF, clear it unless it is the same course across streams
        for (MatchDecision d : decisionsList) {
            if (d != chosenDecision && d.matchedRecord == targetQp) {
                boolean sameCourse = d.requirement != null && chosenDecision.requirement != null
                        && Objects.equals(d.requirement.normCourseCode, chosenDecision.requirement.normCourseCode)
                        && Objects.equals(d.requirement.normTitle, chosenDecision.requirement.normTitle);
                if (!sameCourse) {
                    d.matchedRecord = null;
                    d.status = "UNMATCHED";
                    d.isManualOverride = false;
                    d.reasons.clear();
                    d.reasons.add("Unlinked: PDF reassigned to " + chosenDecision.requirement.getDisplayName());
                }
            }
        }

        // 2. Assign to chosen course
        chosenDecision.matchedRecord = targetQp;
        chosenDecision.status = "MANUAL";
        chosenDecision.isManualOverride = true;
        chosenDecision.reasons.clear();
        chosenDecision.reasons.add("Manual assignment from PDF preview (" + pdfItem.getFileName() + ")");

        saveStateToCache();
        syncAllState();
        updateDetailViewForPdf(pdfItem);
    }

    /**
     * Clear assignment for a PDF item.
     */
    private void clearPdfAssignment(PDFItem pdfItem) {
        if (pdfItem == null || pdfItem.assignedDecision == null) return;
        MatchDecision d = pdfItem.assignedDecision;
        d.matchedRecord = null;
        d.status = "UNMATCHED";
        d.isManualOverride = false;
        d.reasons.clear();
        d.reasons.add("Assignment cleared by user");

        saveStateToCache();
        syncAllState();
        updateDetailViewForPdf(pdfItem);
    }

    /**
     * Match a Course from Tab 2 to a chosen PDF file.
     */
    private void applyManualAssignment(MatchDecision decision, QPRecord chosenQp) {
        if (decision == null || chosenQp == null) return;

        // 1. If another decision was using this QP, clear it unless it is the same course across streams
        for (MatchDecision d : decisionsList) {
            if (d != decision && d.matchedRecord == chosenQp) {
                boolean sameCourse = d.requirement != null && decision.requirement != null
                        && Objects.equals(d.requirement.normCourseCode, decision.requirement.normCourseCode)
                        && Objects.equals(d.requirement.normTitle, decision.requirement.normTitle);
                if (!sameCourse) {
                    d.matchedRecord = null;
                    d.status = "UNMATCHED";
                    d.isManualOverride = false;
                    d.reasons.clear();
                    d.reasons.add("Unlinked: PDF reassigned to " + decision.requirement.getDisplayName());
                }
            }
        }

        decision.matchedRecord = chosenQp;
        decision.status = "MANUAL";
        decision.isManualOverride = true;
        decision.reasons.clear();
        decision.reasons.add("Manual user assignment (" + chosenQp.filename + ")");

        saveStateToCache();
        syncAllState();
        updateDetailViewForCourse(decision);
    }

    /**
     * Clear match for a course requirement.
     */
    private void clearCurrentMatch(MatchDecision decision) {
        if (decision == null) return;
        decision.matchedRecord = null;
        decision.status = "UNMATCHED";
        decision.isManualOverride = false;
        decision.reasons.clear();
        decision.reasons.add("Match cleared by user");

        saveStateToCache();
        syncAllState();
        updateDetailViewForCourse(decision);
    }

    /**
     * Re-synchronizes PDF items, Course requirements, tables, badges, and stats.
     */
    private void syncAllState() {
        // Build map of QPRecord -> MatchDecision
        Map<QPRecord, MatchDecision> qpToDecision = new HashMap<>();
        for (MatchDecision d : decisionsList) {
            if (d.matchedRecord != null) {
                qpToDecision.put(d.matchedRecord, d);
            }
        }

        // Update PDF items
        List<PDFItem> updatedPdfs = new ArrayList<>();
        for (QPRecord r : availableQpRecords) {
            updatedPdfs.add(new PDFItem(r, qpToDecision.get(r)));
        }
        pdfItemsList.setAll(updatedPdfs);

        // Refresh tables
        reqTable.refresh();
        pdfTable.refresh();

        // Update overall stats & badges
        updateOverallStats();
    }

    // =========================================================================
    // SCANNING & STATS
    // =========================================================================

    private static String computeFileSignature(File f) {
        try {
            long len = f.length();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream is = new java.io.FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = is.read(buf)) != -1) {
                    md.update(buf, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            sb.append(len).append("_");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            return f.getName() + "_" + f.length();
        }
    }

    public void runScanAndMatch() {
        if (currentJsonRoot == null) return;
        if ((currentPdfFolder == null || !currentPdfFolder.exists()) && (extraPdfFiles == null || extraPdfFiles.isEmpty())) return;

        PDFHeaderRenderer.clearCache();
        statusSummaryLabel.setText("Scanning PDFs...");
        statusSummaryLabel.setStyle("-fx-background-color: #e0f2fe; -fx-text-fill: #0369a1;");

        new Thread(() -> {
            try {
                // 1. Scan PDFs ONLY from current folder (or extra files belonging to this folder)
                Map<String, File> uniqueFilesByName = new LinkedHashMap<>();

                if (currentPdfFolder != null && currentPdfFolder.exists()) {
                    File[] folderFiles = currentPdfFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".pdf"));
                    if (folderFiles != null) {
                        for (File f : folderFiles) {
                            uniqueFilesByName.put(f.getName().toLowerCase(), f);
                        }
                    }
                }

                if (extraPdfFiles != null) {
                    for (File f : extraPdfFiles) {
                        if (f != null && f.exists() && f.getName().toLowerCase().endsWith(".pdf")) {
                            if (currentPdfFolder != null && f.getParentFile() != null) {
                                if (f.getParentFile().getAbsolutePath().equalsIgnoreCase(currentPdfFolder.getAbsolutePath())) {
                                    uniqueFilesByName.putIfAbsent(f.getName().toLowerCase(), f);
                                }
                            } else if (currentPdfFolder == null) {
                                uniqueFilesByName.putIfAbsent(f.getName().toLowerCase(), f);
                            }
                        }
                    }
                }

                List<File> pdfFilesList = new ArrayList<>(uniqueFilesByName.values());
                pdfFilesList.sort(Comparator.comparing(File::getName));

                List<QPRecord> records = new ArrayList<>();
                Map<String, QPRecord> seenSigs = new HashMap<>();
                Map<String, QPRecord> seenQpKeys = new HashMap<>();
                Map<String, QPRecord> seenQpCodes = new HashMap<>();

                for (File pdf : pdfFilesList) {
                    String sig = computeFileSignature(pdf);
                    if (seenSigs.containsKey(sig)) {
                        QPRecord primary = seenSigs.get(sig);
                        primary.duplicateCount++;
                        primary.duplicateFilenames.add(pdf.getName());
                        logger.info("Deduplicated identical download {} (identical to {})", pdf.getName(), primary.filename);
                        continue;
                    }

                    QPRecord r = engine.extractFromPdf(pdf);
                    seenSigs.put(sig, r);

                    // Check exact QP Code deduplication (e.g. "D 153275" -> "153275")
                    String normQp = "";
                    if (r.qpCode != null) {
                        normQp = r.qpCode.replaceAll("[\\s_\\-]", "").toUpperCase();
                        if (normQp.startsWith("D") && normQp.length() > 5) {
                            normQp = normQp.substring(1);
                        }
                    }

                    if (!normQp.isEmpty() && normQp.matches("\\d{5,6}[A-Za-z]?") && seenQpCodes.containsKey(normQp)) {
                        QPRecord primary = seenQpCodes.get(normQp);
                        primary.duplicateCount++;
                        primary.duplicateFilenames.add(pdf.getName());
                        logger.info("Deduplicated duplicate download for QP {} (file {} matches {})", normQp, pdf.getName(), primary.filename);
                        continue;
                    }
                    if (!normQp.isEmpty() && normQp.matches("\\d{5,6}[A-Za-z]?")) {
                        seenQpCodes.put(normQp, r);
                    }

                    // Also check if exact same QP Code & Course Code was already scanned
                    String qpCodeNorm = (r.qpCode != null) ? r.qpCode.replaceAll("[\\s_\\-]", "").toUpperCase() : "";
                    String courseCodeNorm = (r.courseCode != null) ? r.courseCode.replaceAll("[\\s_\\-]", "").toUpperCase() : "";
                    String qpKey = qpCodeNorm + "::" + courseCodeNorm;

                    if (!qpCodeNorm.isEmpty() && !courseCodeNorm.isEmpty() && seenQpKeys.containsKey(qpKey)) {
                        QPRecord primary = seenQpKeys.get(qpKey);
                        primary.duplicateCount++;
                        primary.duplicateFilenames.add(pdf.getName());
                        logger.info("Deduplicated same QP & Course {} (matches {})", pdf.getName(), primary.filename);
                        continue;
                    }
                    if (!qpCodeNorm.isEmpty() && !courseCodeNorm.isEmpty()) {
                        seenQpKeys.put(qpKey, r);
                    }

                    records.add(r);
                }
                availableQpRecords = records;

                // 2. Parse Seating Requirements
                List<SeatingRequirement> reqs = engine.parseRequirementsFromJson(currentJsonRoot);

                // 3. Match
                String mode = modeCombo.getValue().contains("Strict") ? "strict" : "department";
                List<MatchDecision> results = engine.matchAll(reqs, records, mode);

                Platform.runLater(() -> {
                    decisionsList.setAll(results);
                    syncAllState();

                    // Default selection: select first unassigned PDF or first course
                    PDFItem firstUnassigned = pdfItemsList.stream().filter(p -> !p.isAssigned()).findFirst().orElse(null);
                    if (firstUnassigned != null) {
                        mainTabPane.getSelectionModel().select(tabPdfs);
                        pdfTable.getSelectionModel().select(firstUnassigned);
                    } else if (!decisionsList.isEmpty()) {
                        mainTabPane.getSelectionModel().select(tabCourses);
                        reqTable.getSelectionModel().select(0);
                    }
                });
            } catch (Exception ex) {
                logger.error("Scan & Match failed", ex);
                Platform.runLater(() -> {
                    statusSummaryLabel.setText("Scan Error: " + ex.getMessage());
                    statusSummaryLabel.setStyle("-fx-background-color: #fee2e2; -fx-text-fill: #991b1b;");
                });
            }
        }).start();
    }

    private void updateOverallStats() {
        int totalCourses = decisionsList.size();
        long matchedCourses = decisionsList.stream().filter(d -> !"UNMATCHED".equalsIgnoreCase(d.status)).count();
        int missingCourses = (int) (totalCourses - matchedCourses);

        int totalPdfs = pdfItemsList.size();
        long assignedPdfs = pdfItemsList.stream().filter(PDFItem::isAssigned).count();
        int unassignedPdfs = (int) (totalPdfs - assignedPdfs);

        double pct = (totalCourses > 0) ? (matchedCourses * 100.0 / totalCourses) : 0.0;

        statusSummaryLabel.setText(String.format("%d of %d Courses Matched (%.1f%%)", matchedCourses, totalCourses, pct));
        if (pct >= 90.0) {
            statusSummaryLabel.setStyle("-fx-background-color: #dcfce7; -fx-text-fill: #166534; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        } else {
            statusSummaryLabel.setStyle("-fx-background-color: #fef3c7; -fx-text-fill: #92400e; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
        }

        // Update pills
        btnMissingCoursesPill.setText(missingCourses + " Courses Missing PDF");
        btnMissingCoursesPill.setVisible(missingCourses > 0);
        btnMissingCoursesPill.setManaged(missingCourses > 0);

        btnUnassignedPdfsPill.setText(unassignedPdfs + " Unassigned PDFs");
        btnUnassignedPdfsPill.setVisible(unassignedPdfs > 0);
        btnUnassignedPdfsPill.setManaged(unassignedPdfs > 0);

        // Update tab header titles
        tabPdfs.setText("📁 PDF Files in Folder (" + unassignedPdfs + " Unassigned)");
        tabCourses.setText("📋 JSON Courses Required (" + missingCourses + " Missing)");

        // Update filter buttons labels
        btnFilterPdfAll.setText("All PDFs (" + totalPdfs + ")");
        btnFilterPdfUnassigned.setText("Unassigned Files (" + unassignedPdfs + ")");
        btnFilterPdfUnassigned.setGraphic(createWarningIcon(13, "#b45309"));
        btnFilterPdfAssigned.setText("Assigned Files (" + assignedPdfs + ")");
        btnFilterPdfAssigned.setGraphic(createCheckIcon(13, "#16a34a"));

        btnFilterCourseAll.setText("All Courses (" + totalCourses + ")");
        btnFilterCourseUnmatched.setText("❌ Missing PDF (" + missingCourses + ")");
        btnFilterCourseMatched.setText("Matched (" + matchedCourses + ")");
        btnFilterCourseMatched.setGraphic(createCheckIcon(13, "#16a34a"));
    }

    // =========================================================================
    // EXPORT & APPLY
    // =========================================================================

    private void exportPopulatedJson() {
        if (currentJsonRoot == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Populated Seating JSON");
        fc.setInitialFileName("Room_Wise_Seating_Summary_Populated.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        if (currentJsonFile != null && currentJsonFile.getParentFile() != null) {
            fc.setInitialDirectory(currentJsonFile.getParentFile());
        }

        File outFile = fc.showSaveDialog(stage);
        if (outFile == null) return;

        try {
            JsonNode populated = buildPopulatedJsonNode();
            ObjectMapper mapper = new ObjectMapper();
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                mapper.writerWithDefaultPrettyPrinter().writeValue(fos, populated);
            }
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Populated JSON successfully saved to:\n" + outFile.getAbsolutePath(), ButtonType.OK);
            alert.show();
        } catch (Exception ex) {
            logger.error("Failed to export populated JSON", ex);
            new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage(), ButtonType.OK).show();
        }
    }

    private JsonNode buildPopulatedJsonNode() {
        ObjectMapper mapper = new ObjectMapper();
        ArrayNode arr = mapper.createArrayNode();

        // Build mapping from Requirement to QP Record
        Map<SeatingRequirement, QPRecord> recordMap = new HashMap<>();
        for (MatchDecision d : decisionsList) {
            if (d.matchedRecord != null) {
                recordMap.put(d.requirement, d.matchedRecord);
            }
        }

        if (currentJsonRoot.isArray()) {
            for (JsonNode node : currentJsonRoot) {
                ObjectNode on = node.deepCopy();
                for (Map.Entry<SeatingRequirement, QPRecord> entry : recordMap.entrySet()) {
                    if (entry.getKey().matchingJsonNodes.contains(node)) {
                        QPRecord rec = entry.getValue();
                        String cleanQp = rec.qpCode != null ? rec.qpCode.trim() : "";
                        if (cleanQp.isEmpty() && rec.courseCode != null) cleanQp = rec.courseCode.trim();
                        if (cleanQp.isEmpty() && rec.filename != null) cleanQp = rec.filename.replaceAll("(?i)\\.pdf$", "");

                        on.put("qpCode", cleanQp);
                        on.put("pdfFileName", rec.filename);
                        if (rec.file != null) {
                            on.put("pdfFilePath", rec.file.getAbsolutePath());
                        }
                        break;
                    }
                }
                arr.add(on);
            }
        }
        return arr;
    }

    private void applyAndLoadIntoOuterUI() {
        saveStateToCache();
        JsonNode populated = buildPopulatedJsonNode();
        if (onApplyCallback != null) {
            onApplyCallback.accept(populated);
        }
        stage.close();
    }

    private void browseJsonFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Select Seating JSON");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON Files", "*.json"));
        if (currentJsonFile != null && currentJsonFile.getParentFile() != null) {
            fc.setInitialDirectory(currentJsonFile.getParentFile());
        }
        File f = fc.showOpenDialog(stage);
        if (f != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                this.currentJsonFile = f;
                this.currentJsonRoot = mapper.readTree(f);
                this.lblJsonInfo.setText("📄 " + f.getName());
                if (currentPdfFolder == null && f.getParentFile() != null) {
                    this.currentPdfFolder = f.getParentFile();
                    this.lblFolderInfo.setText("📁 " + currentPdfFolder.getAbsolutePath());
                }
                sCachedDecisions = null;
                sCachedQpRecords = null;
                runScanAndMatch();
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Error reading JSON: " + ex.getMessage()).show();
            }
        }
    }

    private void browsePdfFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select Folder Containing Question Paper PDFs");
        if (currentPdfFolder != null && currentPdfFolder.exists()) {
            dc.setInitialDirectory(currentPdfFolder);
        }
        File d = dc.showDialog(stage);
        if (d != null) {
            this.currentPdfFolder = d;
            this.lblFolderInfo.setText("📁 " + d.getAbsolutePath());
            sCachedDecisions = null;
            sCachedQpRecords = null;
            runScanAndMatch();
        }
    }
}
