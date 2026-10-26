package com.bervan.filestorage.dto;

import com.bervan.filestorage.model.TrashZipConflictException.TrashConflictItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TrashConflictResponse {
    private boolean conflict;
    private boolean inTrash;
    private boolean zipConflict;
    private String metadataId;
    private String filename;
    private String path;
    private String message;
    private String trashViewUrl;
    private List<TrashConflictItem> conflicts;

    public static TrashConflictResponse ofSingleConflict(String metadataId, String filename, String path, String message) {
        return TrashConflictResponse.builder()
                .conflict(true)
                .inTrash(true)
                .zipConflict(false)
                .metadataId(metadataId)
                .filename(filename)
                .path(path)
                .message(message)
                .trashViewUrl("/files?view=trash&highlight=" + metadataId)
                .build();
    }

    public static TrashConflictResponse ofZipConflict(List<TrashConflictItem> conflicts, String message) {
        return TrashConflictResponse.builder()
                .conflict(true)
                .inTrash(true)
                .zipConflict(true)
                .conflicts(conflicts)
                .message(message)
                .build();
    }
}
