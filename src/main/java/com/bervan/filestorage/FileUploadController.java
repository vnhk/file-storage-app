package com.bervan.filestorage;

import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.UUID;

@RestController
public class FileUploadController {

    private final FileServiceManager fileServiceManager;

    public FileUploadController(FileServiceManager fileServiceManager) {
        this.fileServiceManager = fileServiceManager;
    }

    @PostMapping(path = "/file-storage-app/files/upload-file", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE})
    public ResponseEntity<UploadResponse> uploadFile(@RequestParam("file") MultipartFile file, @RequestParam String description, @RequestParam String path) throws IOException {
        return new ResponseEntity<>(fileServiceManager.save(file, description, path), HttpStatus.OK);
    }

    @GetMapping("/file-storage-app/files/download")
    public ResponseEntity<UrlResource> downloadFile(@RequestParam UUID uuid, OutputStream out, HttpServletResponse response) throws IOException {
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

//    @GetMapping("/backup/download")
//    public void downloadBackupFile(OutputStream out, HttpServletResponse response) throws IOException, InterruptedException {
//        response.addHeader("Accept-Ranges", "bytes");
//        Path file = fileServiceManager.doBackup();
//        Files.copy(file, out);
//    }
}