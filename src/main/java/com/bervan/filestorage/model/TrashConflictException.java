package com.bervan.filestorage.model;

import lombok.Getter;

import java.util.UUID;

@Getter
public class TrashConflictException extends RuntimeException {
    private final UUID metadataId;
    private final String filename;
    private final String path;

    public TrashConflictException(UUID metadataId, String filename, String path) {
        super("A file with the given name and path is currently in the trash. Continue if you want to permanently delete the file in the trash, or cancel if you don't want to.");
        this.metadataId = metadataId;
        this.filename = filename;
        this.path = path;
    }
}
