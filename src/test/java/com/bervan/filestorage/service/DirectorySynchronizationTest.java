package com.bervan.filestorage.service;

import com.bervan.common.user.UserRepository;
import com.bervan.filestorage.model.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DirectorySynchronizationTest {
    @TempDir
    Path root;

    private FileDiskStorageService disk() {
        FileDiskStorageService disk = new FileDiskStorageService();
        ReflectionTestUtils.setField(disk, "FOLDERS", List.of("main"));
        ReflectionTestUtils.setField(disk, "folderMapping", Map.of("main", root.toString()));
        return disk;
    }

    @Test
    void scansOnlySelectedSubtreeAndPreservesRepeatedNames() throws Exception {
        Files.createDirectories(root.resolve("Photos/Photos"));
        Files.writeString(root.resolve("Photos/Photos/Photos"), "test");
        Files.createDirectories(root.resolve("PhotosOther"));
        Files.writeString(root.resolve("PhotosOther/outside.txt"), "test");

        List<Metadata> files = disk().getAllFilesInFolder("/Photos/");
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(m -> m.getPath().equals("/Photos/Photos/")
                && m.getFilename().equals("Photos")));
        assertTrue(files.stream().allMatch(m -> m.getPath().startsWith("/Photos/")));
        assertEquals(5, disk().getAllFilesInFolder("/").size());
    }

    @Test
    void restoresExistingTrashRecordsAndQueriesOnlySelectedSubtree() throws Exception {
        Files.createDirectories(root.resolve("Photos"));
        Files.writeString(root.resolve("Photos/picture.txt"), "test");
        Metadata trashed = new Metadata("/Photos/", "picture.txt", "txt", LocalDateTime.now(), false);
        trashed.setInTrash(true);
        trashed.setInTrashStartDate(LocalDateTime.now());
        FileDBStorageService db = mock(FileDBStorageService.class);
        when(db.loadAllByPathPrefix("/Photos/")).thenReturn(Set.of(trashed));
        LoadStorageAndIntegrateWithDB sync =
                new LoadStorageAndIntegrateWithDB(db, disk(), mock(UserRepository.class));

        sync.synchronizeStorageWithDB("/Photos");

        assertFalse(trashed.isInTrash());
        assertNull(trashed.getInTrashStartDate());
        verify(db).loadAllByPathPrefix("/Photos/");
        verify(db).updateBatch(List.of(trashed));
        assertEquals(4L, trashed.getFileSize());
        assertNotNull(trashed.getModificationDate());
        verify(db, never()).load();
        verify(db, never()).insertBatch(anyList());
        verify(db, never()).deleteBatchPermanently(anyList());
    }

    @Test
    void refreshesExistingFileAttributesWithoutChangingItsDescription() throws Exception {
        Files.createDirectories(root.resolve("Photos"));
        Files.writeString(root.resolve("Photos/picture.txt"), "updated");
        Metadata existing = new Metadata("/Photos/", "picture.txt", "txt", LocalDateTime.MIN, false);
        existing.setFileSize(1L);
        existing.setDescription("Keep this description");
        FileDBStorageService db = mock(FileDBStorageService.class);
        when(db.loadAllByPathPrefix("/Photos/")).thenReturn(Set.of(existing));
        new LoadStorageAndIntegrateWithDB(db, disk(), mock(UserRepository.class))
                .synchronizeStorageWithDB("/Photos/");
        assertEquals(7L, existing.getFileSize());
        assertEquals("Keep this description", existing.getDescription());
        assertNotNull(existing.getModificationDate());
        verify(db).updateBatch(List.of(existing));
    }

    @Test
    void rejectsTraversalBeforeReadingOrChangingMetadata() {
        FileDBStorageService db = mock(FileDBStorageService.class);
        LoadStorageAndIntegrateWithDB sync =
                new LoadStorageAndIntegrateWithDB(db, disk(), mock(UserRepository.class));
        assertThrows(IllegalArgumentException.class, () -> sync.synchronizeStorageWithDB("/../outside"));
        verifyNoInteractions(db);
    }

    @Test
    void unavailableStorageRootStopsSynchronizationBeforeDatabaseChanges() {
        FileDBStorageService db = mock(FileDBStorageService.class);
        FileDiskStorageService disk = disk();
        ReflectionTestUtils.setField(disk, "folderMapping", Map.of("main", root.resolve("missing").toString()));
        LoadStorageAndIntegrateWithDB sync =
                new LoadStorageAndIntegrateWithDB(db, disk, mock(UserRepository.class));
        assertThrows(RuntimeException.class, () -> sync.synchronizeStorageWithDB("/"));
        verifyNoInteractions(db);
    }
}
