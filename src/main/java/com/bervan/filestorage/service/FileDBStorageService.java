package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.repository.MetadataRepository;
import com.bervan.logging.JsonLogger;
import jakarta.transaction.Transactional;
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
        if (!path.endsWith(File.separator)) {
            path += File.separator;
        }
        fileEntity.setPath(path);
        fileEntity.setExtension(extension);
        fileEntity.setDirectory(isDirectory);

        return fileEntityRepository.save(fileEntity);
    }

    public Metadata store(Metadata metadata) {
        try {
            return store(LocalDateTime.now(), metadata.getPath(), metadata.getFilename(), metadata.getDescription(), metadata.getExtension(), metadata.isDirectory());
        } catch (Exception e) {
            log.error("File is not saved:", e.getMessage());
        }
        return null;
    }

    public void delete(Metadata metadata) {
        moveToTrash(metadata);
    }

    public void deletePermanently(Metadata metadata) {
        String path;
        if (metadata.getPath().endsWith(File.separator)) {
            path = metadata.getPath() + metadata.getFilename();
        } else {
            path = metadata.getPath() + File.separator + metadata.getFilename();
        }
        log.info("Permanently deleting metadata file: {}", path);
        if (metadata.isDirectory()) {
            log.info("Permanently deleting metadata directory: {}", path);
            Set<Metadata> metadatas = loadAllByPathIncludingTrash(path);
            for (Metadata m : metadatas) {
                deletePermanently(m);
            }
        }
        fileEntityRepository.delete(metadata);
    }

    public void moveToTrash(Metadata metadata) {
        String path;
        if (metadata.getPath().endsWith(File.separator)) {
            path = metadata.getPath() + metadata.getFilename();
        } else {
            path = metadata.getPath() + File.separator + metadata.getFilename();
        }
        log.info("Moving metadata to trash: {}", path);
        if (metadata.isDirectory()) {
            log.info("Moving metadata directory to trash: {}", path);
            Set<Metadata> metadatas = loadAllByPathIncludingTrash(path);
            for (Metadata m : metadatas) {
                moveToTrash(m);
            }
        }
        metadata.setInTrash(true);
        metadata.setInTrashStartDate(LocalDateTime.now());
        fileEntityRepository.save(metadata);
    }

    public void restoreFromTrash(Metadata metadata) {
        String path;
        if (metadata.getPath().endsWith(File.separator)) {
            path = metadata.getPath() + metadata.getFilename();
        } else {
            path = metadata.getPath() + File.separator + metadata.getFilename();
        }
        log.info("Restoring metadata from trash: {}", path);
        if (metadata.isDirectory()) {
            Set<Metadata> metadatas = loadAllByPathIncludingTrash(path);
            for (Metadata m : metadatas) {
                restoreFromTrash(m);
            }
        }
        metadata.setInTrash(false);
        metadata.setInTrashStartDate(null);
        fileEntityRepository.save(metadata);
    }

    public Set<Metadata> load() {
        return new HashSet<>(fileEntityRepository.findAll());
    }

    public Set<Metadata> loadByPath(String path) {
        return fileEntityRepository.findByPathAndInTrashFalse(path);
    }

    public Set<Metadata> loadAllByPathIncludingTrash(String path) {
        return fileEntityRepository.findByPath(path);
    }

    public Set<Metadata> loadTrash() {
        return fileEntityRepository.findByInTrashTrue();
    }

    public List<Metadata> loadByPathAndFilenameInTrash(String path, String filename) {
        return fileEntityRepository.findByPathAndFilenameAndInTrashTrue(path, filename);
    }

    public List<Metadata> findExpiredTrash(LocalDateTime cutoff) {
        return fileEntityRepository.findByInTrashTrueAndInTrashStartDateBefore(cutoff);
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

    @Transactional
    public void deleteBatch(List<Metadata> batch) {
        batch.forEach(this::delete); // each batch in one transaction
    }

    @Transactional
    public void insertBatch(List<Metadata> batch) {
        batch.forEach(this::store); // each batch in one transaction
    }
}
