package com.bervan.filestorage.model;

import java.time.LocalDateTime;

public class UploadResponse {
    private String filename;
    private Metadata metadata;
    private LocalDateTime createDate;

    public UploadResponse(String filename, Metadata metadata, LocalDateTime createDate) {
        this.filename = filename;
        this.metadata = metadata;
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

    public Metadata getMetadata() {
        return metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public LocalDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(LocalDateTime createDate) {
        this.createDate = createDate;
    }
}
