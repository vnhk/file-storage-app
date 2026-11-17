package com.bervan.filestorage.service;

import com.bervan.common.search.SearchQueryOption;
import com.bervan.common.search.SearchRequest;
import com.bervan.common.search.SearchService;
import com.bervan.common.search.model.SearchOperation;
import com.bervan.common.search.model.SearchResponse;
import com.bervan.common.service.BaseService;
import com.bervan.filestorage.model.FileDownloadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.policy.FileStoragePolicies;
import com.bervan.filestorage.model.TrashConflictException;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.repository.MetadataRepository;
import com.bervan.ieentities.ExcelIEEntity;
import com.bervan.logging.JsonLogger;
import jakarta.transaction.Transactional;
import org.apache.commons.io.FilenameUtils;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class FileServiceManager extends BaseService<UUID, Metadata> {
    protected final FileDBStorageService fileDBStorageService;
    protected final FileDiskStorageService fileDiskStorageService;
    protected final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    protected final SearchService searchService;
    private final FileStoragePolicies policies;
    private final FileZipImportService zipImportService;
    private final FileDirectoryContents directoryContents;
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");

    public FileServiceManager(FileDBStorageService fileDBStorageService, SearchService searchService,
                              FileDiskStorageService fileDiskStorageService, MetadataRepository repository,
                              LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB,
                              FileStoragePolicies policies, FileZipImportService zipImportService,
                              FileDirectoryContents directoryContents) {
        super(repository, searchService);
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
        this.searchService = searchService;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        this.policies = policies;
        this.zipImportService = zipImportService;
        this.directoryContents = directoryContents;
    }

//    to be done once owners approach chosen
//    @Scheduled(fixedDelay = 2000000000)
//    public void synchronizeStorageWithDBAtStartUp() {
//        log.info("Synchronize storage with DB at start up");
//        loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
//        log.info("Storage synchronized with DB");
//    }
//
//    @Scheduled(cron = "0 0 0 * * *")
//    public void synchronizeStorageWithDBAtMidnight() {
//        log.info("Synchronize storage with DB at midnight");
//        loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
//        log.info("Storage synchronized with DB");
//    }

    public UploadResponse saveAndExtractZip(MultipartFile file, String description, final String path) throws IOException {
        return saveAndExtractZip(file, description, path, false);
    }

    public UploadResponse saveAndExtractZip(MultipartFile file, String description, final String path, boolean overwriteTrash) throws IOException {
        return zipImportService.extract(file, description, path, overwriteTrash, this);
    }

    @Transactional
    public UploadResponse save(MultipartFile file, String description, String path) {
        return save(file, description, path, false);
    }

    @Transactional
    public UploadResponse save(MultipartFile file, String description, String path, boolean overwriteTrash) {
        return saveWithTrackingFilter(file, description, path, overwriteTrash, policies.trackingFilter());
    }

    @Transactional
    UploadResponse saveWithTrackingFilter(MultipartFile file, String description, String path,
                                                  boolean overwriteTrash, Predicate<Metadata> shouldTrack) {
        log.info("FILE PATH SEPARATOR: " + File.separator);
        log.info("Saving file in DB and on a disk: " + path);
        UploadResponse uploadResponse = new UploadResponse();
        List<Metadata> createdMetadata = new ArrayList<>();

        log.info("Building new destination path....");
        String[] pathParts = file.getOriginalFilename().split(Pattern.quote(File.separator));
        List<Metadata> directoriesInPath = getDirectoriesInPath(path);
        String newPath = path;
        for (String pathPart : pathParts) {
            if (pathPart.equals(pathParts[pathParts.length - 1])) {
                break; //last is real filename
            }

            newPath += File.separator + pathPart;
            if (directoriesInPath.stream().noneMatch(e -> e.getFilename().equals(pathPart))) {
                Metadata emptyDirectory = createEmptyDirectory(path, pathPart);
                createdMetadata.add(emptyDirectory);
            }
            path = newPath;
        }
        String filename = pathParts[pathParts.length - 1];
        log.info("New destination path: " + path);
        log.info("Extracted filename: " + filename);

        // Check for collision with items in trash
        String cleanDestPath = path.endsWith(File.separator) ? path : path + File.separator;
        List<Metadata> trashedItems = fileDBStorageService.loadByPathAndFilenameInTrash(cleanDestPath, filename);
        if (!trashedItems.isEmpty()) {
            if (!overwriteTrash) {
                Metadata trashed = trashedItems.get(0);
                throw new TrashConflictException(trashed.getId(), trashed.getFilename(), trashed.getPath());
            } else {
                for (Metadata trashed : trashedItems) {
                    deletePermanently(trashed);
                }
            }
        }

        filename = fileDiskStorageService.store(file, path, filename);
        LocalDateTime createDate = LocalDateTime.now();
        uploadResponse.setCreateDate(createDate);

        Metadata candidate = new Metadata(cleanDestPath, filename, FilenameUtils.getExtension(filename), createDate, false);
        if (!shouldTrack.test(candidate)) {
            uploadResponse.setMetadata(createdMetadata);
            return uploadResponse;
        }

        log.info("Saving metadata in database for file: " + filename + " and path: " + path);
        Metadata stored = fileDBStorageService.store(createDate, path, filename, description, FilenameUtils.getExtension(filename), false);
        long fileSize = file.getSize();
        if (fileSize <= 0) {
            try {
                Path savedFile = fileDiskStorageService.getFile(path + File.separator + filename);
                fileSize = Files.size(savedFile);
            } catch (Exception e) {
                log.warn("Could not determine file size from disk for: " + filename);
            }
        }
        stored.setFileSize(fileSize);
        fileDBStorageService.update(stored);
        createdMetadata.add(stored);
        uploadResponse.setMetadata(createdMetadata);

        return uploadResponse;
    }

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> getDirectoriesInPath(String path) {
        return fileDBStorageService.loadByPath(path).stream().filter(Metadata::isDirectory).collect(Collectors.toList());
    }

    public Optional<Metadata> getParent(Metadata metadata) {
        String path = metadata.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String lastFolder = path.substring(path.lastIndexOf("/") + 1);
        String remainingPath = path.substring(0, path.lastIndexOf("/"));

        SearchRequest searchRequest = new SearchRequest();
        searchRequest.addCriterion("G1", Metadata.class, "path",
                SearchOperation.EQUALS_OPERATION, remainingPath);
        searchRequest.addCriterion("G1", Metadata.class, "filename",
                SearchOperation.EQUALS_OPERATION, lastFolder);
        searchRequest.addCriterion("G1", Metadata.class, "isDirectory",
                SearchOperation.EQUALS_OPERATION, true);

        SearchQueryOption options = new SearchQueryOption(Metadata.class);
        options.setSortField("filename");

        SearchResponse<Metadata> response = searchService.search(searchRequest, options);

        List<Metadata> resultList = response.getResultList();
        if (resultList.size() == 1) {
            return Optional.of(resultList.get(0));
        } else {
            return Optional.empty();
        }
    }

//    public Path doBackup() throws IOException, InterruptedException {
//        return fileDiskStorageService.doBackup();
//    }

    @Override
    public void save(List<Metadata> data) {
        throw new RuntimeException("Use store method.");
    }

    @Override
    public Metadata save(Metadata data) {
        throw new RuntimeException("Use store method.");
    }

    public Metadata updateMetadata(Metadata data) {
        return fileDBStorageService.update(data);
    }

    @Override
    @Transactional
    public void delete(Metadata item) {
        log.info("Moving item to trash: {}{}", item.getPath(), item.getFilename());
        fileDBStorageService.moveToTrash(item);
    }

    @Transactional
    public void deletePermanently(Metadata item) {
        if (item == null) {
            return;
        }

        // Additional safety check: ensure inTrash is strictly true (neither null nor false)
        if (!Boolean.TRUE.equals(item.isInTrash())) {
            log.error("SAFETY GUARD: Refusing to permanently delete item that is NOT in trash! Id: {}, Path: {}{}, inTrash: {}",
                    item.getId(), item.getPath(), item.getFilename(), item.isInTrash());
            throw new IllegalStateException("SAFETY GUARD: Cannot permanently delete item '" + item.getFilename() +
                    "' because inTrash is not true (value: " + item.isInTrash() + ")!");
        }

        policies.beforeDelete(item);
        log.info("Permanently deleting item: {}{}", item.getPath(), item.getFilename());
        if (item.isDirectory()) {
            String p = item.getPath() != null ? item.getPath() : "/";
            if (!p.startsWith("/")) p = "/" + p;
            if (!p.endsWith("/")) p = p + "/";
            String childPath = p + item.getFilename() + "/";
            Set<Metadata> children = fileDBStorageService.loadAllByPathIncludingTrash(childPath);
            for (Metadata child : children) {
                if (Boolean.TRUE.equals(child.isInTrash())) {
                    deletePermanently(child);
                }
            }
        }
        fileDBStorageService.deletePermanently(item);
        try {
            fileDiskStorageService.delete(item.getPath(), item.getFilename());
        } catch (Exception e) {
            log.error("Could not delete file from storage: " + item.getFilename(), e);
        }
    }

    @Transactional
    public void restoreFromTrash(Metadata item) {
        log.info("Restoring item from trash: {}{}", item.getPath(), item.getFilename());
        List<Metadata> active = fileDBStorageService.loadByPathAndFilename(item.getPath(), item.getFilename())
                .stream().filter(m -> !m.isInTrash()).toList();
        if (!active.isEmpty()) {
            throw new IllegalStateException("Cannot restore: an active item with name '" + item.getFilename() + "' already exists in " + item.getPath());
        }
        fileDBStorageService.restoreFromTrash(item);
    }

    public Set<Metadata> loadTrash() {
        return fileDBStorageService.loadTrash();
    }

    @Transactional
    public void emptyTrash() {
        Set<Metadata> trashItems = fileDBStorageService.loadTrash();
        log.info("Emptying trash: found {} items to delete permanently.", trashItems.size());
        for (Metadata item : trashItems) {
            if (item != null && Boolean.TRUE.equals(item.isInTrash())) {
                deletePermanently(item);
            } else {
                log.warn("SAFETY GUARD: Skipping item during emptyTrash because inTrash is not true: {}",
                        item != null ? item.getFilename() : "null");
            }
        }
    }

    @Override
    public void saveIfValid(List<? extends ExcelIEEntity<UUID>> objects) {
        throw new RuntimeException("Unsupported");
    }

    public Set<Metadata> loadByPath(String path) {
        return fileDBStorageService.loadByPath(path);
    }

    @Transactional
    public Metadata createEmptyDirectory(String path, String value) {
        log.debug("CREATING new empty directory: " + value + " in a path " + path);

        Metadata metadata = fileDBStorageService.createEmptyDirectory(path, value);
        fileDiskStorageService.createEmptyDirectory(path, value);

        log.debug("CREATED new empty directory: " + value + " in a path " + path);

        return metadata;
    }

    public Path getFile(UUID uuid) {
        try {
            Metadata metadata = fileDBStorageService.loadById(uuid).get(0);
            return fileDiskStorageService.getFile(metadata.getPath() + File.separator + metadata.getFilename());
        } catch (Exception e) {
            throw new FileDownloadException("Cannot get file: " + uuid);
        }
    }

    public Metadata getMetadata(UUID uuid) {
        return fileDBStorageService.loadById(uuid).get(0);
    }

    public Path getFile(Metadata metadata) {
        return fileDiskStorageService.getFile(metadata.getPath() + File.separator + metadata.getFilename());
    }

    @Transactional
    public void renameFile(Metadata metadata, String newFilename) {
        String cleanPath = metadata.getPath();
        if (cleanPath != null && !cleanPath.endsWith(File.separator)) {
            cleanPath += File.separator;
        }
        List<Metadata> existingInDb = fileDBStorageService.loadByPathAndFilename(cleanPath, newFilename)
                .stream().filter(m -> !Boolean.TRUE.equals(m.isInTrash()) && !m.getId().equals(metadata.getId()))
                .toList();
        if (!existingInDb.isEmpty()) {
            throw new IllegalArgumentException("File with name '" + newFilename + "' already exists in " + cleanPath);
        }

        Path source = getFile(metadata);
        fileDiskStorageService.renameFile(source, newFilename);
        String oldExtension = metadata.getExtension();
        metadata.setFilename(newFilename);
        metadata.setExtension(FilenameUtils.getExtension(newFilename));
        metadata.setModificationDate(LocalDateTime.now());
        updateMetadata(metadata);

        // If directory, update all children paths
        if (metadata.isDirectory()) {
            String oldChildPath = metadata.getPath();
            if (!oldChildPath.endsWith("/")) oldChildPath += "/";
            oldChildPath += metadata.getFilename();
            // Note: children paths reference old filename, but since we renamed
            // the directory on disk, the path mapping still works via folder resolution
        }
    }

    public void updateFileContent(Metadata metadata, byte[] newContent) {
        Path file = getFile(metadata);
        fileDiskStorageService.overwriteFile(file, newContent);
        metadata.setModificationDate(LocalDateTime.now());
        updateMetadata(metadata);
    }

    @Transactional
    public Metadata copyFileToPath(Metadata metadata, String destPath) {
        Path source = getFile(metadata);
        String destFullPath = destPath + File.separator + metadata.getFilename();
        Path destination = fileDiskStorageService.getFile(destFullPath);

        fileDiskStorageService.copyFile(source, destination);

        Metadata newMetadata = fileDBStorageService.store(
                LocalDateTime.now(), destPath, metadata.getFilename(),
                metadata.getDescription(), metadata.getExtension(), metadata.isDirectory()
        );

        if (metadata.isDirectory()) {
            String srcChildPath = metadata.getPath();
            if (!srcChildPath.endsWith("/")) srcChildPath += "/";
            srcChildPath += metadata.getFilename();

            String destChildPath = destPath;
            if (!destChildPath.endsWith("/")) destChildPath += "/";
            destChildPath += metadata.getFilename();

            Set<Metadata> children = fileDBStorageService.loadByPath(srcChildPath);
            for (Metadata child : children) {
                copyFileToPath(child, destChildPath);
            }
        }

        return newMetadata;
    }

    @Transactional
    public void moveFileToPath(Metadata metadata, String destPath) {
        Path source = getFile(metadata);
        String destFullPath = destPath + File.separator + metadata.getFilename();
        Path destination = fileDiskStorageService.getFile(destFullPath);

        if (metadata.isDirectory()) {
            String srcChildPath = metadata.getPath();
            if (!srcChildPath.endsWith("/")) srcChildPath += "/";
            srcChildPath += metadata.getFilename();

            String newChildPath = destPath;
            if (!newChildPath.endsWith("/")) newChildPath += "/";
            newChildPath += metadata.getFilename();

            Set<Metadata> children = fileDBStorageService.loadByPath(srcChildPath);
            for (Metadata child : children) {
                moveFileToPath(child, newChildPath);
            }
        }

        fileDiskStorageService.moveFile(source, destination);
        metadata.setPath(destPath);
        metadata.setModificationDate(LocalDateTime.now());
        updateMetadata(metadata);
    }

    public byte[] readFile(Metadata metadata) {
        Path file = fileDiskStorageService.getFile(metadata.getPath() + File.separator + metadata.getFilename());
        try {
            FileInputStream fis = new FileInputStream(file.toFile());
            return fis.readAllBytes();
        } catch (Exception e) {
            log.error("Cannot get file: " + metadata.getId() + ", path = " + file.toString(), e);
            throw new FileDownloadException("Cannot get file: " + metadata.getId());
        }
    }

    public void addFilesToZip(List<UUID> ids, ZipOutputStream zos) {
        for (UUID id : ids) {
            try {
                Metadata m = getMetadata(id);
                if (m.isDirectory()) {
                    addFolderToZip(m, m.getFilename(), zos);
                } else {
                    addFileToZip(m, m.getFilename(), zos);
                }
            } catch (Exception e) {
                log.warn("Failed to get top-level metadata or process zip entry for ID: " + id, e);
            }
        }
    }

    private void addFolderToZip(Metadata folder, String basePathInZip, ZipOutputStream zos) throws IOException {
        // Add an entry for the directory itself (ensures empty dirs are preserved)
        String dirEntryName = basePathInZip.endsWith("/") ? basePathInZip : basePathInZip + "/";
        zos.putNextEntry(new ZipEntry(dirEntryName));
        zos.closeEntry();
        zos.flush();

        String p = folder.getPath() != null ? folder.getPath() : "/";
        String sep = "/".equals(p) ? "" : "/";
        if (p.endsWith("/")) {
            sep = "";
        }
        String childPath = p + sep + folder.getFilename();

        // Load immediate children and recurse
        List<Metadata> children = directoryContents.load(childPath + "/");
        if (children.isEmpty()) {
            return;
        }

        for (Metadata child : children) {
            String entryName = dirEntryName + child.getFilename();
            try {
                if (child.isDirectory()) {
                    addFolderToZip(child, entryName, zos);
                } else {
                    addFileToZip(child, entryName, zos);
                }
            } catch (Exception e) {
                log.warn("Failed to add child to zip: " + entryName, e);
            }
        }
    }

    private void addFileToZip(Metadata fileMeta, String entryName, ZipOutputStream zos) throws IOException {
        Path file = getFile(fileMeta);
        if (java.nio.file.Files.exists(file) && java.nio.file.Files.isRegularFile(file)) {
            zos.putNextEntry(new ZipEntry(entryName));
            java.nio.file.Files.copy(file, zos);
            zos.closeEntry();
            zos.flush();
        } else {
            log.warn("File does not exist or is not a regular file on disk: " + (file != null ? file.toString() : "null") + " for metadata: " + fileMeta.getFilename());
        }
    }

    @Transactional
    public Long calculateAndUpdateFolderSize(UUID folderId) {
        Metadata folder = getMetadata(folderId);
        if (folder == null) {
            throw new IllegalArgumentException("Folder not found: " + folderId);
        }
        if (!folder.isDirectory()) {
            return folder.getFileSize() != null ? folder.getFileSize() : 0L;
        }

        long totalSize = calculateDirectorySizeRecursive(folder);
        folder.setFileSize(totalSize);
        updateMetadata(folder);
        return totalSize;
    }

    private long calculateDirectorySizeRecursive(Metadata folder) {
        String p = folder.getPath() != null ? folder.getPath() : "/";
        String sep = "/".equals(p) ? "" : "/";
        if (p.endsWith("/")) {
            sep = "";
        }
        String childPath = p + sep + folder.getFilename() + "/";

        List<Metadata> children;
        try {
            children = directoryContents.load(childPath);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Cannot calculate directory size: " + childPath, e);
        }
        if (children.isEmpty()) {
            return 0L;
        }

        long totalSum = 0L;
        for (Metadata child : children) {
            if (child.isDirectory()) {
                // Recursively compute subfolder size and persist it
                long subFolderSize = calculateDirectorySizeRecursive(child);
                child.setFileSize(subFolderSize);
                updateMetadata(child);
                totalSum += subFolderSize;
            } else {
                long size = (child.getFileSize() != null && child.getFileSize() > 0)
                        ? child.getFileSize()
                        : 0L;

                // Fallback to disk if fileSize is not set in DB
                if (size == 0L) {
                    try {
                        Path file = getFile(child);
                        if (java.nio.file.Files.exists(file) && java.nio.file.Files.isRegularFile(file)) {
                            size = java.nio.file.Files.size(file);
                            child.setFileSize(size);
                            if (child.getId() != null) updateMetadata(child);
                        }
                    } catch (Exception e) {
                        log.warn("Could not determine file size from disk for: " + child.getFilename(), e);
                    }
                }
                totalSum += size;
            }
        }
        return totalSum;
    }
}
