package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import jakarta.transaction.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.FileNotFoundException;
import java.util.List;

@Service
public class LoadStorageAndIntegrateWithDB {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;

    public LoadStorageAndIntegrateWithDB(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
    }

    @Transactional
    public void synchronizeStorageWithDB() throws FileNotFoundException {
        List<Metadata> allFilesInFolder = fileDiskStorageService.getAllFilesInFolder();

        for (Metadata metadata : allFilesInFolder) {
            if (fileDBStorageService.loadByPathAndFilename(metadata.getPath(), metadata.getFilename()).isEmpty()) {
                fileDBStorageService.store(metadata);
            }
        }
    }
}
