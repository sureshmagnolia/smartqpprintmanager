package com.printmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.printmanager.model.Config;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigManager {
    private static final Logger logger = LoggerFactory.getLogger(ConfigManager.class);
    private static final String CONFIG_FILE = "config.json";
    private final ObjectMapper mapper = new ObjectMapper();

    public Config loadConfig() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            logger.info("Config file not found, creating a new one.");
            return new Config();
        }
        try {
            return mapper.readValue(file, Config.class);
        } catch (IOException e) {
            logger.error("Error loading config: {}", e.getMessage());
            return new Config();
        }
    }

    public void saveConfig(Config config) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(new File(CONFIG_FILE), config);
            logger.info("Configuration saved successfully.");
        } catch (IOException e) {
            logger.error("Error saving config: {}", e.getMessage());
        }
    }
}
