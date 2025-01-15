package com.bervan.filestorage.model;

import java.time.LocalDateTime;
import java.util.List;

public class UploadResponse {
    private List<Metadata> metadata;
    private LocalDateTime createDate;

    public UploadResponse(List<Metadata> metadata, LocalDateTime createDate) {
        this.metadata = metadata;
        this.createDate = createDate;
    }

    public UploadResponse() {

    }

    public List<Metadata> getMetadata() {
        return metadata;
    }

    public void setMetadata(List<Metadata> metadata) {
        this.metadata = metadata;
    }

    public LocalDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(LocalDateTime createDate) {
        this.createDate = createDate;
    }
}
