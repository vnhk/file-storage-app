package com.bervan.filestorage.service;

import com.bervan.common.user.UserRepository;
import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import org.springframework.stereotype.Service;

import java.util.*;

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

        Map<String, Metadata> allFilesInFolderMap = new HashMap<>();
        for (Metadata metadata : allFilesInFolder) {
            String key = metadata.getPath() + "|" + metadata.getFilename(); ///ab + c.txt  ==  /a + bc.txt
            if (allFilesInFolderMap.containsKey(key)) {
                log.error("Duplicate file path: " + key);
                continue;
            }
            allFilesInFolderMap.put(key, metadata);
        }

        Set<Metadata> dbFiles = fileDBStorageService.load();
        Set<String> filesPresentInDBAndFolder = new HashSet<>();

        List<Metadata> toDelete = new ArrayList<>();
        for (Metadata dbFile : dbFiles) {
            String key = dbFile.getPath() + "|" + dbFile.getFilename();
            if (allFilesInFolderMap.containsKey(key)) {
                filesPresentInDBAndFolder.add(key);
            } else {
                toDelete.add(dbFile);
            }
        }

        log.info("Delete all files in folder: " + toDelete.size());
        batchDelete(toDelete, 100);

        allFilesInFolderMap.entrySet()
                .removeIf(entry -> filesPresentInDBAndFolder.contains(entry.getKey()));

        List<Metadata> toInsert = allFilesInFolderMap.values().stream().toList();

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
