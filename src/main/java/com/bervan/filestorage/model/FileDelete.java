package com.bervan.filestorage.model;


public class FileDelete {
    private long documentId;
    private String filename;

    public FileDelete(long documentId, String filename) {
        this.documentId = documentId;
        this.filename = filename;
    }

    public long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(long documentId) {
        this.documentId = documentId;
    }
}
