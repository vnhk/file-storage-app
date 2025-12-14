package com.bervan.filestorage.model;

import com.bervan.common.model.BervanOwnedBaseEntity;
import com.bervan.common.model.PersistableTableOwnedData;
import com.bervan.history.model.AbstractBaseHistoryEntity;
import jakarta.persistence.*;
import jakarta.validation.constraints.Size;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

@Entity
@Inheritance(strategy = InheritanceType.TABLE_PER_CLASS)
@Table(
        name = "Metadata",
        uniqueConstraints =
        @UniqueConstraint(columnNames = {"path", "filename"})
)

public class Metadata extends BervanOwnedBaseEntity<UUID> implements PersistableTableOwnedData<UUID> {
    @Id
    private UUID id;
    private String filename;
    private String path;
    private boolean isDirectory;
    private String extension;
    private LocalDateTime createDate;
    private String userName;
    @Size(max = 2000)
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
    public Collection<? extends AbstractBaseHistoryEntity<UUID>> getHistoryEntities() {
        return new ArrayList<>();
    }

    @Override
    public Field getHistoryCollectionField() {
        return null;
    }

    @Override
    public Class<? extends AbstractBaseHistoryEntity<UUID>> getTargetHistoryEntityClass() {
        return null;
    }

    @Override
    public void setHistoryEntities(Collection<? extends AbstractBaseHistoryEntity<UUID>> abstractBaseHistoryEntities) {

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

    @Override
    public void setDeleted(Boolean value) {

    }
}
