package com.bervan.filestorage;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public final class MetadataDto {
    private String id;
    private String filename;
    private String path;
    private boolean directory;
    private String extension;
    private Long fileSize;
    private boolean encrypted;
    private String modificationDate;
}