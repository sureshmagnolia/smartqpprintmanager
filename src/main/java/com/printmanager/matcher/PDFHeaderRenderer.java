package com.printmanager.matcher;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance PDF Header Preview Renderer.
 * Renders the top portion (header, degree, QP code, title) of page 1 of a Question Paper PDF.
 */
public class PDFHeaderRenderer {
    private static final Logger logger = LoggerFactory.getLogger(PDFHeaderRenderer.class);
    private static final Map<String, Image> PREVIEW_CACHE = new ConcurrentHashMap<>();

    /**
     * Renders top 35% of page 0 of the PDF asynchronously.
     */
    public static CompletableFuture<Image> renderHeaderAsync(File pdfFile) {
        return CompletableFuture.supplyAsync(() -> renderHeaderSync(pdfFile));
    }

    /**
     * Synchronously renders the top 35% crop of page 0.
     */
    public static Image renderHeaderSync(File pdfFile) {
        if (pdfFile == null || !pdfFile.exists() || !pdfFile.canRead()) {
            return null;
        }

        String cacheKey = pdfFile.getAbsolutePath() + "_" + pdfFile.lastModified();
        Image cached = PREVIEW_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try (PDDocument doc = Loader.loadPDF(pdfFile)) {
            if (doc.getNumberOfPages() == 0) return null;

            PDFRenderer renderer = new PDFRenderer(doc);
            // Render full page 1 at 1.8x scale for razor-sharp reading across full width
            BufferedImage fullPage = renderer.renderImage(0, 1.8f);
            
            WritableImage fxImage = SwingFXUtils.toFXImage(fullPage, null);
            PREVIEW_CACHE.put(cacheKey, fxImage);
            return fxImage;
        } catch (Exception e) {
            logger.warn("Failed to render PDF header preview for {}: {}", pdfFile.getName(), e.getMessage());
            return null;
        }
    }

    public static void clearCache() {
        PREVIEW_CACHE.clear();
    }
}

