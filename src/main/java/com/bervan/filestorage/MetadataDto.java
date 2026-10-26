package com.bervan.filestorage;

import com.bervan.filestorage.model.Metadata;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public final class MetadataDto {
    private String id;
    private String filename;
    private String path;
    private boolean directory;
    private String extension;
    private Long fileSize;
    private boolean encrypted;
    private String modificationDate;
    private boolean inTrash;
    private String inTrashStartDate;

    public static MetadataDto from(Metadata m) {
        if (m == null) {
            return null;
        }
        return new MetadataDto(
                m.getId() != null ? m.getId().toString() : null,
                m.getFilename(),
                m.getPath(),
                m.isDirectory(),
                m.getExtension(),
                m.getFileSize(),
                m.isEncrypted(),
                m.getModificationDate() != null ? m.getModificationDate().toString() : null,
                m.isInTrash(),
                m.getInTrashStartDate() != null ? m.getInTrashStartDate().toString() : null
        );
    }
}
