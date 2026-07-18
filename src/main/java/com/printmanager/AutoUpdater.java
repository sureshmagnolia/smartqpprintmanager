package com.printmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

public class AutoUpdater {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/sureshmagnolia/smartqpprintmanager/releases/latest";
    private static final String CURRENT_VERSION = "7.15";

    public static void checkForUpdates() {
        new Thread(() -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.ALWAYS)
                        .build();

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GITHUB_API_URL))
                        .header("Accept", "application/vnd.github.v3+json")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(response.body());
                    String tagName = root.path("tag_name").asText();
                    String latestVersion = parseVersionString(tagName);
                    System.out.println("AutoUpdater Parsed latestVersion: " + latestVersion);

                    if (compareVersions(latestVersion, CURRENT_VERSION) > 0) {
                        String downloadUrl = getMsiDownloadUrl(root);
                        System.out.println("AutoUpdater MSI URL: " + downloadUrl);
                        if (downloadUrl != null) {
                            Platform.runLater(() -> promptUserForUpdate(latestVersion, downloadUrl));
                        } else {
                            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "Update found but no MSI file attached to release!").show());
                        }
                    } else {
                        System.out.println("AutoUpdater: You are up to date.");
                    }
                } else {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "GitHub API returned " + response.statusCode()).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Failed to check for updates: " + e.toString()).show());
            }
        }).start();
    }

    private static String parseVersionString(String tag) {
        return tag.replaceAll("[^0-9.]", "");
    }

    private static int compareVersions(String v1, String v2) {
        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for(int i = 0; i < length; i++) {
            int p1 = i < parts1.length && !parts1[i].isEmpty() ? Integer.parseInt(parts1[i]) : 0;
            int p2 = i < parts2.length && !parts2[i].isEmpty() ? Integer.parseInt(parts2[i]) : 0;
            if(p1 < p2) return -1;
            if(p1 > p2) return 1;
        }
        return 0;
    }

    private static String getMsiDownloadUrl(JsonNode root) {
        JsonNode assets = root.path("assets");
        for (JsonNode asset : assets) {
            String name = asset.path("name").asText().toLowerCase();
            if (name.endsWith(".msi")) {
                return asset.path("browser_download_url").asText();
            }
        }
        return null;
    }

    private static void promptUserForUpdate(String newVersion, String downloadUrl) {
        File targetFile = new File(System.getProperty("java.io.tmpdir"), "SmartQPPrintManager_Update_V" + newVersion + ".msi");
        if (targetFile.exists() && targetFile.length() > 0) {
            System.out.println("AutoUpdater: Update already downloaded.");
            promptUserToInstall(newVersion, targetFile);
            return;
        }

        System.out.println("AutoUpdater: Starting background download of update...");
        downloadUpdateInBackground(newVersion, downloadUrl, targetFile);
    }

    private static void downloadUpdateInBackground(String newVersion, String downloadUrl, File targetFile) {
        new Thread(() -> {
            try {
                File tempFile = new File(targetFile.getAbsolutePath() + ".tmp");
                
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build();
                
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
                
                if (response.statusCode() == 200) {
                    try (InputStream is = response.body(); FileOutputStream fos = new FileOutputStream(tempFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        long totalRead = 0;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                            totalRead += bytesRead;
                            if (contentLength > 0) {
                                double progress = (double) totalRead / contentLength;
                                App.setUpdateProgress(progress, newVersion);
                            }
                        }
                    }
                    
                    if (targetFile.exists()) targetFile.delete();
                    tempFile.renameTo(targetFile);

                    System.out.println("AutoUpdater: Background download complete.");
                    App.hideUpdateProgress();
                    Platform.runLater(() -> promptUserToInstall(newVersion, targetFile));
                } else {
                    System.err.println("AutoUpdater: Failed to download update. HTTP " + response.statusCode());
                    App.hideUpdateProgress();
                    Platform.runLater(() -> {
                        new Alert(Alert.AlertType.ERROR, "Failed to download update in background (HTTP " + response.statusCode() + ").").show();
                    });
                }

            } catch (Exception e) {
                System.err.println("AutoUpdater: Background update download failed: " + e.getMessage());
                e.printStackTrace();
                App.hideUpdateProgress();
            }
        }).start();
    }
    
    private static void promptUserToInstall(String newVersion, File installerFile) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Update Ready");
        alert.setHeaderText("A new update (Version " + newVersion + ") is ready to install!");
        alert.setContentText("The update has been downloaded in the background. Would you like to restart the application and install the update now?");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            installUpdate(installerFile);
        }
    }

    private static void installUpdate(File installer) {
        try {
            // Determine the path to the current executable
            String currentExe = ProcessHandle.current().info().command().orElse("C:\\Program Files\\Smart QP Print Manager\\Smart QP Print Manager.exe");
            
            File updateScript = new File(System.getProperty("java.io.tmpdir"), "update_app.bat");
            try (java.io.PrintWriter writer = new java.io.PrintWriter(updateScript)) {
                writer.println("@echo off");
                writer.println("echo Waiting for application to exit...");
                writer.println("timeout /t 2 /nobreak >nul");
                writer.println("echo Installing update...");
                writer.println("start /wait msiexec /i \"" + installer.getAbsolutePath() + "\" /passive");
                writer.println("echo Restarting application...");
                writer.println("start \"\" \"" + currentExe + "\"");
                writer.println("del \"%~f0\""); // Delete the batch script itself
            }
            
            // Run the batch script in the background
            new ProcessBuilder("cmd", "/c", "start", "/min", "cmd", "/c", updateScript.getAbsolutePath()).start();
            System.exit(0);
        } catch (Exception e) {
            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Failed to launch installer: " + e.getMessage()).show());
        }
    }
}

