package com.printmanager.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

public class Toast {
    public static void show(Stage ownerStage, String message, int delayMs) {
        Platform.runLater(() -> {
            if (ownerStage == null) return;
            Popup popup = new Popup();
            popup.setAutoFix(true);
            popup.setAutoHide(true);
            popup.setHideOnEscape(true);
            
            Label label = new Label(message);
            label.setStyle("-fx-background-color: rgba(0, 0, 0, 0.85); -fx-text-fill: white; " +
                           "-fx-padding: 12px 24px; -fx-background-radius: 25px; " +
                           "-fx-font-size: 14px; -fx-font-weight: bold; " +
                           "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 10, 0, 0, 4);");
                           
            popup.getContent().add(label);
            
            popup.setOnShown(e -> {
                popup.setX(ownerStage.getX() + ownerStage.getWidth() / 2 - popup.getWidth() / 2);
                popup.setY(ownerStage.getY() + ownerStage.getHeight() - popup.getHeight() - 70); // Bottom-center
            });
            
            popup.show(ownerStage);
            
            // Fade out animation
            Timeline timeline = new Timeline();
            KeyFrame fadeOutStart = new KeyFrame(Duration.millis(delayMs));
            KeyFrame fadeOutEnd = new KeyFrame(Duration.millis(delayMs + 500), new KeyValue(popup.opacityProperty(), 0.0));
            timeline.getKeyFrames().addAll(fadeOutStart, fadeOutEnd);
            timeline.setOnFinished(e -> popup.hide());
            timeline.play();
        });
    }
}
