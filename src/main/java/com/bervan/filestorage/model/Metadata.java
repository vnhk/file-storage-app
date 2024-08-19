package com.bervan.filestorage.model;

import com.bervan.common.model.PersistableTableData;
import com.bervan.history.model.AbstractBaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import org.checkerframework.common.aliasing.qual.Unique;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
public class Metadata implements AbstractBaseEntity<UUID>, PersistableTableData {
    @Id
    @GeneratedValue
    private UUID id;
    @Unique
    private String filename;
    private LocalDateTime createDate;
    private String userName;
    private String description;
    private boolean deleted;
    private LocalDateTime modificationDate;

    public Metadata() {

    }

    public Metadata(UUID id, String filename, LocalDateTime createDate, String userName, String description, boolean deleted, LocalDateTime modificationDate) {
        this.id = id;
        this.filename = filename;
        this.createDate = createDate;
        this.userName = userName;
        this.description = description;
        this.deleted = deleted;
        this.modificationDate = modificationDate;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
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

    @Override
    public String getName() {
        return filename;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
