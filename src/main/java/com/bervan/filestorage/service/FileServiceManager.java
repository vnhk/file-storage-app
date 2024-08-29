package com.bervan.filestorage.service;

import com.bervan.common.service.BaseService;
import com.bervan.filestorage.model.FileDownloadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class FileServiceManager implements BaseService<Metadata> {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;

    public FileServiceManager(FileDBStorageService fileDBStorageService, FileDiskStorageService fileDiskStorageService) {
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
    }

    public UploadResponse save(MultipartFile file, String description, String path) {
        UploadResponse uploadResponse = new UploadResponse();
        uploadResponse.setFilename(file.getOriginalFilename());
        String filename = fileDiskStorageService.store(file, path);
        LocalDateTime createDate = LocalDateTime.now();
        uploadResponse.setCreateDate(createDate);

        Metadata stored = fileDBStorageService.store(createDate, path, filename, description, FilenameUtils.getExtension(filename), false);

        uploadResponse.setMetadata(stored);

        return uploadResponse;
    }


    public Path getFile(UUID uuid) {
        try {
            Metadata metadata = fileDBStorageService.loadById(uuid).get();
            return fileDiskStorageService.getFile(metadata.getPath() + File.separator + metadata.getFilename());
        } catch (Exception e) {
            throw new FileDownloadException("Cannot get file: " + uuid);
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
    public Metadata save(Metadata data) {
        throw new RuntimeException("Use store method.");
    }

    @Override
    public Set<Metadata> load() {
        return fileDBStorageService.load();
    }

    @Override
    public void delete(Metadata item) {
        throw new RuntimeException("Not implemented correctly.");
    }

    public Set<Metadata> loadByPath(String path) {
        return fileDBStorageService.loadByPath(path);
    }
}
