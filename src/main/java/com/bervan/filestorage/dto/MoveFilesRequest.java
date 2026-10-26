package com.bervan.filestorage.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DTO representing a request to move multiple files/folders to a destination directory path.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MoveFilesRequest {
    private List<UUID> ids = new ArrayList<>();
    private String destPath;
}
