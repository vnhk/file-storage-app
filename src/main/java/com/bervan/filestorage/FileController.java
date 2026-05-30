package com.bervan.filestorage;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileEncryptionService;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.logging.JsonLogger;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class FileController {
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private final FileServiceManager fileServiceManager;
    private final FileEncryptionService fileEncryptionService;
    private JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");

    public FileController(FileServiceManager fileServiceManager, FileEncryptionService fileEncryptionService) {
        this.fileServiceManager = fileServiceManager;
        this.fileEncryptionService = fileEncryptionService;
    }

    @GetMapping("/file-storage-app/files/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@RequestParam UUID uuid, HttpSession session) throws IOException {
        log.info("Getting thumbnail for file {}", uuid);
        Metadata metadata = fileServiceManager.getMetadata(uuid);
        Path file = fileServiceManager.getFile(uuid);
        String filename = file.getFileName().toString().toLowerCase();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "";

        if (IMAGE_EXTENSIONS.contains(ext)) {
            // For images: generate scaled thumbnail
            byte[] fileBytes = getFileBytes(metadata, file, session, uuid);
            if (fileBytes == null) {
                return ResponseEntity.status(401).build();
            }

            BufferedImage original = ImageIO.read(new java.io.ByteArrayInputStream(fileBytes));
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
        } else {
            // For non-image files: return full file bytes
            byte[] fileBytes = getFileBytes(metadata, file, session, uuid);
            if (fileBytes == null) {
                return ResponseEntity.status(401).build();
            }

            String mimeType = guessMimeType(metadata.getFilename());

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileBytes.length))
                    .body(fileBytes);
        }
    }

    private byte[] getFileBytes(Metadata metadata, Path file, HttpSession session, UUID uuid) throws IOException {
        if (metadata.isEncrypted()) {
            String ivHex = (String) session.getAttribute("enc_iv_" + uuid);
            byte[] keyBytes = (byte[]) session.getAttribute("enc_key_" + uuid);
            if (keyBytes == null || ivHex == null) {
                return null;
            }
            try (InputStream decrypted = fileEncryptionService.createDecryptingStream(file, keyBytes, ivHex, 0)) {
                return decrypted.readAllBytes();
            } catch (Exception e) {
                throw new IOException("Decryption failed", e);
            }
        } else {
            return Files.readAllBytes(file);
        }
    }

    private String guessMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
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