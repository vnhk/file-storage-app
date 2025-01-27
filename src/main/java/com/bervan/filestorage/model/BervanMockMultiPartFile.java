package com.bervan.filestorage.model;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class BervanMockMultiPartFile implements MultipartFile {
    private final String name;
    private final String originalFilename;
    @Nullable
    private final String contentType;
    private final InputStream inputStream;

    public BervanMockMultiPartFile(String name, @Nullable String originalFilename, @Nullable String contentType, InputStream inputStream) {
        Assert.hasLength(name, "Name must not be empty");
        this.name = name;
        this.inputStream = inputStream;
        this.originalFilename = originalFilename != null ? originalFilename : "";
        this.contentType = contentType;
    }

    public String getName() {
        return this.name;
    }

    @NonNull
    public String getOriginalFilename() {
        return this.originalFilename;
    }

    @Nullable
    public String getContentType() {
        return this.contentType;
    }

    public boolean isEmpty() {
        return inputStream == null;
    }

    @Override
    public long getSize() {
        throw new RuntimeException("Method not supported!");
    }

    @Override
    public byte[] getBytes() throws IOException {
        throw new RuntimeException("Method not supported!");
    }

    public InputStream getInputStream() throws IOException {
        return inputStream;
    }

    public void transferTo(File dest) throws IOException, IllegalStateException {
        FileCopyUtils.copy(inputStream, new FileOutputStream(dest));
    }
}

