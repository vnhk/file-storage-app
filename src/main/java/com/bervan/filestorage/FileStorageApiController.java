package com.bervan.filestorage;

import com.bervan.filestorage.dto.MoveFilesRequest;
import com.bervan.filestorage.dto.TrashConflictResponse;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.TrashConflictException;
import com.bervan.filestorage.model.TrashZipConflictException;
import com.bervan.filestorage.service.FileEncryptionService;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.filestorage.service.FileStorageApiHelper;
import com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB;
import com.bervan.logging.JsonLogger;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
public class FileStorageApiController {

    private static final int DEFAULT_ZIP_BUFFER_SIZE = 64 * 1024; // 64 KB buffer
    private static final String APPLICATION_ZIP_VALUE = "application/zip";
    private static final String BYTES_UNIT = "bytes";

    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final FileEncryptionService fileEncryptionService;
    private final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    private final FileStorageApiHelper fileStorageApiHelper;

    public FileStorageApiController(FileServiceManager fileServiceManager,
                                    FileEncryptionService fileEncryptionService,
                                    LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB,
                                    FileStorageApiHelper fileStorageApiHelper) {
        this.fileServiceManager = fileServiceManager;
        this.fileEncryptionService = fileEncryptionService;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        this.fileStorageApiHelper = fileStorageApiHelper;
    }

    @GetMapping
    public ResponseEntity<List<MetadataDto>> list(@RequestParam(defaultValue = "/") String path) {
        Set<Metadata> items = fileServiceManager.loadByPath(path);
        List<MetadataDto> result = items.stream()
                .sorted(Comparator.comparing(Metadata::isDirectory).reversed()
                        .thenComparing(m -> m.getFilename().toLowerCase()))
                .map(MetadataDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam(defaultValue = "/") String path,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "false") boolean extract,
            @RequestParam(defaultValue = "false") boolean overwriteTrash,
            @RequestParam("files") List<MultipartFile> files
    ) {
        List<MetadataDto> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            try {
                String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
                if (extract && name.toLowerCase().endsWith(".zip")) {
                    var response = fileServiceManager.saveAndExtractZip(file, description, path, overwriteTrash);
                    response.getMetadata().forEach(m -> saved.add(MetadataDto.from(m)));
                } else {
                    var response = fileServiceManager.save(file, description, path, overwriteTrash);
                    response.getMetadata().forEach(m -> saved.add(MetadataDto.from(m)));
                }
            } catch (TrashConflictException e) {
                TrashConflictResponse response = TrashConflictResponse.ofSingleConflict(
                        e.getMetadataId().toString(),
                        e.getFilename(),
                        e.getPath(),
                        e.getMessage()
                );
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            } catch (TrashZipConflictException e) {
                TrashConflictResponse response = TrashConflictResponse.ofZipConflict(
                        e.getConflicts(),
                        e.getMessage()
                );
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            } catch (Exception e) {
                log.error("Failed to upload file", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
        }
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/trash")
    public ResponseEntity<List<MetadataDto>> listTrash() {
        Set<Metadata> items = fileServiceManager.loadTrash();
        List<MetadataDto> result = items.stream()
                .sorted(Comparator.comparing(Metadata::getInTrashStartDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(MetadataDto::from)
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/trash/restore")
    public ResponseEntity<Void> restoreFromTrash(@RequestBody List<UUID> ids) {
        try {
            for (UUID id : ids) {
                Metadata m = fileServiceManager.getMetadata(id);
                if (m != null) {
                    fileServiceManager.restoreFromTrash(m);
                }
            }
            return ResponseEntity.ok().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (Exception e) {
            log.error("Failed to restore items from trash", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/trash/permanent")
    public ResponseEntity<Void> deletePermanently(@RequestBody List<UUID> ids) {
        try {
            for (UUID id : ids) {
                Metadata m = fileServiceManager.getMetadata(id);
                if (m != null) {
                    fileServiceManager.deletePermanently(m);
                }
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to permanently delete items", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/trash/empty")
    public ResponseEntity<Void> emptyTrash() {
        try {
            fileServiceManager.emptyTrash();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to empty trash", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/folder")
    public ResponseEntity<MetadataDto> createFolder(
            @RequestParam(defaultValue = "/") String path,
            @RequestParam String name
    ) {
        try {
            Metadata dir = fileServiceManager.createEmptyDirectory(path, name);
            return ResponseEntity.ok(MetadataDto.from(dir));
        } catch (Exception e) {
            log.error("Failed to create folder", e);
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
            log.error("Failed to delete item: " + id, e);
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
            log.error("Failed to rename item: " + id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/move")
    public ResponseEntity<Void> moveAll(@RequestBody MoveFilesRequest request,
                                        @RequestParam(required = false) String destPath) {
        try {
            String targetPath = destPath != null && !destPath.isBlank() ? destPath : request.getDestPath();
            if (targetPath == null || request.getIds() == null) {
                return ResponseEntity.badRequest().build();
            }
            for (UUID id : request.getIds()) {
                Metadata m = fileServiceManager.getMetadata(id);
                if (m != null) {
                    fileServiceManager.moveFileToPath(m, targetPath);
                }
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to move items", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/directories")
    public ResponseEntity<List<MetadataDto>> listDirectories(@RequestParam(defaultValue = "/") String path) {
        List<Metadata> dirs = fileServiceManager.getDirectoriesInPath(path);
        return ResponseEntity.ok(dirs.stream().map(MetadataDto::from).collect(Collectors.toList()));
    }

    @PostMapping("/{id}/calculate-size")
    public ResponseEntity<Long> calculateFolderSize(@PathVariable UUID id) {
        try {
            Long totalSize = fileServiceManager.calculateAndUpdateFolderSize(id);
            return ResponseEntity.ok(totalSize);
        } catch (Exception e) {
            log.error("Failed to calculate folder size for: " + id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
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
            fileStorageApiHelper.saveEncryptionKeysToSession(session, id, key, m.getEncryptionIv());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to unlock file: " + id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PutMapping("/{id}/content")
    public ResponseEntity<Void> updateContent(@PathVariable UUID id, @RequestBody String content) {
        try {
            Metadata m = fileServiceManager.getMetadata(id);
            fileServiceManager.updateFileContent(m, content.getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to update file content: " + id, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/sync")
    public ResponseEntity<Void> sync() {
        try {
            loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Failed to synchronize storage with DB", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/download-zip")
    public void downloadZip(@RequestBody List<UUID> ids, HttpServletResponse response) throws IOException {
        response.setContentType(APPLICATION_ZIP_VALUE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"files.zip\"");
        try (BufferedOutputStream bos = new BufferedOutputStream(response.getOutputStream(), DEFAULT_ZIP_BUFFER_SIZE);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_SPEED);
            fileServiceManager.addFilesToZip(ids, zos);
            zos.flush();
        }
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(
            @RequestParam UUID uuid,
            @RequestParam(required = false, defaultValue = "true") boolean scale,
            @RequestParam(required = false) Double maxSize,
            HttpSession session) throws IOException {
        return fileStorageApiHelper.getThumbnail(uuid, scale, maxSize, session);
    }

    @PostMapping("/create-stream-download")
    public ResponseEntity<UUID> createStreamDownload(@RequestParam UUID metadataUuid) {
        return fileStorageApiHelper.createStreamDownload(metadataUuid);
    }

    @GetMapping("/stream-download")
    @PermitAll
    public ResponseEntity<StreamingResponseBody> streamDownload(
            @RequestParam UUID downloadItemUuid,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
            throws IOException {
        return fileStorageApiHelper.streamDownload(downloadItemUuid, rangeHeader);
    }

    @GetMapping("/download")
    public ResponseEntity<UrlResource> downloadFile(@RequestParam UUID uuid, HttpServletResponse response) throws IOException {
        response.addHeader(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT);
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
