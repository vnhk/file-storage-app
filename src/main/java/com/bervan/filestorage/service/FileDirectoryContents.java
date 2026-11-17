package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.policy.FileStoragePolicies;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Includes intentionally untracked files in folder exports and size calculations. */
@Component
public class FileDirectoryContents {
    private final FileDBStorageService database;
    private final FileDiskStorageService disk;
    private final FileStoragePolicies policies;

    public FileDirectoryContents(FileDBStorageService database, FileDiskStorageService disk,
                                 FileStoragePolicies policies) {
        this.database = database;
        this.disk = disk;
        this.policies = policies;
    }

    public List<Metadata> load(String directory) throws IOException {
        Set<Metadata> tracked = database.loadAllByPathIncludingTrash(directory);
        List<Metadata> result = new ArrayList<>(tracked.stream().filter(file -> !file.isInTrash()).toList());
        Set<String> trackedNames = tracked.stream().map(Metadata::getFilename).collect(Collectors.toSet());
        Path physicalDirectory = disk.getFile(directory);
        if (!Files.isDirectory(physicalDirectory)) return result;

        List<Metadata> candidates = new ArrayList<>();
        try (var children = Files.list(physicalDirectory)) {
            for (Path child : children.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                String filename = child.getFileName().toString();
                Metadata file = new Metadata(directory, filename, FilenameUtils.getExtension(filename), null, false);
                candidates.add(file);
            }
        }
        var shouldTrack = policies.trackingFilter(candidates);
        for (Metadata file : candidates) {
            // Do not resurrect trashed records or include arbitrary files awaiting synchronization.
            if (!trackedNames.contains(file.getFilename()) && !shouldTrack.test(file)) {
                file.setFileSize(Files.size(disk.getFile(directory + file.getFilename())));
                result.add(file);
            }
        }
        return result;
    }
}
