package com.printmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printmanager.model.Config;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final String APP_NAME = "SmartQPPrintManager";
    private static final String CONFIG_FILE_NAME = "config.json";
    private final ObjectMapper mapper = new ObjectMapper();

    public String getConfigPath() {
        return getResolvedConfigFile().getAbsolutePath();
    }

    private File getResolvedConfigFile() {
        String userDir = System.getProperty("user.dir");
        String os = System.getProperty("os.name").toLowerCase();
        
        // 1. Determine if we should force AppData (Installed Mode)
        // If we are in Program Files, we almost certainly want AppData.
        boolean inProgramFiles = userDir.toLowerCase().contains("program files");
        
        File localFile = new File(userDir, CONFIG_FILE_NAME);
        
        if (!inProgramFiles) {
            // Check if local directory is truly writable by trying to create/delete a dummy file
            // canWrite() can sometimes be misleading on Windows due to VirtualStore
            if (isDirWritable(userDir)) {
                logger.info("Portable Mode detected (writable directory): {}", userDir);
                return localFile;
            }
        }

        // 2. Use AppData / User Home (Installed Mode)
        File appDataDir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null) {
                appDataDir = new File(appData, APP_NAME);
            } else {
                appDataDir = new File(System.getProperty("user.home"), "AppData/Roaming/" + APP_NAME);
            }
        } else if (os.contains("mac")) {
            appDataDir = new File(System.getProperty("user.home"), "Library/Application Support/" + APP_NAME);
        } else {
            appDataDir = new File(System.getProperty("user.home"), "." + APP_NAME.toLowerCase());
        }

        if (!appDataDir.exists()) {
            boolean created = appDataDir.mkdirs();
            logger.info("Creating AppData directory: {} -> {}", appDataDir.getAbsolutePath(), created);
        }
        
        File appDataFile = new File(appDataDir, CONFIG_FILE_NAME);
        
        // 3. Seed AppData from Local if AppData is new and Local exists
        if (!appDataFile.exists() && localFile.exists()) {
            try {
                Files.copy(localFile.toPath(), appDataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                logger.info("Seeded AppData config from local installation: {}", localFile.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Failed to seed AppData config: {}", e.getMessage());
            }
        }
        
        return appDataFile;
    }

    private boolean isDirWritable(String dirPath) {
        try {
            Path path = Paths.get(dirPath, ".write_test_" + System.currentTimeMillis());
            Files.createFile(path);
            Files.delete(path);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Config loadConfig() {
        File file = getResolvedConfigFile();
        if (!file.exists()) {
            logger.info("Config file {} not found, returning default config.", file.getAbsolutePath());
            return new Config();
        }
        try {
            logger.info("Loading config from: {}", file.getAbsolutePath());
            Config cfg = mapper.readValue(file, Config.class);
            return cfg;
        } catch (IOException e) {
            logger.error("Error loading config from {}: {}", file.getAbsolutePath(), e.getMessage());
            return new Config();
        }
    }

    public void saveConfig(Config config) {
        File file = getResolvedConfigFile();
        try {
            logger.info("Saving config to: {}", file.getAbsolutePath());
            // Ensure parent directory exists again just in case
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
            logger.info("Configuration saved successfully to {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Error saving config to {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }
}
