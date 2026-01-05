package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class LoadStorageAndIntegrateWithDB {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;

    public LoadStorageAndIntegrateWithDB(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
    }

    public void synchronizeStorageWithDB() {
        List<Metadata> allFilesInFolder = fileDiskStorageService.getAllFilesInFolder();

        long count = allFilesInFolder.stream().filter(e -> e.getPath().isEmpty()).count();
        if (count != 0) {
            log.error("Incorrect path amount of files: " + count);
            throw new RuntimeException("Incorrect path amount of files: " + count);
        }

        Set<Metadata> dbFiles = fileDBStorageService.load();

        List<Metadata> toDelete = dbFiles.stream()
                .filter(metadata -> allFilesInFolder.stream().noneMatch(m ->
                        m.getPath().equals(metadata.getPath()) &&
                                m.getFilename().equals(metadata.getFilename())))
                .toList();

        batchDelete(toDelete, 1000);

        List<Metadata> toInsert = allFilesInFolder.stream()
                .filter(metadata -> fileDBStorageService.loadByPathAndFilename(metadata.getPath(), metadata.getFilename()).isEmpty())
                .toList();

        batchInsert(toInsert, 1000);
    }

    private void batchDelete(List<Metadata> list, int batchSize) {
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            List<Metadata> batch = list.subList(i, end);
            deleteBatch(batch);
        }
    }

    private void batchInsert(List<Metadata> list, int batchSize) {
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            List<Metadata> batch = list.subList(i, end);
            insertBatch(batch);
        }
    }

    @Transactional
    public void deleteBatch(List<Metadata> batch) {
        batch.forEach(fileDBStorageService::delete); // each batch in one transaction
    }

    @Transactional
    public void insertBatch(List<Metadata> batch) {
        batch.forEach(fileDBStorageService::store); // each batch in one transaction
    }
}
