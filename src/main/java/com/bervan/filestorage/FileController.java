package com.bervan.filestorage;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileEncryptionService;
import com.bervan.filestorage.service.FileServiceManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

@RestController
public class FileController {

    private final FileServiceManager fileServiceManager;
    private final FileEncryptionService fileEncryptionService;

    public FileController(FileServiceManager fileServiceManager, FileEncryptionService fileEncryptionService) {
        this.fileServiceManager = fileServiceManager;
        this.fileEncryptionService = fileEncryptionService;
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

    @GetMapping("/file-storage-app/files/stream")
    public void streamEncryptedFile(@RequestParam UUID uuid,
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    HttpSession session) throws IOException {
        byte[] keyBytes = (byte[]) session.getAttribute("enc_key_" + uuid);
        String ivHex = (String) session.getAttribute("enc_iv_" + uuid);
        if (keyBytes == null || ivHex == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Session key not found — please enter password");
            return;
        }

        Metadata metadata = fileServiceManager.getMetadata(uuid);
        Path file = fileServiceManager.getFile(uuid);
        long fileSize = metadata.getFileSize() != null ? metadata.getFileSize() : Files.size(file);

        String rangeHeader = request.getHeader("Range");
        long rangeStart = 0;
        long rangeEnd = fileSize - 1;

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] parts = rangeHeader.substring(6).split("-");
            if (parts.length > 0 && !parts[0].isEmpty()) rangeStart = Long.parseLong(parts[0]);
            if (parts.length > 1 && !parts[1].isEmpty()) rangeEnd = Long.parseLong(parts[1]);
        }
        rangeEnd = Math.min(rangeEnd, fileSize - 1);
        long contentLength = rangeEnd - rangeStart + 1;

        response.setStatus(rangeHeader != null ? HttpServletResponse.SC_PARTIAL_CONTENT : HttpServletResponse.SC_OK);
        response.setHeader("Content-Type", guessMimeType(metadata.getFilename()));
        response.setHeader("Content-Length", String.valueOf(contentLength));
        response.setHeader("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + fileSize);
        response.setHeader("Accept-Ranges", "bytes");

        try (InputStream decrypted = fileEncryptionService.createDecryptingStream(file, keyBytes, ivHex, rangeStart)) {
            byte[] buf = new byte[65536];
            long remaining = contentLength;
            int read;
            while (remaining > 0 && (read = decrypted.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                response.getOutputStream().write(buf, 0, read);
                remaining -= read;
            }
            response.getOutputStream().flush();
        } catch (Exception e) {
            if (!response.isCommitted()) response.sendError(500, "Decryption failed");
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