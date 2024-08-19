package com.bervan.filestorage.repository;

import com.bervan.filestorage.model.Metadata;
import com.bervan.history.model.BaseRepository;

import java.util.List;

public interface MetadataRepository extends BaseRepository<Metadata, Long> {
    List<Metadata> findAllByDocumentId(Long documentId);

    List<Metadata> findAllByDocumentIdAndFilename(Long documentId, String filename);
}
