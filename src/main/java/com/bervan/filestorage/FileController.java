package com.bervan.filestorage;

import com.bervan.filestorage.service.FileServiceManager;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@RestController
public class FileController {

    private final FileServiceManager fileServiceManager;

    public FileController(FileServiceManager fileServiceManager) {
        this.fileServiceManager = fileServiceManager;
    }

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");

    @GetMapping("/file-storage-app/files/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@RequestParam UUID uuid) throws IOException {
        Path file = fileServiceManager.getFile(uuid);
        String filename = file.getFileName().toString().toLowerCase();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "";

        if (!IMAGE_EXTENSIONS.contains(ext)) {
            return ResponseEntity.notFound().build();
        }

        BufferedImage original = ImageIO.read(file.toFile());
        if (original == null) {
            return ResponseEntity.notFound().build();
        }

        int maxSize = 200;
        int w = original.getWidth();
        int h = original.getHeight();
        double scale = Math.min((double) maxSize / w, (double) maxSize / h);
        int newW = Math.max(1, (int) (w * scale));
        int newH = Math.max(1, (int) (h * scale));

        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, newW, newH);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, newW, newH, null);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpeg", baos);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400, public")
                .body(baos.toByteArray());
    }

    @GetMapping("/file-storage-app/files/download")
    public ResponseEntity<UrlResource> downloadFile(@RequestParam UUID uuid, HttpServletResponse response) throws IOException {
        response.addHeader("Accept-Ranges", "bytes");
        Path file = fileServiceManager.getFile(uuid);
        UrlResource urlResource = new UrlResource(file.toUri());

        String[] pathParts = file.getFileName().toString().split(File.separator);
        String filename = pathParts[pathParts.length - 1];

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(urlResource);
    }
}