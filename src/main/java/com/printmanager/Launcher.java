package com.printmanager;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

public class Launcher {
    public static JWindow splash;

    public static void main(String[] args) {
        boolean showSplash = true;
        for (String arg : args) {
            if (arg.contains("--type=")) {
                showSplash = false;
                break;
            }
        }

        if (showSplash) {
            showSplashScreen();
        }
        App.main(args);
    }

    private static void showSplashScreen() {
        splash = new JWindow();
        splash.setSize(500, 300);
        splash.setLocationRelativeTo(null);
        splash.setBackground(new Color(0, 0, 0, 0)); // Transparent background for rounded corners

        JPanel panel = new JPanel() {
            private float angle = 0;
            private float breathe = 0;

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2d = (Graphics2D) g.create();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Draw rounded background
                RoundRectangle2D roundedRectangle = new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 30, 30);
                g2d.setClip(roundedRectangle);

                // Gradient background
                GradientPaint gp = new GradientPaint(0, 0, new Color(41, 128, 185), getWidth(), getHeight(), new Color(109, 213, 250));
                g2d.setPaint(gp);
                g2d.fillRect(0, 0, getWidth(), getHeight());

                // Center the graphics context for the flower
                g2d.translate(getWidth() / 2, 100);

                // Breathing & Slow Rotation Effect for the Magnolia
                float scale = 1.0f + 0.08f * (float)Math.sin(Math.toRadians(breathe));
                g2d.scale(scale, scale);
                g2d.rotate(Math.toRadians(angle));

                // Magnolia Petal path definition
                Path2D.Float petal = new Path2D.Float();
                petal.moveTo(0, 0); // Base of the petal
                petal.curveTo(18, -15, 22, -45, 0, -55); // Right arc up to the tip
                petal.curveTo(-22, -45, -18, -15, 0, 0); // Left arc down to the base

                // Draw the Magnolia Flower (8 overlapping petals)
                int numPetals = 8;
                for (int i = 0; i < numPetals; i++) {
                    // Outer Petal Fill (White)
                    g2d.setColor(new Color(255, 255, 255, 220));
                    g2d.fill(petal);
                    
                    // Subtle Petal Outline/Shadow
                    g2d.setColor(new Color(200, 220, 240, 150));
                    g2d.setStroke(new BasicStroke(1.5f));
                    g2d.draw(petal);
                    
                    g2d.rotate(Math.toRadians(360.0 / numPetals));
                }

                // Inner layered smaller petals for depth
                g2d.scale(0.6, 0.6);
                g2d.rotate(Math.toRadians(22.5)); // Offset slightly
                for (int i = 0; i < numPetals; i++) {
                    g2d.setColor(new Color(255, 250, 250, 240));
                    g2d.fill(petal);
                    g2d.setColor(new Color(200, 220, 240, 100));
                    g2d.draw(petal);
                    g2d.rotate(Math.toRadians(360.0 / numPetals));
                }

                // Magnolia Center Carpel (Golden/Yellow gradient)
                GradientPaint centerGradient = new GradientPaint(-10, -10, new Color(255, 223, 0), 10, 10, new Color(212, 175, 55));
                g2d.setPaint(centerGradient);
                g2d.fillOval(-12, -12, 24, 24);

                // Update animation variables
                angle += 1.5; // Slow rotation
                breathe += 5.0; // Breathing speed
                
                if (angle >= 360) angle = 0;
                if (breathe >= 360) breathe = 0;

                g2d.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BorderLayout());

        JLabel title = new JLabel("Smart QP Print Manager", SwingConstants.CENTER);
        title.setFont(new Font("Segoe UI", Font.BOLD, 28));
        title.setForeground(Color.WHITE);
        title.setBorder(BorderFactory.createEmptyBorder(170, 0, 10, 0));

        JLabel status = new JLabel("Waking up the printing hamsters...", SwingConstants.CENTER);
        status.setFont(new Font("Segoe UI", Font.ITALIC, 14));
        status.setForeground(new Color(255, 255, 255, 200));
        status.setBorder(BorderFactory.createEmptyBorder(0, 0, 5, 0));

        JLabel branding = new JLabel("Product of Magnolia Creations", SwingConstants.CENTER);
        branding.setFont(new Font("Segoe UI", Font.BOLD, 12));
        branding.setForeground(new Color(255, 255, 255, 150));
        branding.setBorder(BorderFactory.createEmptyBorder(0, 0, 20, 0));

        panel.add(title, BorderLayout.CENTER);
        
        JPanel southPanel = new JPanel(new GridLayout(2, 1));
        southPanel.setOpaque(false);
        southPanel.add(status);
        southPanel.add(branding);
        panel.add(southPanel, BorderLayout.SOUTH);

        splash.setContentPane(panel);
        splash.setVisible(true);

        // Satirical loading messages
        String[] messages = {
            "Waking up the printing hamsters...",
            "Negotiating with paper jams...",
            "Extracting quantum ink from the ether...",
            "Bypassing university server rate limits...",
            "Decrypting Question Papers (Just kidding!)...",
            "Convincing the printer not to sleep...",
            "Reticulating splines...",
            "Polishing the UI pixels...",
            "Firing up the Chromium engine...",
            "Loading... Almost there... Probably..."
        };

        Timer timer = new Timer(40, e -> {
            panel.repaint();
        });
        timer.start();

        Timer textTimer = new Timer(1500, e -> {
            int idx = (int) (Math.random() * messages.length);
            status.setText(messages[idx]);
        });
        textTimer.start();
    }
}
