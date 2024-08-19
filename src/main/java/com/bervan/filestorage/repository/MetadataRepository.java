package com.bervan.filestorage.repository;

import com.bervan.filestorage.model.Metadata;
import com.bervan.history.model.BaseRepository;

import java.util.List;

public interface MetadataRepository extends BaseRepository<Metadata, Long> {
    List<Metadata> findAllByFilename(String filename);
}
