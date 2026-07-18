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
    private static final double CURRENT_VERSION = 7.5;

    public static void checkForUpdates() {
        new Thread(() -> {
            try {
                System.out.println("AutoUpdater checking for updates...");
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GITHUB_API_URL))
                        .header("Accept", "application/vnd.github.v3+json")
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("AutoUpdater GitHub API Response code: " + response.statusCode());

                if (response.statusCode() == 200) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(response.body());
                    String tagName = root.path("tag_name").asText();
                    double latestVersion = parseVersion(tagName);
                    System.out.println("AutoUpdater Parsed latestVersion: " + latestVersion);

                    if (latestVersion > CURRENT_VERSION) {
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

    private static double parseVersion(String tag) {
        try {
            return Double.parseDouble(tag.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return 0;
        }
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

    private static void promptUserForUpdate(double newVersion, String downloadUrl) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Update Available");
        alert.setHeaderText("A new version is available!");
        alert.setContentText("Version " + newVersion + " has been released. Would you like to download and install it now?");

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            downloadAndInstallUpdate(downloadUrl);
        }
    }

    private static void downloadAndInstallUpdate(String downloadUrl) {
        Alert progressAlert = new Alert(Alert.AlertType.INFORMATION);
        progressAlert.setTitle("Downloading Update");
        progressAlert.setHeaderText("Please wait, downloading update...");
        
        ProgressIndicator progressIndicator = new ProgressIndicator();
        VBox vbox = new VBox(progressIndicator);
        vbox.setAlignment(javafx.geometry.Pos.CENTER);
        progressAlert.getDialogPane().setContent(vbox);
        progressAlert.getButtonTypes().clear();
        progressAlert.show();

        new Thread(() -> {
            try {
                File tempFile = new File(System.getProperty("java.io.tmpdir"), "SmartQPPrintManager_Update.msi");
                
                HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(downloadUrl)).build();
                
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
                
                try (InputStream is = response.body(); FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long totalRead = 0;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        if (contentLength > 0) {
                            double progress = (double) totalRead / contentLength;
                            Platform.runLater(() -> progressIndicator.setProgress(progress));
                        }
                    }
                }

                Platform.runLater(progressAlert::close);
                installUpdate(tempFile);

            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressAlert.close();
                    new Alert(Alert.AlertType.ERROR, "Update failed: " + e.getMessage()).show();
                });
            }
        }).start();
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

