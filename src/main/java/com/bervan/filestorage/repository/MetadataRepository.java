package com.bervan.filestorage.repository;

import com.bervan.filestorage.model.Metadata;
import com.bervan.history.model.BaseRepository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface MetadataRepository extends BaseRepository<Metadata, UUID> {
    List<Metadata> findByPathAndFilenameAndOwnersId(String path, String filename, UUID ownerId);

    Set<Metadata> findByPathAndOwnersId(String path, UUID ownerId);

    List<Metadata> findByPathStartsWithAndOwnersId(String path, UUID loggedUserId);
}
