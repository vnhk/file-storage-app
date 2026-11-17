package com.bervan.filestorage.service;

import com.bervan.filestorage.model.*;
import com.bervan.filestorage.policy.FileStoragePolicies;
import com.bervan.logging.JsonLogger;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileZipImportService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileDiskStorageService fileDiskStorageService;
    private final FileDBStorageService fileDBStorageService;
    private final FileStoragePolicies policies;

    public FileZipImportService(FileDiskStorageService fileDiskStorageService,
                                FileDBStorageService fileDBStorageService, FileStoragePolicies policies) {
        this.fileDiskStorageService = fileDiskStorageService;
        this.fileDBStorageService = fileDBStorageService;
        this.policies = policies;
    }

    public UploadResponse extract(MultipartFile file, String description, final String path, boolean overwriteTrash, FileServiceManager manager) throws IOException {
        if (!file.getOriginalFilename().endsWith(".zip")) {
            throw new RuntimeException("File is not a zip!");
        }

        UploadResponse uploadResponse = new UploadResponse();
        Path zipFile = null;
        List<Metadata> savedFilesMetadata = new ArrayList<>();

        try {
            String[] pathParts = file.getOriginalFilename().split(Pattern.quote(File.separator));
            String fileNameTmp = fileDiskStorageService.storeTmp(file, pathParts[pathParts.length - 1]);

            zipFile = fileDiskStorageService.getTmpFile(fileNameTmp);

            // Pre-scan zip entries to detect conflicts with trashed items
            List<TrashZipConflictException.TrashConflictItem> conflicts = new ArrayList<>();
            List<Metadata> archiveFiles = new ArrayList<>();
            try (ZipInputStream scanZis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = scanZis.getNextEntry()) != null) {
                    if (entry.getName().startsWith("__") || entry.getName().startsWith(".") || entry.isDirectory()) {
                        continue;
                    }
                    String[] entryParts = entry.getName().split(Pattern.quote(File.separator));
                    String baseDestinationPath = path;
                    for (int i = 0; i < entryParts.length - 1; i++) {
                        baseDestinationPath = baseDestinationPath + File.separator + entryParts[i];
                    }
                    String cleanDestPath = baseDestinationPath.endsWith(File.separator) ? baseDestinationPath : baseDestinationPath + File.separator;
                    String entryFilename = entryParts[entryParts.length - 1];
                    archiveFiles.add(new Metadata(cleanDestPath, entryFilename, null, LocalDateTime.now(), false));

                    List<Metadata> trashed = fileDBStorageService.loadByPathAndFilenameInTrash(cleanDestPath, entryFilename);
                    for (Metadata t : trashed) {
                        conflicts.add(new TrashZipConflictException.TrashConflictItem(t.getId(), t.getFilename(), t.getPath()));
                    }
                    scanZis.closeEntry();
                }
            }

            if (!conflicts.isEmpty()) {
                if (!overwriteTrash) {
                    throw new TrashZipConflictException(conflicts);
                } else {
                    for (TrashZipConflictException.TrashConflictItem conflict : conflicts) {
                        Metadata m = manager.getMetadata(conflict.getMetadataId());
                        if (m != null) {
                            manager.deletePermanently(m);
                        }
                    }
                }
            }

            var shouldTrack = policies.trackingFilter(archiveFiles);
            List<UploadResponse> uploadResponses = new ArrayList<>();

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry zipEntry;

                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (zipEntry.getName().startsWith("__") || zipEntry.getName().startsWith(".") || zipEntry.isDirectory()) {
                        continue;
                    }

                    String extractedFilename = zipEntry.getName();
                    log.info("Started processing file: " + extractedFilename);

                    String[] extractedPathParts = extractedFilename.split(Pattern.quote(File.separator));
                    String fileName = extractedPathParts[extractedPathParts.length - 1];
                    Path tempFilePath = Files.createTempFile("extracted_", "_" + fileName);

                    log.info("File is BEING SAVED as temp: " + extractedFilename + " in " + tempFilePath);
                    Files.copy(zis, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("File is SAVED as temp: " + extractedFilename + " in " + tempFilePath);

                    log.info("File is being processed " + extractedFilename + " in destination path: " + path);
                    uploadResponses.add(processFile(tempFilePath, extractedFilename, description, path, overwriteTrash, manager, shouldTrack));
                    log.info("File is processed " + extractedFilename + " in destination path: " + path);

                    zis.closeEntry();
                }
            }

            uploadResponses.forEach(response -> savedFilesMetadata.addAll(response.getMetadata()));
        } catch (TrashZipConflictException e) {
            throw e;
        } catch (Exception e) {
            for (Metadata filesToBeReverted : savedFilesMetadata) {
                Files.deleteIfExists(fileDiskStorageService.getFile(filesToBeReverted.getPath() + File.separator + filesToBeReverted.getFilename()));
            }
            throw new RuntimeException("Error processing ZIP file", e);
        } finally {
            if (zipFile != null) {
                Files.deleteIfExists(zipFile);
            }
        }

        uploadResponse.setCreateDate(LocalDateTime.now());
        uploadResponse.setMetadata(savedFilesMetadata);
        return uploadResponse;
    }

    private UploadResponse processFile(Path tempFilePath, String extractedFilename, String description, String path, boolean overwriteTrash, FileServiceManager manager, Predicate<Metadata> shouldTrack) throws IOException {
        try {
            BervanMockMultiPartFile multipartFile = new BervanMockMultiPartFile(extractedFilename, extractedFilename, Files.probeContentType(tempFilePath), Files.newInputStream(tempFilePath));
            return manager.saveWithTrackingFilter(multipartFile, description, path, overwriteTrash, shouldTrack);
        } finally {
            Files.delete(tempFilePath);
        }
    }

}
