package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.repository.MetadataRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FileDBStorageService {
    private final MetadataRepository fileEntityRepository;

    public FileDBStorageService(MetadataRepository fileEntityRepository) {
        this.fileEntityRepository = fileEntityRepository;
    }

    public Metadata store(LocalDateTime createDate, String originalFilename, String description) {
        Metadata fileEntity = new Metadata();
        fileEntity.setCreateDate(createDate);
        fileEntity.setFilename(originalFilename);
        fileEntity.setUserName("tmpUser");
        fileEntity.setDeleted(false);
        fileEntity.setDescription(description);

        return fileEntityRepository.save(fileEntity);
    }

    public void delete(String filename) {
        List<Metadata> allByDocumentIdAndFilename =
                fileEntityRepository.findAllByFilename(filename);
        fileEntityRepository.deleteAll(allByDocumentIdAndFilename);
    }

    public void deleteAll() {
        fileEntityRepository.deleteAll(load());
    }

    public List<Metadata> load() {
        return fileEntityRepository.findAll();
    }
}
