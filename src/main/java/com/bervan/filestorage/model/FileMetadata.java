package com.bervan.filestorage.model;

import jakarta.validation.constraints.NotEmpty;
import org.springframework.web.multipart.MultipartFile;

public class FileMetadata {
    @NotEmpty(message = "Document id cannot be empty!")
    private Long documentId;
    @NotEmpty(message = "User name cannot be empty!")
    private String userName;
    @NotEmpty(message = "File cannot be empty!")
    private MultipartFile file;

    public FileMetadata(Long documentId, String userName, MultipartFile file) {
        this.documentId = documentId;
        this.userName = userName;
        this.file = file;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public MultipartFile getFile() {
        return file;
    }

    public void setFile(MultipartFile file) {
        this.file = file;
    }
}
