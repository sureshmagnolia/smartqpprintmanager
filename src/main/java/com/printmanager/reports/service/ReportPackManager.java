package com.printmanager.reports.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.printmanager.ConfigManager;
import com.printmanager.reports.model.ReportPack;
import com.printmanager.reports.model.ReportPackItem;
import com.printmanager.reports.model.ReportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ReportPackManager {
    private static final Logger logger = LoggerFactory.getLogger(ReportPackManager.class);
    private static final String PACKS_FILE_NAME = "report_packs.json";
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final List<ReportPack> packs = new ArrayList<>();
    private final File packsFile;

    public ReportPackManager() {
        this.packsFile = resolvePacksFile();
        loadPacks();
    }

    private File resolvePacksFile() {
        ConfigManager cm = new ConfigManager();
        File configFile = new File(cm.getConfigPath());
        return new File(configFile.getParentFile(), PACKS_FILE_NAME);
    }

    public synchronized List<ReportPack> getPacks() {
        return new ArrayList<>(packs);
    }

    public synchronized ReportPack getPack(String id) {
        if (id == null) return null;
        for (ReportPack p : packs) {
            if (p.getId().equalsIgnoreCase(id)) return p;
        }
        return null;
    }

    public synchronized void savePack(ReportPack pack) {
        if (pack == null) return;
        boolean found = false;
        for (int i = 0; i < packs.size(); i++) {
            if (packs.get(i).getId().equalsIgnoreCase(pack.getId())) {
                packs.set(i, pack);
                found = true;
                break;
            }
        }
        if (!found) {
            packs.add(pack);
        }
        saveToFile();
    }

    public synchronized void deletePack(String id) {
        if (id == null) return;
        packs.removeIf(p -> p.getId().equalsIgnoreCase(id));
        saveToFile();
    }

    public synchronized void resetToDefaults() {
        packs.clear();
        packs.addAll(createDefaultPacks());
        saveToFile();
    }

    private void loadPacks() {
        if (packsFile != null && packsFile.exists() && packsFile.length() > 0) {
            try {
                List<ReportPack> loaded = mapper.readValue(packsFile, new TypeReference<List<ReportPack>>() {});
                if (loaded != null && !loaded.isEmpty()) {
                    packs.clear();
                    packs.addAll(loaded);
                    logger.info("Loaded {} custom report pack(s) from {}", packs.size(), packsFile.getAbsolutePath());
                    return;
                }
            } catch (Exception e) {
                logger.warn("Could not load report packs from {}: {}. Reverting to defaults.", packsFile.getAbsolutePath(), e.getMessage());
            }
        }
        resetToDefaults();
    }

    private synchronized void saveToFile() {
        if (packsFile == null) return;
        try {
            if (!packsFile.getParentFile().exists()) {
                packsFile.getParentFile().mkdirs();
            }
            mapper.writeValue(packsFile, packs);
            logger.info("Saved {} report pack(s) to {}", packs.size(), packsFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Failed to save report packs to {}: {}", packsFile.getAbsolutePath(), e.getMessage(), e);
        }
    }

    public static List<ReportPack> createDefaultPacks() {
        List<ReportPack> defaults = new ArrayList<>();

        // 1. Pre-Exam Pack
        List<ReportPackItem> preExamItems = new ArrayList<>();
        preExamItems.add(new ReportPackItem(ReportType.QP_REQUIREMENT.getId(), true, 1, false));
        preExamItems.add(new ReportPackItem(ReportType.QP_DISTRIBUTION.getId(), true, 1, false));
        preExamItems.add(new ReportPackItem(ReportType.ROOM_STICKERS.getId(), true, 1, false));
        defaults.add(new ReportPack("pre_exam_pack", "Pre-Exam Pack (QP & Hall Prep)",
                "Question Paper Abstract, QP Distribution Checklist, and Room Door Slips", "Default", preExamItems));

        // 2. Notice Board Pack
        List<ReportPackItem> nbItems = new ArrayList<>();
        nbItems.add(new ReportPackItem(ReportType.CANDIDATE_DAYWISE_SEATING.getId(), true, 1, false));
        defaults.add(new ReportPack("notice_board_pack", "Notice Board Pack",
                "High-density candidate seating register grid for portico and entrances", "Default", nbItems));

        // 3. Superintendent & Control Room Pack
        List<ReportPackItem> superItems = new ArrayList<>();
        superItems.add(new ReportPackItem(ReportType.ROOM_ALLOTMENT_SUMMARY.getId(), true, 1, false));
        superItems.add(new ReportPackItem(ReportType.INVIGILATOR_REQUIREMENT.getId(), true, 1, false));
        defaults.add(new ReportPack("superintendent_pack", "Superintendent & Observer Pack",
                "Room Allotment Abstract and Invigilator Duty Sign-in Summary", "Default", superItems));

        // 4. Hall Signing Sheets Pack
        List<ReportPackItem> hallItems = new ArrayList<>();
        hallItems.add(new ReportPackItem(ReportType.ROOM_WISE_SEATING.getId(), true, 1, true)); // Duplex
        hallItems.add(new ReportPackItem(ReportType.ABSENTEE_STATEMENT.getId(), true, 1, false)); // Simplex
        defaults.add(new ReportPack("hall_signing_pack", "Hall Signing Sheets Pack",
                "Official candidate attendance nominal rolls (Duplex) + Room Absentee slips", "Default", hallItems));

        // 5. Scribe Assistance Pack
        List<ReportPackItem> scribeItems = new ArrayList<>();
        scribeItems.add(new ReportPackItem(ReportType.SCRIBE_PROFORMA.getId(), true, 1, false));
        scribeItems.add(new ReportPackItem(ReportType.SCRIBE_ASSISTANCE.getId(), true, 1, false));
        defaults.add(new ReportPack("scribe_pack", "Scribe Assistance Pack",
                "Individual scribe declaration proformas and duty assignment summary", "Default", scribeItems));

        // 6. Complete Session Master Pack
        List<ReportPackItem> masterItems = new ArrayList<>();
        for (ReportType type : ReportType.values()) {
            if (type != ReportType.SCRIBE_MASTER) { // Scribe master is multi-session audit
                masterItems.add(new ReportPackItem(type.getId(), true, 1, type.isDefaultDuplex()));
            }
        }
        defaults.add(new ReportPack("complete_session_pack", "Complete Session Master Pack",
                "All reports for the active session dispatched to their respective duplex/simplex queues", "Default", masterItems));

        return defaults;
    }
}

