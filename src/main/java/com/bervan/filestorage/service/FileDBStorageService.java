package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.repository.MetadataRepository;
import com.bervan.logging.JsonLogger;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class FileDBStorageService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
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
        return store(LocalDateTime.now(), metadata.getPath(), metadata.getFilename(), metadata.getDescription(), metadata.getExtension(), metadata.isDirectory());
    }

    public void delete(Metadata metadata) {
        String path;
        if (metadata.getPath().endsWith(File.separator)) {
            path = metadata.getPath() + metadata.getFilename();

        } else {
            path = metadata.getPath() + File.separator + metadata.getFilename();
        }
        log.info("Deleting metadata file: {}", path);
        if (metadata.isDirectory()) {
            log.info("Deleting metadata directory: {}", path);
            Set<Metadata> metadatas = loadByPath(path);
            for (Metadata m : metadatas) {
                delete(m);
            }
        }
        fileEntityRepository.delete(metadata);
    }

    public Set<Metadata> load() {
        return new HashSet<>(fileEntityRepository.findAll());
    }

    public Set<Metadata> loadByPath(String path) {
        return fileEntityRepository.findByPath(path);
    }

    public List<Metadata> loadById(UUID id) {
        Optional<Metadata> byId = fileEntityRepository.findById(id);
        if (byId.isPresent()) {
            List<Metadata> res = new ArrayList<>();
            res.add(byId.get());
            return res;
        }
        return new ArrayList<>();
    }

    public List<Metadata> loadByPathAndFilename(String path, String filename) {
        return fileEntityRepository.findByPathAndFilename(path, filename);
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
