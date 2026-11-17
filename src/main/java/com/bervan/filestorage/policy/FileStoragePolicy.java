package com.bervan.filestorage.policy;

import com.bervan.filestorage.model.Metadata;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/** Module-specific storage rules. Excluding metadata never excludes the physical file. */
public interface FileStoragePolicy {
    default boolean shouldTrackMetadata(Metadata file) {
        return true;
    }

    /** Creates a filter without an operation file list; policies can inspect existing disk contents. */
    default Predicate<Metadata> trackingFilter() {
        return trackingFilter(List.of());
    }

    /** Creates an operation-local filter, including files not yet written during an import. */
    default Predicate<Metadata> trackingFilter(Collection<Metadata> files) {
        return this::shouldTrackMetadata;
    }

    default void beforeFileDelete(Metadata file) {
    }

    default void beforeDirectoryDelete(Metadata directory) {
    }

    /** Extensions potentially excluded in this directory, for the file browser notice. */
    default List<String> untrackedExtensions(String directory) {
        return List.of();
    }
}
