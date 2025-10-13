package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class LoadStorageAndIntegrateWithDB {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;

    public LoadStorageAndIntegrateWithDB(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
    }

    @Transactional
    public void synchronizeStorageWithDB() {
        List<Metadata> allFilesInFolder = fileDiskStorageService.getAllFilesInFolder();

        long count = allFilesInFolder.stream().filter(e -> e.getPath().isEmpty()).count();
        if (count != 0) {
            log.error("Incorrect path amount of files:" + count);
            throw new RuntimeException("Incorrect path amount of files:" + count);
        }
        Set<Metadata> load = fileDBStorageService.load();

        for (Metadata metadata : load) {
            if (allFilesInFolder.stream().noneMatch(m ->
                    m.getPath().equals(metadata.getPath())
                            && m.getFilename().equals(metadata.getFilename())
            )) {
                fileDBStorageService.delete(metadata);
            }
        }

        for (Metadata metadata : allFilesInFolder) {
            if (fileDBStorageService.loadByPathAndFilename(metadata.getPath(), metadata.getFilename()).isEmpty()) {
                fileDBStorageService.store(metadata);
            }
        }
    }
}
