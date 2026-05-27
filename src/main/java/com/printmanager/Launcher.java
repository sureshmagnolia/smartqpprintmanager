package com.printmanager;

import javax.swing.*;
import java.awt.*;
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

                // Draw spinning loading circle
                g2d.translate(getWidth() / 2, 100);
                g2d.rotate(Math.toRadians(angle));
                g2d.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2d.setColor(new Color(255, 255, 255, 150));
                g2d.drawArc(-30, -30, 60, 60, 0, 360);
                g2d.setColor(Color.WHITE);
                g2d.drawArc(-30, -30, 60, 60, 0, 100);

                angle += 8;
                if (angle >= 360) angle = 0;

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
        status.setBorder(BorderFactory.createEmptyBorder(0, 0, 40, 0));

        panel.add(title, BorderLayout.CENTER);
        panel.add(status, BorderLayout.SOUTH);

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

        Timer timer = new Timer(50, e -> {
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
