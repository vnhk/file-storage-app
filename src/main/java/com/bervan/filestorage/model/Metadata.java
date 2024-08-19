package com.bervan.filestorage.model;

import com.bervan.history.model.AbstractBaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
public class Metadata implements AbstractBaseEntity<UUID> {
    @Id
    @GeneratedValue
    private UUID id;
    private String filename;
    private Long documentId;
    private LocalDateTime createDate;
    private String userName;
    private boolean deleted;
    private LocalDateTime modificationDate;

    public Metadata() {

    }

    public Metadata(UUID id, String filename, Long documentId, LocalDateTime createDate, String userName, boolean deleted, LocalDateTime modificationDate) {
        this.id = id;
        this.filename = filename;
        this.documentId = documentId;
        this.createDate = createDate;
        this.userName = userName;
        this.deleted = deleted;
        this.modificationDate = modificationDate;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public LocalDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(LocalDateTime createDate) {
        this.createDate = createDate;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    @Override
    public LocalDateTime getModificationDate() {
        return modificationDate;
    }

    @Override
    public void setModificationDate(LocalDateTime modificationDate) {
        this.modificationDate = modificationDate;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public void setId(UUID uuid) {
        this.id = uuid;
    }
}
