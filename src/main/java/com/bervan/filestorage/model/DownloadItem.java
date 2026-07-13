package com.bervan.filestorage.model;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class DownloadItem {
    private final Metadata metadata;
    private final Instant createdAt;
    private boolean downloaded;

    public DownloadItem(Metadata metadata) {
        this.metadata = metadata;
        this.createdAt = Instant.now();
    }

    public boolean expired() {
        return createdAt.plus(24, ChronoUnit.HOURS).isBefore(Instant.now());
    }

    public boolean alreadyDownloaded() {
        return downloaded;
    }

    public void markDownloaded() {
        this.downloaded = true;
    }

    public Metadata getMetadata() {
        return metadata;
    }
}
