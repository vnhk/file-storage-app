package com.bervan.filestorage.service;

import com.bervan.common.service.BaseService;
import com.bervan.filestorage.model.FileDownloadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class FileServiceManager implements BaseService<Metadata> {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;

    public FileServiceManager(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
    }

    public UploadResponse save(MultipartFile file, String description) {
        UploadResponse uploadResponse = new UploadResponse();
        uploadResponse.setFilename(file.getOriginalFilename());
        String filename = fileDiskStorageService.store(file);
        LocalDateTime createDate = LocalDateTime.now();
        uploadResponse.setCreateDate(createDate);

        Metadata stored = fileDBStorageService.store(createDate, filename, description);

        uploadResponse.setMetadata(stored);

        return uploadResponse;
    }


    public Path getFile(String filename) {
        try {
            Optional<Path> file = fileDiskStorageService.getFile(filename);
            if (file.isEmpty()) {
                throw new FileDownloadException("Cannot find file " + filename);
            } else {
                return file.get();
            }
        } catch (Exception e) {
            throw new FileDownloadException("Cannot get file " + filename);
        }
    }

    public void delete(String filename) {
        fileDBStorageService.delete(filename);
        fileDiskStorageService.delete(filename);
    }

    public void deleteAll() {
        fileDiskStorageService.deleteAll();
        fileDBStorageService.deleteAll();
    }

    public Path doBackup() throws IOException, InterruptedException {
        return fileDiskStorageService.doBackup();
    }

    @Override
    public void save(List<Metadata> data) {
        throw new RuntimeException("Use store method.");
    }

    @Override
    public void save(Metadata data) {
        throw new RuntimeException("Use store method.");
    }

    @Override
    public List<Metadata> load() {
        return fileDBStorageService.load();
    }
}
