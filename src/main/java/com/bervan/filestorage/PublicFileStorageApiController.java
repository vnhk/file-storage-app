package com.bervan.filestorage;

import com.bervan.filestorage.model.DownloadItem;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileEncryptionService;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.logging.JsonLogger;
import jakarta.annotation.security.PermitAll;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/public/files")
public class PublicFileStorageApiController {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final FileEncryptionService fileEncryptionService;
    private final Map<UUID, DownloadItem> fileCache = new ConcurrentHashMap<>();

    public PublicFileStorageApiController(FileServiceManager fileServiceManager,
                                          FileEncryptionService fileEncryptionService) {
        this.fileServiceManager = fileServiceManager;
        this.fileEncryptionService = fileEncryptionService;
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
                log.error("RANGE STREAM FAILED", e);
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
}
