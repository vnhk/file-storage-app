package com.bervan.filestorage;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileEncryptionService;
import com.bervan.filestorage.service.FileServiceManager;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
public class FileStorageApiController {

    private final FileServiceManager fileServiceManager;
    private final FileEncryptionService fileEncryptionService;
    private final com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;

    public FileStorageApiController(FileServiceManager fileServiceManager,
                                    FileEncryptionService fileEncryptionService,
                                    com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB) {
        this.fileServiceManager = fileServiceManager;
        this.fileEncryptionService = fileEncryptionService;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
    }

    private MetadataDto toDto(Metadata m) {
        return new MetadataDto(
                m.getId() != null ? m.getId().toString() : null,
                m.getFilename(),
                m.getPath(),
                m.isDirectory(),
                m.getExtension(),
                m.getFileSize(),
                m.isEncrypted(),
                m.getModificationDate() != null ? m.getModificationDate().toString() : null
        );
    }

    @GetMapping
    public ResponseEntity<List<MetadataDto>> list(@RequestParam(defaultValue = "/") String path) {
        Set<Metadata> items = fileServiceManager.loadByPath(path);
        List<MetadataDto> result = items.stream()
                .sorted(Comparator.comparing(Metadata::isDirectory).reversed()
                        .thenComparing(m -> m.getFilename().toLowerCase()))
                .map(this::toDto)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<List<MetadataDto>> upload(
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "false") boolean extract,
            @RequestParam("files") List<MultipartFile> files
    ) {
        List<MetadataDto> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
                if (extract && name.toLowerCase().endsWith(".zip")) {
                    var response = fileServiceManager.saveAndExtractZip(file, description, path);
                    response.getMetadata().forEach(m -> saved.add(toDto(m)));
                } else {
                    var response = fileServiceManager.save(file, description, path);
                    response.getMetadata().forEach(m -> saved.add(toDto(m)));
                }
            } catch (Exception e) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        return ResponseEntity.ok(saved);
    }

    @PostMapping("/folder")
    public ResponseEntity<MetadataDto> createFolder(
            @RequestParam(defaultValue = "/") String path,
            @RequestParam String name
    ) {
        try {
            Metadata dir = fileServiceManager.createEmptyDirectory(path, name);
            return ResponseEntity.ok(toDto(dir));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        try {
            Metadata m = fileServiceManager.getMetadata(id);
            fileServiceManager.delete(m);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PatchMapping("/{id}/rename")
    public ResponseEntity<Void> rename(@PathVariable UUID id, @RequestParam String name) {
        try {
            Metadata m = fileServiceManager.getMetadata(id);
            fileServiceManager.renameFile(m, name);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/{id}/move")
    public ResponseEntity<Void> move(@PathVariable UUID id, @RequestParam String destPath) {
        try {
            Metadata m = fileServiceManager.getMetadata(id);
            fileServiceManager.moveFileToPath(m, destPath);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/directories")
    public ResponseEntity<List<MetadataDto>> listDirectories(@RequestParam(defaultValue = "/") String path) {
        List<Metadata> dirs = fileServiceManager.getDirectoriesInPath(path);
        return ResponseEntity.ok(dirs.stream().map(this::toDto).collect(Collectors.toList()));
    }

    @PostMapping("/{id}/unlock")
    public ResponseEntity<Void> unlockFile(@PathVariable UUID id,
                                           @RequestParam String password,
                                           HttpSession session) {
        try {
            Metadata m = fileServiceManager.getMetadata(id);
            if (!m.isEncrypted()) return ResponseEntity.badRequest().build();
            byte[] key = fileEncryptionService.deriveKey(password, m.getEncryptionSalt());
            boolean valid = fileEncryptionService.verifyPassword(key, m.getEncryptionIv(), m.getEncryptionVerifier());
            if (!valid) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            session.setAttribute("enc_key_" + id, key);
            session.setAttribute("enc_iv_" + id, m.getEncryptionIv());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{id}/content")
    public ResponseEntity<Void> updateContent(@PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody String content) {
        try {
            Metadata m = fileServiceManager.getMetadata(id);
            fileServiceManager.updateFileContent(m, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> sync() {
        try {
            loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/download-zip")
    public void downloadZip(@RequestBody List<UUID> ids, HttpServletResponse response) throws IOException {
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"files.zip\"");
        try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
            for (UUID id : ids) {
                try {
                    Metadata m = fileServiceManager.getMetadata(id);
                    if (m.isDirectory()) {
                        addFolderToZip(m, m.getFilename(), zos);
                    } else {
                        addFileToZip(m, m.getFilename(), zos);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void addFolderToZip(Metadata folder, String basePathInZip, ZipOutputStream zos) throws IOException {
        // Add an entry for the directory itself (ensures empty dirs are preserved)
        String dirEntryName = basePathInZip.endsWith("/") ? basePathInZip : basePathInZip + "/";
        zos.putNextEntry(new ZipEntry(dirEntryName));
        zos.closeEntry();

        String p = folder.getPath() != null ? folder.getPath() : "/";
        String sep = "/".equals(p) ? "" : "/";
        if (p.endsWith("/")) {
            sep = "";
        }
        String childPath = p + sep + folder.getFilename();

        // Load immediate children and recurse
        Set<Metadata> children = fileServiceManager.loadByPath(childPath + "/");
        if (children == null || children.isEmpty()) {
            return;
        }

        for (Metadata child : children) {
            String entryName = dirEntryName + child.getFilename();
            if (child.isDirectory()) {
                addFolderToZip(child, entryName, zos);
            } else {
                addFileToZip(child, entryName, zos);
            }
        }
    }

    private void addFileToZip(Metadata fileMeta, String entryName, ZipOutputStream zos) throws IOException {
        Path file = fileServiceManager.getFile(fileMeta.getId());
        zos.putNextEntry(new ZipEntry(entryName));
        java.nio.file.Files.copy(file, zos);
        zos.closeEntry();
    }

    public record MetadataDto(
            String id,
            String filename,
            String path,
            boolean directory,
            String extension,
            Long fileSize,
            boolean encrypted,
            String modificationDate
    ) {
    }
}
