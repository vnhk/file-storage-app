package com.bervan.filestorage.model;

import java.time.LocalDateTime;
import java.util.UUID;

public class UploadResponse {
    private String filename;
    private UUID metadataId;
    private LocalDateTime createDate;

    public UploadResponse(String filename, UUID metadataId, LocalDateTime createDate) {
        this.filename = filename;
        this.metadataId = metadataId;
        this.createDate = createDate;
    }

    public UploadResponse() {

    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public UUID getMetadataId() {
        return metadataId;
    }

    public void setMetadataId(UUID metadataId) {
        this.metadataId = metadataId;
    }

    public LocalDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(LocalDateTime createDate) {
        this.createDate = createDate;
    }
}
