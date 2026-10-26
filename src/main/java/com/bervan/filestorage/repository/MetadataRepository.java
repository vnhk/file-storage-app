package com.bervan.filestorage.repository;

import com.bervan.filestorage.model.Metadata;
import com.bervan.history.model.BaseRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface MetadataRepository extends BaseRepository<Metadata, UUID> {
    List<Metadata> findByPathAndFilename(String path, String filename);

    Set<Metadata> findByPath(String path);

    Set<Metadata> findByPathAndInTrashFalse(String path);

    List<Metadata> findByPathAndFilenameAndInTrashTrue(String path, String filename);

    List<Metadata> findByPathAndFilenameAndInTrashFalse(String path, String filename);

    Set<Metadata> findByInTrashTrue();

    List<Metadata> findByInTrashTrueAndInTrashStartDateBefore(LocalDateTime cutoff);
}
