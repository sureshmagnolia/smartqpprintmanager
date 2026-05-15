package com.printmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printmanager.model.Config;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final String APP_NAME = "SmartQPPrintManager";
    private static final String CONFIG_FILE_NAME = "config.json";
    private final ObjectMapper mapper = new ObjectMapper();

    private File getResolvedConfigFile() {
        // 1. Check local directory (Portable Mode)
        // If a config exists locally and is writable, OR if the directory is writable, stay local.
        File localFile = new File(CONFIG_FILE_NAME);
        File localDir = new File(".");
        
        if (localFile.exists()) {
            if (localFile.canWrite()) {
                logger.info("Using local config (Portable Mode): {}", localFile.getAbsolutePath());
                return localFile;
            } else {
                logger.info("Local config exists but is not writable. Switching to AppData.");
            }
        } else if (localDir.canWrite()) {
            logger.info("Local directory is writable. Using local config: {}", localFile.getAbsolutePath());
            return localFile;
        }

        // 2. Use AppData / User Home (Installed Mode)
        String userHome = System.getProperty("user.home");
        String os = System.getProperty("os.name").toLowerCase();
        File appDataDir;
        
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                appDataDir = new File(appData, APP_NAME);
            } else {
                appDataDir = new File(userHome, "AppData/Roaming/" + APP_NAME);
            }
        } else if (os.contains("mac")) {
            appDataDir = new File(userHome, "Library/Application Support/" + APP_NAME);
        } else {
            appDataDir = new File(userHome, "." + APP_NAME.toLowerCase());
        }

        if (!appDataDir.exists()) {
            boolean created = appDataDir.mkdirs();
            logger.info("Creating AppData directory: {} -> {}", appDataDir.getAbsolutePath(), created);
        }
        
        File appDataFile = new File(appDataDir, CONFIG_FILE_NAME);
        
        // If AppData config doesn't exist, try to seed it from the read-only local config (if it exists)
        if (!appDataFile.exists() && localFile.exists()) {
            try {
                Files.copy(localFile.toPath(), appDataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Seeded AppData config from local installation: {}", localFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to seed AppData config: {}", e.getMessage());
            }
        }
        
        logger.info("Using AppData config: {}", appDataFile.getAbsolutePath());
        return appDataFile;
    }

    public Config loadConfig() {
        File file = getResolvedConfigFile();
        if (!file.exists()) {
            logger.info("Config file {} not found, returning empty config.", file.getAbsolutePath());
            return new Config();
        }
        try {
            logger.info("Loading config from: {}", file.getAbsolutePath());
            Config cfg = mapper.readValue(file, Config.class);
            logger.info("Config loaded successfully. Rules: {}, FileQueue: {}, RoomGroups: {}", 
                cfg.getRules().size(), cfg.getFileQueue().size(), cfg.getRoomGroups().size());
            return cfg;
        } catch (IOException e) {
            logger.error("Error loading config from {}: {}", file.getAbsolutePath(), e.getMessage());
            e.printStackTrace();
            return new Config();
        }
    }

    public void saveConfig(Config config) {
        try {
            File file = getResolvedConfigFile();
            logger.info("Saving config to: {}. FileQueue: {}, RoomGroups: {}", 
                file.getAbsolutePath(), config.getFileQueue().size(), config.getRoomGroups().size());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
            logger.info("Configuration saved successfully.");
        } catch (IOException e) {
            logger.error("Error saving config to {}: {}", getResolvedConfigFile().getAbsolutePath(), e.getMessage());
            e.printStackTrace();
        }
    }
}
