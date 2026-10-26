package com.bervan.filestorage;

import com.bervan.filestorage.dto.ByteRange;
import com.bervan.filestorage.dto.MoveFilesRequest;
import com.bervan.filestorage.dto.TrashConflictResponse;
import com.bervan.filestorage.model.DownloadItem;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.TrashConflictException;
import com.bervan.filestorage.model.TrashZipConflictException;
import com.bervan.filestorage.service.FileEncryptionService;
import com.bervan.filestorage.service.FileServiceManager;
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

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
public class FileStorageApiController {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp");
    private static final int DEFAULT_ZIP_BUFFER_SIZE = 64 * 1024; // 64 KB buffer
    private static final int STREAM_CHUNK_BUFFER_SIZE = 1024 * 1024; // 1 MB chunk buffer
    private static final double DEFAULT_THUMBNAIL_MAX_SIZE = 200.0;
    private static final long THUMBNAIL_CACHE_MAX_AGE_SECONDS = 86400L;
    private static final String THUMBNAIL_CACHE_CONTROL = "max-age=" + THUMBNAIL_CACHE_MAX_AGE_SECONDS + ", public";
    private static final String DEFAULT_IMAGE_FORMAT = "jpeg";
    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";
    private static final String APPLICATION_ZIP_VALUE = "application/zip";
    private static final String BYTES_UNIT = "bytes";
    private static final String SESSION_KEY_PREFIX = "enc_key_";
    private static final String SESSION_IV_PREFIX = "enc_iv_";

    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final FileEncryptionService fileEncryptionService;
    private final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    private final Map<UUID, DownloadItem> fileCache = new ConcurrentHashMap<>();

    public FileStorageApiController(FileServiceManager fileServiceManager,
                                    FileEncryptionService fileEncryptionService,
                                    LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB) {
        this.fileServiceManager = fileServiceManager;
        this.fileEncryptionService = fileEncryptionService;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
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
                    response.getMetadata().forEach(m -> saved.add(toDto(m)));
                } else {
                    var response = fileServiceManager.save(file, description, path, overwriteTrash);
                    response.getMetadata().forEach(m -> saved.add(toDto(m)));
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
                .map(this::toDto)
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
            return ResponseEntity.ok(toDto(dir));
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
        return ResponseEntity.ok(dirs.stream().map(this::toDto).collect(Collectors.toList()));
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
            session.setAttribute(SESSION_KEY_PREFIX + id, key);
            session.setAttribute(SESSION_IV_PREFIX + id, m.getEncryptionIv());
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
        log.info("Getting thumbnail for file {}", uuid);
        Metadata metadata = fileServiceManager.getMetadata(uuid);
        Path file = fileServiceManager.getFile(uuid);
        String filename = file.getFileName().toString().toLowerCase();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "";

        if (IMAGE_EXTENSIONS.contains(ext)) {
            return getResponseEntityForImages(uuid, session, metadata, file, scale, maxSize);
        } else {
            return getResponseEntityForNonImages(uuid, session, metadata, file);
        }
    }

    @PostMapping("/create-stream-download")
    public ResponseEntity<UUID> createStreamDownload(@RequestParam UUID metadataUuid) {
        log.info("CREATE STREAM DOWNLOAD START metadataUuid={}", metadataUuid);
        try {
            Metadata metadata = fileServiceManager.getMetadata(metadataUuid);
            log.info("Metadata loaded id={}, filename={}, path={}, size={}, encrypted={}",
                    metadata.getId(), metadata.getFilename(), metadata.getPath(), metadata.getFileSize(), metadata.isEncrypted());

            UUID downloadId = UUID.randomUUID();
            DownloadItem item = new DownloadItem(metadata);
            fileCache.put(downloadId, item);

            log.info("Download item created downloadId={}, cacheSize={}", downloadId, fileCache.size());
            return ResponseEntity.ok(downloadId);
        } catch (Exception e) {
            log.error("CREATE STREAM DOWNLOAD FAILED metadataUuid={}", metadataUuid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("/stream-download")
    @PermitAll
    public ResponseEntity<StreamingResponseBody> streamDownload(
            @RequestParam UUID downloadItemUuid,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader)
            throws IOException {

        DownloadItem item = getValidDownloadItem(downloadItemUuid);
        if (item == null) {
            return ResponseEntity.notFound().build();
        }

        Metadata metadata = item.getMetadata();
        Path file = fileServiceManager.getFile(metadata);
        long fileSize = Files.size(file);
        String mimeType = guessMimeType(metadata.getFilename());

        if (rangeHeader == null) {
            return createFullStreamResponse(metadata, file, fileSize, mimeType);
        } else {
            ByteRange byteRange = ByteRange.parse(rangeHeader, fileSize);
            return createPartialStreamResponse(metadata, file, fileSize, mimeType, byteRange);
        }
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

    private DownloadItem getValidDownloadItem(UUID downloadItemUuid) {
        DownloadItem item = fileCache.get(downloadItemUuid);
        if (item == null) {
            log.error("DOWNLOAD ITEM NOT FOUND uuid={}", downloadItemUuid);
            return null;
        }
        if (item.expired()) {
            log.error("DOWNLOAD ITEM EXPIRED uuid={}", downloadItemUuid);
            fileCache.remove(downloadItemUuid);
            return null;
        }
        return item;
    }

    private ResponseEntity<StreamingResponseBody> createFullStreamResponse(
            Metadata metadata, Path file, long fileSize, String mimeType) {
        log.info("NORMAL DOWNLOAD mode filename={}", metadata.getFilename());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .contentLength(fileSize)
                .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + metadata.getFilename() + "\"")
                .body(createFullStreamingBody(file, metadata.getFilename()));
    }

    private ResponseEntity<StreamingResponseBody> createPartialStreamResponse(
            Metadata metadata, Path file, long fileSize, String mimeType, ByteRange byteRange) {
        log.info("RANGE DOWNLOAD mode range={}-{}, contentLength={}",
                byteRange.start(), byteRange.end(), byteRange.contentLength());
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_RANGE, byteRange.toContentRangeHeader(fileSize))
                .header(HttpHeaders.ACCEPT_RANGES, BYTES_UNIT)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + metadata.getFilename() + "\"")
                .contentLength(byteRange.contentLength())
                .body(createRangeStreamingBody(file, byteRange));
    }

    private StreamingResponseBody createFullStreamingBody(Path file, String filename) {
        return outputStream -> {
            log.info("STREAM START filename={}", filename);
            try (InputStream input = Files.newInputStream(file)) {
                long copied = input.transferTo(outputStream);
                log.info("STREAM FINISHED filename={}, bytesSent={}", filename, copied);
            } catch (Exception e) {
                log.error("STREAM FAILED filename={}", filename, e);
                throw e;
            }
        };
    }

    private StreamingResponseBody createRangeStreamingBody(Path file, ByteRange range) {
        return outputStream -> {
            log.info("RANGE STREAM START start={}, end={}", range.start(), range.end());
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(range.start());
                byte[] buffer = new byte[STREAM_CHUNK_BUFFER_SIZE];
                long remaining = range.contentLength();
                long sent = 0;

                while (remaining > 0) {
                    int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) {
                        break;
                    }
                    outputStream.write(buffer, 0, read);
                    sent += read;
                    remaining -= read;
                }
                log.info("RANGE STREAM FINISHED sent={}, expected={}", sent, range.contentLength());
            } catch (Exception e) {
                log.error("RANGE STREAM FAILED", e);
                throw e;
            }
        };
    }

    private ResponseEntity<byte[]> getResponseEntityForNonImages(UUID uuid, HttpSession session, Metadata metadata, Path file) throws IOException {
        byte[] fileBytes = getFileBytes(metadata, file, session, uuid);
        if (fileBytes == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String mimeType = guessMimeType(metadata.getFilename());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(fileBytes.length))
                .body(fileBytes);
    }

    private ResponseEntity<byte[]> getResponseEntityForImages(UUID uuid, HttpSession session, Metadata metadata, Path file, boolean shouldScale, Double maxSizeRequested) throws IOException {
        byte[] fileBytes = getFileBytes(metadata, file, session, uuid);
        if (fileBytes == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        BufferedImage original = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (original == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] renderedBytes = scaleAndRenderImage(original, shouldScale, maxSizeRequested);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, THUMBNAIL_CACHE_CONTROL)
                .body(renderedBytes);
    }

    private byte[] scaleAndRenderImage(BufferedImage original, boolean shouldScale, Double maxSizeRequested) throws IOException {
        int width = original.getWidth();
        int height = original.getHeight();

        if (shouldScale) {
            double maxSize = (maxSizeRequested != null && maxSizeRequested > 0)
                    ? maxSizeRequested
                    : DEFAULT_THUMBNAIL_MAX_SIZE;
            double scale = Math.min(maxSize / width, maxSize / height);
            width = Math.max(1, (int) (width * scale));
            height = Math.max(1, (int) (height * scale));
        }

        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        try {
            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, width, height);
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.drawImage(original, 0, 0, width, height, null);
        } finally {
            g2d.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaled, DEFAULT_IMAGE_FORMAT, baos);
        return baos.toByteArray();
    }

    private byte[] getFileBytes(Metadata metadata, Path file, HttpSession session, UUID uuid) throws IOException {
        if (metadata.isEncrypted()) {
            String ivHex = (String) session.getAttribute(SESSION_IV_PREFIX + uuid);
            byte[] keyBytes = (byte[]) session.getAttribute(SESSION_KEY_PREFIX + uuid);
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
        if (filename == null) return DEFAULT_MIME_TYPE;
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
        return DEFAULT_MIME_TYPE;
    }

    private MetadataDto toDto(Metadata m) {
        return MetadataDto.from(m);
    }
}
