package com.bervan.filestorage;

import com.bervan.filestorage.model.DownloadItem;
import com.bervan.filestorage.model.Metadata;
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

    @PostMapping("/move")
    public ResponseEntity<Void> moveAll(@RequestBody List<UUID> ids, @RequestParam String destPath) {
        try {
            for (UUID id : ids) {
                Metadata m = fileServiceManager.getMetadata(id);
                fileServiceManager.moveFileToPath(m, destPath);
            }
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
    public ResponseEntity<Void> updateContent(@PathVariable UUID id, @RequestBody String content) {
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
        try (BufferedOutputStream bos = new BufferedOutputStream(response.getOutputStream(), 65536);
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.setLevel(Deflater.BEST_SPEED);
            fileServiceManager.addFilesToZip(ids, zos);
            zos.flush();
        }
    }

    @GetMapping("/thumbnail")
    public ResponseEntity<byte[]> getThumbnail(@RequestParam UUID uuid, @RequestParam(required = false, defaultValue = "true") boolean scale, @RequestParam(required = false) Double maxSize, HttpSession session) throws IOException {
        log.info("Getting thumbnail for file {}", uuid);
        Metadata metadata = fileServiceManager.getMetadata(uuid);
        Path file = fileServiceManager.getFile(uuid);
        String filename = file.getFileName().toString().toLowerCase();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1) : "";

        if (IMAGE_EXTENSIONS.contains(ext)) {
            // For images: generate scaled thumbnail
            return getResponseEntityForImages(uuid, session, metadata, file, scale, maxSize);
        } else {
            return getResponseEntityForNonImages(uuid, session, metadata, file);
        }
    }

    @PostMapping("/create-stream-download")
    public ResponseEntity<UUID> createStreamDownload(
            @RequestParam UUID metadataUuid) {

        log.info("CREATE STREAM DOWNLOAD START metadataUuid={}", metadataUuid);

        try {
            Metadata metadata = fileServiceManager.getMetadata(metadataUuid);

            log.info(
                    "Metadata loaded id={}, filename={}, path={}, size={}, encrypted={}",
                    metadata.getId(),
                    metadata.getFilename(),
                    metadata.getPath(),
                    metadata.getFileSize(),
                    metadata.isEncrypted()
            );

            UUID downloadId = UUID.randomUUID();

            DownloadItem item = new DownloadItem(metadata);
            fileCache.put(downloadId, item);

            log.info(
                    "Download item created downloadId={}, cacheSize={}",
                    downloadId,
                    fileCache.size()
            );

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
            @RequestHeader(value = "Range", required = false) String rangeHeader)
            throws IOException {

        log.info(
                "STREAM DOWNLOAD START uuid={}, range={}",
                downloadItemUuid,
                rangeHeader
        );

        log.info(
                "Current cache size={}, containsKey={}",
                fileCache.size(),
                fileCache.containsKey(downloadItemUuid)
        );

        DownloadItem item = fileCache.get(downloadItemUuid);

        if (item == null) {
            log.error(
                    "DOWNLOAD ITEM NOT FOUND uuid={}",
                    downloadItemUuid
            );
            return ResponseEntity.notFound().build();
        }

        if (item.expired()) {
            log.error(
                    "DOWNLOAD ITEM EXPIRED uuid={}",
                    downloadItemUuid
            );
            return ResponseEntity.notFound().build();
        }


        Metadata metadata = item.getMetadata();

        log.info(
                "Metadata from download item filename={}, id={}, encrypted={}",
                metadata.getFilename(),
                metadata.getId(),
                metadata.isEncrypted()
        );


        Path file = fileServiceManager.getFile(metadata);

        log.info(
                "Physical file resolved path={}, exists={}, size={}",
                file,
                Files.exists(file),
                Files.exists(file) ? Files.size(file) : -1
        );


        long fileSize = Files.size(file);

        String mimeType = guessMimeType(metadata.getFilename());

        log.info(
                "Preparing stream filename={}, mimeType={}, fileSize={}",
                metadata.getFilename(),
                mimeType,
                fileSize
        );


        if (rangeHeader == null) {

            log.info("NORMAL DOWNLOAD mode");

            StreamingResponseBody stream = outputStream -> {

                log.info(
                        "STREAM START filename={}",
                        metadata.getFilename()
                );

                try (InputStream input = Files.newInputStream(file)) {
                    long copied = input.transferTo(outputStream);

                    log.info(
                            "STREAM FINISHED filename={}, bytesSent={}",
                            metadata.getFilename(),
                            copied
                    );
                } catch (Exception e) {
                    log.error(
                            "STREAM FAILED filename={}",
                            metadata.getFilename(),
                            e
                    );
                    throw e;
                }
            };


            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    .contentLength(fileSize)
                    .header(
                            HttpHeaders.ACCEPT_RANGES,
                            "bytes"
                    )
                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + metadata.getFilename() + "\""
                    )
                    .body(stream);
        }


        log.info(
                "RANGE DOWNLOAD mode range={}",
                rangeHeader
        );


        String[] ranges = rangeHeader.replace("bytes=", "").split("-");

        long start = Long.parseLong(ranges[0]);

        long end = ranges.length > 1 && !ranges[1].isEmpty()
                ? Long.parseLong(ranges[1])
                : fileSize - 1;


        long contentLength = end - start + 1;


        log.info(
                "Range parsed start={}, end={}, contentLength={}, fileSize={}",
                start,
                end,
                contentLength,
                fileSize
        );


        StreamingResponseBody stream = outputStream -> {

            log.info(
                    "RANGE STREAM START start={}, end={}",
                    start,
                    end
            );

            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {

                raf.seek(start);

                byte[] buffer = new byte[1024 * 1024];

                long remaining = contentLength;
                long sent = 0;


                while (remaining > 0) {

                    int read = raf.read(
                            buffer,
                            0,
                            (int) Math.min(buffer.length, remaining)
                    );


                    if (read == -1) {
                        break;
                    }


                    outputStream.write(buffer, 0, read);

                    sent += read;
                    remaining -= read;
                }


                log.info(
                        "RANGE STREAM FINISHED sent={}, expected={}",
                        sent,
                        contentLength
                );


            } catch (Exception e) {
                log.error(
                        "RANGE STREAM FAILED",
                        e
                );
                throw e;
            }
        };


        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.parseMediaType(mimeType))
                .header(
                        HttpHeaders.CONTENT_RANGE,
                        "bytes " + start + "-" + end + "/" + fileSize
                )
                .header(
                        HttpHeaders.ACCEPT_RANGES,
                        "bytes"
                )
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + metadata.getFilename() + "\""
                )
                .contentLength(contentLength)
                .body(stream);
    }

    @GetMapping("/download")
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


    private ResponseEntity<byte[]> getResponseEntityForNonImages(UUID uuid, HttpSession session, Metadata metadata, Path file) throws IOException {
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

    private ResponseEntity<byte[]> getResponseEntityForImages(UUID uuid, HttpSession session, Metadata metadata, Path file, boolean shouldScale, Double maxSizeRequested) throws IOException {
        byte[] fileBytes = getFileBytes(metadata, file, session, uuid);
        if (fileBytes == null) {
            return ResponseEntity.status(401).build();
        }

        BufferedImage original = ImageIO.read(new ByteArrayInputStream(fileBytes));
        if (original == null) {
            return ResponseEntity.notFound().build();
        }

        int w = original.getWidth();
        int h = original.getHeight();

        if (shouldScale) {
            double maxSize = 200;

            if (maxSizeRequested != null && maxSizeRequested > 0) {
                maxSize = maxSizeRequested;
            }
            double scale = Math.min((double) maxSize / w, (double) maxSize / h);
            w = Math.max(1, (int) (w * scale));
            h = Math.max(1, (int) (h * scale));
        }

        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = scaled.createGraphics();
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, w, h);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(original, 0, 0, w, h, null);
        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpeg", baos);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CACHE_CONTROL, "max-age=86400, public")
                .body(baos.toByteArray());
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

}
