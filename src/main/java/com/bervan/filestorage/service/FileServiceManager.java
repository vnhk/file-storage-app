package com.bervan.filestorage.service;

import com.bervan.common.search.SearchService;
import com.bervan.common.service.BaseService;
import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.FileDownloadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.repository.MetadataRepository;
import com.bervan.ieentities.ExcelIEEntity;
import jakarta.transaction.Transactional;
import org.apache.commons.io.FilenameUtils;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class FileServiceManager extends BaseService<UUID, Metadata> {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;
    private final BervanLogger log;

    public FileServiceManager(FileDBStorageService fileDBStorageService, SearchService searchService, FileDiskStorageService fileDiskStorageService, BervanLogger log, MetadataRepository repository) {
        super(repository, searchService);
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
        this.log = log;
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

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> getDirectoriesInPath(String path) {
        return fileDBStorageService.loadByPath(path).stream().filter(Metadata::isDirectory).collect(Collectors.toList());
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

    public Metadata updateMetadata(Metadata data) {
        return fileDBStorageService.update(data);
    }

    @Override
    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public Set<Metadata> load() {
        return fileDBStorageService.load();
    }

    @Override
    public void delete(Metadata item) {
        fileDBStorageService.delete(item);
        try {
            fileDiskStorageService.delete(item.getPath(), item.getFilename());
        } catch (Exception e) {
            log.error("Could not delete file from storage!", e);
        }
    }

    @Override
    public void saveIfValid(List<? extends ExcelIEEntity<UUID>> objects) {
        throw new RuntimeException("Unsupported");
    }

    //    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public Set<Metadata> loadByPath(String path) {
        return fileDBStorageService.loadByPath(path);
    }

    @Transactional
    public Metadata createEmptyDirectory(String path, String value) {
        Metadata metadata = fileDBStorageService.createEmptyDirectory(path, value);
        fileDiskStorageService.createEmptyDirectory(path, value);

        return metadata;
    }

//    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> loadVideoById(String videoId) {
        return fileDBStorageService.loadById(UUID.fromString(videoId));
    }

//    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> loadVideosByPathStartsWith(String path) {
        return fileDBStorageService.loadByPathStartsWith(path);
    }

    public Path getFile(UUID uuid) {
        try {
            Metadata metadata = fileDBStorageService.loadById(uuid).get(0);
            return fileDiskStorageService.getFile(metadata.getPath() + File.separator + metadata.getFilename());
        } catch (Exception e) {
            throw new FileDownloadException("Cannot get file: " + uuid);
        }
    }
}
