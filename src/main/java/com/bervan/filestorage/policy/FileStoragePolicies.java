package com.bervan.filestorage.policy;

import com.bervan.filestorage.model.Metadata;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

@Component
public class FileStoragePolicies {
    private final List<FileStoragePolicy> policies;

    public FileStoragePolicies(List<FileStoragePolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    /** Creates a filter without an operation file list; policies can inspect existing disk contents. */
    public Predicate<Metadata> trackingFilter() {
        return trackingFilter(List.of());
    }

    public Predicate<Metadata> trackingFilter(Collection<Metadata> files) {
        List<Predicate<Metadata>> filters = policies.stream().map(p -> p.trackingFilter(files)).toList();
        // Directory records are required for navigation and explicit recursive deletion.
        return file -> file.isDirectory() || filters.stream().allMatch(filter -> filter.test(file));
    }

    public List<String> untrackedExtensions(String directory) {
        return policies.stream().flatMap(p -> p.untrackedExtensions(directory).stream()).distinct().sorted().toList();
    }

    public void beforeDelete(Metadata file) {
        for (FileStoragePolicy policy : policies) {
            if (file.isDirectory()) policy.beforeDirectoryDelete(file);
            else policy.beforeFileDelete(file);
        }
    }
}
