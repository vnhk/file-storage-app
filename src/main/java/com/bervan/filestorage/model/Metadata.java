package com.bervan.filestorage.model;

import com.bervan.common.model.PersistableTableData;
import com.bervan.history.model.AbstractBaseEntity;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "Metadata",
        uniqueConstraints =
        @UniqueConstraint(columnNames = {"path", "filename"})
)
public class Metadata implements AbstractBaseEntity<UUID>, PersistableTableData {
    @Id
    @GeneratedValue
    private UUID id;
    private String filename;
    private String path;
    private boolean isDirectory;
    private String extension;
    private LocalDateTime createDate;
    private String userName;
    private String description;
    private LocalDateTime modificationDate;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isDirectory() {
        return isDirectory;
    }

    public void setDirectory(boolean directory) {
        isDirectory = directory;
    }

    public String getExtension() {
        return extension;
    }

    public void setExtension(String extension) {
        this.extension = extension;
    }

    public Metadata() {

    }

    public Metadata(String path, String filename, String extension, LocalDateTime createDate, boolean isDirectory) {
        this.path = path;
        this.filename = filename;
        this.extension = extension;
        this.createDate = createDate;
        this.isDirectory = isDirectory;
    }

    public Metadata(UUID id, String path, String filename, String extension, boolean isDirectory) {
        this.id = id;
        this.path = path;
        this.filename = filename;
        this.extension = extension;
        this.isDirectory = isDirectory;
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
    public String getTableFilterableColumnValue() {
        return filename;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
