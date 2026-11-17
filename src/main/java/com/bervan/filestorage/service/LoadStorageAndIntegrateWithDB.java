package com.bervan.filestorage.service;

import com.bervan.filestorage.policy.FileStoragePolicies;
import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class LoadStorageAndIntegrateWithDB {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;
    private final FileStoragePolicies policies;

    public LoadStorageAndIntegrateWithDB(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService, FileStoragePolicies policies) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
        this.policies = policies;
    }

    public void synchronizeStorageWithDB(String directory) {
        if (directory == null || !directory.startsWith("/") || directory.contains("\\")) {
            throw new IllegalArgumentException("An absolute storage directory is required");
        }
        for (String component : directory.split("/")) {
            if (component.equals("..") || component.equals(".")) {
                throw new IllegalArgumentException("Relative path components are not allowed");
            }
        }
        directory = java.nio.file.Path.of(directory).normalize().toString().replace('\\', '/');
        if (!directory.endsWith("/")) directory += "/";
        List<Metadata> allFilesInFolder = fileDiskStorageService.getAllFilesInFolder(directory);

        long count = allFilesInFolder.stream().filter(e -> e.getPath().isEmpty()).count();
        if (count != 0) {
            log.error("Incorrect path amount of files: " + count);
            throw new RuntimeException("Incorrect path amount of files: " + count);
        }

        Map<String, Metadata> allFilesInFolderMap = new HashMap<>();
        for (Metadata metadata : allFilesInFolder) {
            String key = metadata.getPath() + "|" + metadata.getFilename(); ///ab + c.txt  ==  /a + bc.txt
            if (allFilesInFolderMap.containsKey(key)) {
                log.error("Duplicate file path: " + key);
                continue;
            }
            allFilesInFolderMap.put(key, metadata);
        }

        var shouldTrack = policies.trackingFilter(allFilesInFolder);
        Set<Metadata> dbFiles = fileDBStorageService.loadAllByPathPrefix(directory);
        Set<String> filesPresentInDBAndFolder = new HashSet<>();

        List<Metadata> metadataExcludedByPolicy = new ArrayList<>();
        List<Metadata> metadataMissingOnDisk = new ArrayList<>();
        List<Metadata> toUpdate = new ArrayList<>();
        for (Metadata dbFile : dbFiles) {
            String key = dbFile.getPath() + "|" + dbFile.getFilename();
            if (!shouldTrack.test(dbFile)) {
                metadataExcludedByPolicy.add(dbFile);
                allFilesInFolderMap.remove(key);
                continue;
            }
            if (allFilesInFolderMap.containsKey(key)) {
                filesPresentInDBAndFolder.add(key);
                Metadata diskFile = allFilesInFolderMap.get(key);
                boolean changed = dbFile.isInTrash()
                        || dbFile.isDirectory() != diskFile.isDirectory()
                        || !Objects.equals(dbFile.getExtension(), diskFile.getExtension())
                        || !Objects.equals(dbFile.getCreateDate(), diskFile.getCreateDate())
                        || !Objects.equals(dbFile.getModificationDate(), diskFile.getModificationDate())
                        || (!diskFile.isDirectory() && !Objects.equals(dbFile.getFileSize(), diskFile.getFileSize()));
                if (changed) {
                    dbFile.setInTrash(false);
                    dbFile.setInTrashStartDate(null);
                    dbFile.setDirectory(diskFile.isDirectory());
                    dbFile.setExtension(diskFile.getExtension());
                    dbFile.setCreateDate(diskFile.getCreateDate());
                    dbFile.setModificationDate(diskFile.getModificationDate());
                    if (!diskFile.isDirectory()) dbFile.setFileSize(diskFile.getFileSize());
                    toUpdate.add(dbFile);
                }
            } else {
                metadataMissingOnDisk.add(dbFile);
            }
        }

        removeMetadataExcludedByPolicy(metadataExcludedByPolicy, 100);
        batchUpdate(toUpdate, 100);

        log.info("Remove metadata for missing disk entries: " + metadataMissingOnDisk.size());
        batchDelete(metadataMissingOnDisk, 100);

        allFilesInFolderMap.entrySet()
                .removeIf(entry -> filesPresentInDBAndFolder.contains(entry.getKey()));

        List<Metadata> toInsert = allFilesInFolderMap.values().stream().filter(shouldTrack).toList();

        log.info("Insert all files in folder: " + toInsert.size());
        batchInsert(toInsert, 100);
    }

    /**
     * Removes existing records that a storage policy no longer wants to track.
     * Filtering new inserts alone would leave records created before the policy was introduced.
     * Unlike cleanup of missing disk entries, exclusion does not mean the file is missing:
     * it can still be required for playback, exports, and folder size calculations.
     * Therefore this path removes file metadata only, without moving items to trash,
     * traversing directory records, calling deletion hooks, or deleting physical files.
     */
    private void removeMetadataExcludedByPolicy(List<Metadata> excludedMetadata, int batchSize) {
        for (int i = 0; i < excludedMetadata.size(); i += batchSize) {
            List<Metadata> batch = excludedMetadata.subList(i, Math.min(i + batchSize, excludedMetadata.size()));
            try {
                fileDBStorageService.removeFileMetadataOnly(batch);
            } catch (Exception e) {
                log.error("Failed to remove metadata excluded by storage policy: " + describeBatch(batch), e);
            }
        }
    }

    private String describeBatch(List<Metadata> batch) {
        Map<String, List<String>> filesByDirectory = new TreeMap<>();
        for (Metadata file : batch) {
            filesByDirectory.computeIfAbsent(file.getPath(), path -> new ArrayList<>())
                    .add(file.getFilename());
        }
        List<String> descriptions = new ArrayList<>();
        filesByDirectory.forEach((directory, filenames) -> {
            if (filenames.size() > 50) {
                descriptions.add(directory + " (" + filenames.size() + " entries)");
            } else {
                filenames.stream().sorted().forEach(filename -> descriptions.add(directory + filename));
            }
        });
        return String.join(", ", descriptions);
    }

    private void batchUpdate(List<Metadata> list, int batchSize) {
        for (int i = 0; i < list.size(); i += batchSize) {
            List<Metadata> batch = list.subList(i, Math.min(i + batchSize, list.size()));
            try {
                fileDBStorageService.updateBatch(batch);
            } catch (Exception e) {
                log.error("Failed to update synchronized metadata: " + describeBatch(batch), e);
            }
        }
    }

    private void batchDelete(List<Metadata> list, int batchSize) {
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            List<Metadata> batch = list.subList(i, end);
            log.info("Batch delete files in folder: " + batch.size());
            try {
                fileDBStorageService.deleteBatchPermanently(batch);
            } catch (Exception e) {
                log.error("Failed to delete synchronized metadata: " + describeBatch(batch), e);
            }
        }
    }

    private void batchInsert(List<Metadata> list, int batchSize) {
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            List<Metadata> batch = list.subList(i, end);
            log.info("Batch insert files in folder: " + batch.size());
            try {
                fileDBStorageService.insertBatch(batch);
            } catch (Exception e) {
                log.error("Failed to insert synchronized metadata: " + describeBatch(batch), e);
            }
        }
    }
}
