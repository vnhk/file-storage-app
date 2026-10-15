package com.bervan.filestorage.model;

import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class BervanMockMultiPartFile implements MultipartFile {
    private final String name;
    private final String originalFilename;
    @Nullable
    private final String contentType;
    private byte[] content;
    private InputStream inputStream;

    public BervanMockMultiPartFile(String name, @Nullable String originalFilename, @Nullable String contentType, InputStream inputStream) {
        Assert.hasLength(name, "Name must not be empty");
        this.name = name;
        this.inputStream = inputStream;
        this.originalFilename = originalFilename != null ? originalFilename : "";
        this.contentType = contentType;
    }

    public BervanMockMultiPartFile(String name, @Nullable String originalFilename, @Nullable String contentType, byte[] content) {
        Assert.hasLength(name, "Name must not be empty");
        this.name = name;
        this.content = content;
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
        if (content != null) {
            return content.length == 0;
        }
        return inputStream == null;
    }

    @Override
    public long getSize() {
        if (content != null) {
            return content.length;
        }
        try {
            return getBytes().length;
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        if (content != null) {
            return content;
        }
        if (inputStream != null) {
            content = inputStream.readAllBytes();
            return content;
        }
        return new byte[0];
    }

    public InputStream getInputStream() throws IOException {
        if (content != null) {
            return new ByteArrayInputStream(content);
        }
        return inputStream;
    }

    public void transferTo(File dest) throws IOException, IllegalStateException {
        if (content != null) {
            FileCopyUtils.copy(content, dest);
        } else if (inputStream != null) {
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                FileCopyUtils.copy(inputStream, fos);
            }
        }
    }
}

