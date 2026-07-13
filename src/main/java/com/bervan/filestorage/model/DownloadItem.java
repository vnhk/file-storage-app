package com.bervan.filestorage.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class DownloadItem {
    private final Metadata metadata;
    private final Instant createdAt;

    public DownloadItem(Metadata metadata) {
        this.metadata = metadata;
        this.createdAt = Instant.now();
    }

    public boolean expired() {
        return createdAt.plus(24, ChronoUnit.HOURS).isBefore(Instant.now());
    }

    public Metadata getMetadata() {
        return metadata;
    }
}