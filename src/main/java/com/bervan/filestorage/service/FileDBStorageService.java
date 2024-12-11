package com.bervan.filestorage.service;

import com.bervan.common.service.AuthService;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.repository.MetadataRepository;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class FileDBStorageService {
    private final MetadataRepository fileEntityRepository;

    public FileDBStorageService(MetadataRepository fileEntityRepository) {
        this.fileEntityRepository = fileEntityRepository;
    }

    public Metadata store(LocalDateTime createDate, String path, String originalFilename, String description,
                          String extension, boolean isDirectory) {
        Metadata fileEntity = new Metadata();
        fileEntity.setCreateDate(createDate);
        fileEntity.setFilename(originalFilename);
        fileEntity.setUserName("tmpUser");
        fileEntity.setDescription(description);
        fileEntity.setPath(path);
        fileEntity.setExtension(extension);
        fileEntity.setDirectory(isDirectory);

        return fileEntityRepository.save(fileEntity);
    }

    public Metadata store(Metadata metadata) {
        return fileEntityRepository.save(metadata);
    }

    public void delete(Metadata metadata) {
        if(metadata.isDirectory()) {
            for (Metadata m : loadByPath(metadata.getPath() + File.separator + metadata.getFilename())) {
                delete(m);
            }
        }
        fileEntityRepository.delete(metadata);
    }

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public Set<Metadata> load() {
        return new HashSet<>(fileEntityRepository.findAll());
    }

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public Set<Metadata> loadByPath(String path) {
        return fileEntityRepository.findByPathAndOwnersId(path, AuthService.getLoggedUserId());
    }

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> loadById(UUID id) {
        Optional<Metadata> byId = fileEntityRepository.findById(id);
        return byId.map(Arrays::asList).orElseGet(ArrayList::new);
    }

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> loadByPathAndFilename(String path, String filename) {
        return fileEntityRepository.findByPathAndFilenameAndOwnersId(path, filename, AuthService.getLoggedUserId());
    }

    public Metadata createEmptyDirectory(String path, String value) {
        return store(LocalDateTime.now(), path, value, null, null, true);
    }

    public Metadata update(Metadata data) {
        if (data == null || data.getId() == null) {
            throw new RuntimeException("Unable to update Metadata! Id is null!");
        }
        return fileEntityRepository.save(data);
    }
}
