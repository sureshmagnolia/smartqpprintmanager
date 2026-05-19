package com.printmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printmanager.model.Config;
import com.printmanager.model.AppState;
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
    private static final String STATE_FILE_NAME = "appstate.json";
    private final ObjectMapper mapper = new ObjectMapper();

    public String getConfigPath() {
        return getResolvedFile(CONFIG_FILE_NAME).getAbsolutePath();
    }

    private File getResolvedFile(String fileName) {
        String userDir = System.getProperty("user.dir");
        String os = System.getProperty("os.name").toLowerCase();
        
        boolean inProgramFiles = userDir.toLowerCase().contains("program files");
        File localFile = new File(userDir, fileName);
        
        if (!inProgramFiles && isDirWritable(userDir)) {
            if (fileName.equals(CONFIG_FILE_NAME)) {
                logger.info("Portable Mode detected (writable directory): {}", userDir);
            }
            return localFile;
        }

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
            appDataDir.mkdirs();
        }
        
        File appDataFile = new File(appDataDir, fileName);
        
        if (!appDataFile.exists() && localFile.exists()) {
            try {
                Files.copy(localFile.toPath(), appDataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.error("Failed to seed AppData file {}: {}", fileName, e.getMessage());
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
        File file = getResolvedFile(CONFIG_FILE_NAME);
        Config config = null;
        
        if (file.exists()) {
            try {
                config = mapper.readValue(file, Config.class);
                logger.info("Configuration loaded from {}", file.getAbsolutePath());
            } catch (IOException e) {
                logger.error("Error loading config from {}: {}", file.getAbsolutePath(), e.getMessage());
            }
        }
        
        // If config is missing or has no rules, try loading from resources as a fallback
        if (config == null || config.getRules().isEmpty()) {
            try (java.io.InputStream is = getClass().getResourceAsStream("/config.default.json")) {
                if (is != null) {
                    config = mapper.readValue(is, Config.class);
                    logger.info("Loaded default configuration from resources fallback.");
                }
            } catch (IOException e) {
                logger.error("Failed to load default configuration from resources: {}", e.getMessage());
            }
        }
        
        if (config == null) config = new Config();
        
        // Final safety: Ensure smartSplitRules is not null and has at least the default rule if empty
        if (config.getSmartSplitRules() == null || config.getSmartSplitRules().isEmpty()) {
            com.printmanager.model.SmartSplitRule defaultRule = new com.printmanager.model.SmartSplitRule();
            defaultRule.setKeyword("Multiple Choice Questions for SDE");
            defaultRule.setEnabled(true);
            config.getSmartSplitRules().add(defaultRule);
            logger.info("Injected missing default SmartSplitRule.");
        }
        
        return config;
    }

    public void saveConfig(Config config) {
        File file = getResolvedFile(CONFIG_FILE_NAME);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
            logger.info("Configuration saved successfully to {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Error saving config to {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    public AppState loadAppState() {
        File file = getResolvedFile(STATE_FILE_NAME);
        if (!file.exists()) return new AppState();
        try {
            return mapper.readValue(file, AppState.class);
        } catch (IOException e) {
            logger.error("Error loading app state from {}: {}", file.getAbsolutePath(), e.getMessage());
            return new AppState();
        }
    }

    public void saveAppState(AppState state) {
        File file = getResolvedFile(STATE_FILE_NAME);
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            mapper.writerWithDefaultPrettyPrinter().writeValue(file, state);
            logger.info("App state saved successfully to {}", file.getAbsolutePath());
        } catch (IOException e) {
            logger.error("Error saving app state to {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }
}
