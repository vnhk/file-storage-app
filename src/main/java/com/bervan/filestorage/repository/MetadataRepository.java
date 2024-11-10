package com.bervan.filestorage.repository;

import com.bervan.filestorage.model.Metadata;
import com.bervan.history.model.BaseRepository;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MetadataRepository extends BaseRepository<Metadata, UUID> {
    Optional<Metadata> findByPathAndFilenameAndOwnerId(String path, String filename, UUID ownerId);

    Set<Metadata> findByPathAndOwnerId(String path, UUID ownerId);
}
