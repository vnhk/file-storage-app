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

    public Metadata store(LocalDateTime createDate, Long id, String originalFilename) {
        Metadata fileEntity = new Metadata();
        fileEntity.setCreateDate(createDate);
        fileEntity.setFilename(originalFilename);
        fileEntity.setDocumentId(id);
        fileEntity.setUserName("tmpUser");
        fileEntity.setDeleted(false);

        return fileEntityRepository.save(fileEntity);
    }

    public List<Metadata> getFiles(Long documentId) {
        return fileEntityRepository.findAllByDocumentId(documentId);
    }

    public void delete(Long documentId, String filename) {
        List<Metadata> allByDocumentIdAndFilename =
                fileEntityRepository.findAllByDocumentIdAndFilename(documentId, filename);
        fileEntityRepository.deleteAll(allByDocumentIdAndFilename);
    }

    public void deleteAll(Long documentId) {
        fileEntityRepository.deleteAll(getFiles(documentId));
    }
}
