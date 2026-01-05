package com.bervan.filestorage.service;

import com.bervan.common.user.UserRepository;
import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class LoadStorageAndIntegrateWithDB {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;
    private final UserRepository userRepository;

    public LoadStorageAndIntegrateWithDB(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService, UserRepository userRepository) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
        this.userRepository = userRepository;
    }

//    @Scheduled(cron = "0 0 0/1 * * *")
//    public void synchronizeStorageCron() {
//        List<User> all = userRepository.findAll();
//        SecurityContext securityContext = SecurityContextHolder.getContext();
//        securityContext.setAuthentication();
//        synchronizeStorageWithDB();
//
//    }

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

        log.info("Delete all files in folder: " + toDelete.size());
        batchDelete(toDelete, 100);

        List<Metadata> toInsert = allFilesInFolder.stream()
                .filter(metadata -> fileDBStorageService.loadByPathAndFilename(metadata.getPath(), metadata.getFilename()).isEmpty())
                .toList();

        log.info("Insert all files in folder: " + toInsert.size());
        batchInsert(toInsert, 100);
    }

    private void batchDelete(List<Metadata> list, int batchSize) {
        for (int i = 0; i < list.size(); i += batchSize) {
            int end = Math.min(i + batchSize, list.size());
            List<Metadata> batch = list.subList(i, end);
            log.info("Batch delete files in folder: " + batch.size());
            try {
                fileDBStorageService.deleteBatch(batch);
            } catch (Exception e) {
                log.error("Error while delete files in folder: " + batch, e);
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
                log.error("Error while insert files in folder: " + batch, e);
            }
        }
    }
}
