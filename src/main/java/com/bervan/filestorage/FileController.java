package com.bervan.filestorage;

import com.bervan.filestorage.service.FileServiceManager;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.UUID;

@RestController
public class FileController {

    private final FileServiceManager fileServiceManager;

    public FileController(FileServiceManager fileServiceManager) {
        this.fileServiceManager = fileServiceManager;
    }

    @GetMapping("/file-storage-app/files/download")
    public void downloadFile(@RequestParam UUID uuid, HttpServletResponse response) throws IOException {
        Path file = fileServiceManager.getFile(uuid);
        if (!file.toFile().exists()) {
            throw new IllegalArgumentException("File not found: " + uuid);
        }

        String filename = file.getFileName().toString();

        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
        response.setHeader("Accept-Ranges", "bytes");

        try (OutputStream out = response.getOutputStream();
             var inputStream = java.nio.file.Files.newInputStream(file)) {
            byte[] buffer = new byte[81920];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        }
    }
}