package com.bervan.filestorage.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
public class TrashZipConflictException extends RuntimeException {
    private final List<TrashConflictItem> conflicts;

    public TrashZipConflictException(List<TrashConflictItem> conflicts) {
        super("A file with the given name and path is currently in the trash. Continue if you want to permanently delete the file in the trash, or cancel if you don't want to.");
        this.conflicts = conflicts;
    }

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class TrashConflictItem {
        private UUID metadataId;
        private String filename;
        private String path;
    }
}
