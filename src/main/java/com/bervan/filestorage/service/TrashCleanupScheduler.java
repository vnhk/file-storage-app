package com.bervan.filestorage.service;

import com.bervan.common.user.User;
import com.bervan.common.user.UserRepository;
import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TrashCleanupScheduler {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final FileDBStorageService fileDBStorageService;
    private final UserRepository userRepository;

    public TrashCleanupScheduler(FileServiceManager fileServiceManager,
                                 FileDBStorageService fileDBStorageService,
                                 UserRepository userRepository) {
        this.fileServiceManager = fileServiceManager;
        this.fileDBStorageService = fileDBStorageService;
        this.userRepository = userRepository;
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
            // Background scheduled threads do not have an active SecurityContext.
            // Authenticate as system user with ROLE_USER so that @PreAuthorize("hasRole('USER')") on repository.delete allows deletion.
            User systemUser = userRepository.findByUsername("COMMON_USER")
                    .orElseGet(() -> userRepository.findAll().stream().findFirst().orElse(null));

            if (systemUser != null) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        systemUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            }

            List<Metadata> expiredItems = fileDBStorageService.findExpiredTrash(cutoff);
            if (expiredItems.isEmpty()) {
                log.info("No expired trash items found.");
                return;
            }
            log.info("Found {} expired trash items to permanently delete.", expiredItems.size());
            for (Metadata item : expiredItems) {
                try {
                    // Safety guard: ensure item is strictly marked as in trash before deleting
                    if (item != null && Boolean.TRUE.equals(item.isInTrash())) {
                        fileServiceManager.deletePermanently(item);
                    } else {
                        log.warn("SAFETY GUARD: Skipping item during scheduled cleanup because inTrash is not true: {}",
                                item != null ? item.getFilename() : "null");
                    }
                } catch (Exception e) {
                    log.error("Failed to permanently delete expired trash item: " + (item != null ? item.getFilename() : "null"), e);
                }
            }
        } catch (Exception e) {
            log.error("Error during scheduled trash cleanup", e);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
