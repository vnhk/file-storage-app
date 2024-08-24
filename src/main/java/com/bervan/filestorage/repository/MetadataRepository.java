package com.bervan.filestorage.repository;

import com.bervan.filestorage.model.Metadata;
import com.bervan.history.model.BaseRepository;

import java.util.Optional;
import java.util.Set;

public interface MetadataRepository extends BaseRepository<Metadata, Long> {
    Optional<Metadata> findByPathAndFilename(String path, String filename);

    Set<Metadata> findByPath(String path);
}
