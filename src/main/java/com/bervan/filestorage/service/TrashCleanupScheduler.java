package com.bervan.filestorage.service;

import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TrashCleanupScheduler {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final FileDBStorageService fileDBStorageService;

    public TrashCleanupScheduler(FileServiceManager fileServiceManager,
                                 FileDBStorageService fileDBStorageService) {
        this.fileServiceManager = fileServiceManager;
        this.fileDBStorageService = fileDBStorageService;
    }

    /**
     * Automatically permanently deletes files in trash older than 30 days.
     * Runs daily at 2:00 AM.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupExpiredTrash() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        log.info("Running daily trash cleanup for items deleted before: {}", cutoff);
        try {
            List<Metadata> expiredItems = fileDBStorageService.findExpiredTrash(cutoff);
            if (expiredItems.isEmpty()) {
                log.info("No expired trash items found.");
                return;
            }
            log.info("Found {} expired trash items to permanently delete.", expiredItems.size());
            for (Metadata item : expiredItems) {
                try {
                    fileServiceManager.deletePermanently(item);
                } catch (Exception e) {
                    log.error("Failed to permanently delete expired trash item: " + item.getFilename(), e);
                }
            }
        } catch (Exception e) {
            log.error("Error during scheduled trash cleanup", e);
        }
    }
}
