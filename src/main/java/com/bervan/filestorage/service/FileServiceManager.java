package com.bervan.filestorage.service;

import com.bervan.common.search.SearchService;
import com.bervan.common.service.BaseService;
import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.FileDownloadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.repository.MetadataRepository;
import com.bervan.ieentities.ExcelIEEntity;
import jakarta.transaction.Transactional;
import org.apache.commons.io.FilenameUtils;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileServiceManager extends BaseService<UUID, Metadata> {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;
    private final BervanLogger log;

    public FileServiceManager(FileDBStorageService fileDBStorageService, SearchService searchService, FileDiskStorageService fileDiskStorageService, BervanLogger log, MetadataRepository repository) {
        super(repository, searchService);
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
        this.log = log;
    }

    @Transactional
    public UploadResponse saveAndExtractZip(MultipartFile file, String description, final String path) throws IOException {
        if (!file.getOriginalFilename().endsWith(".zip")) {
            throw new RuntimeException("File is not a zip!");
        }

        UploadResponse uploadResponse = new UploadResponse();
        List<Metadata> savedFilesMetadata = new ArrayList<>();
        Path zipFile = null;
        try {
            String[] pathParts = file.getOriginalFilename().split(Pattern.quote(File.separator));
            String fileNameTmp = fileDiskStorageService.storeTmp(file, pathParts[pathParts.length - 1]);

            zipFile = fileDiskStorageService.getTmpFile(fileNameTmp);

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry zipEntry;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (zipEntry.getName().startsWith("__")) {
                        continue;
                    }
                    if (zipEntry.isDirectory()) {
                        continue;
                    }

                    String extractedFilename = zipEntry.getName();
                    String[] extractedPathParts = extractedFilename.split(Pattern.quote(File.separator));
                    String fileName = extractedPathParts[extractedPathParts.length - 1];
                    Path tempFilePath = Files.createTempFile("extracted_", "_" + fileName);

                    Files.copy(zis, tempFilePath, StandardCopyOption.REPLACE_EXISTING);

                    MultipartFile multipartFile = new MockMultipartFile(
                            extractedFilename,
                            extractedFilename,
                            Files.probeContentType(tempFilePath),
                            Files.newInputStream(tempFilePath)
                    );

                    UploadResponse saved = save(multipartFile, description, path);

                    savedFilesMetadata.addAll(saved.getMetadata());
                    Files.delete(tempFilePath);
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            for (Metadata filesToBeReverted : savedFilesMetadata) {
                Files.deleteIfExists(fileDiskStorageService.getFile(filesToBeReverted.getPath() + File.separator + filesToBeReverted.getFilename()));
            }
            throw new RuntimeException("Error processing ZIP file", e);
        } finally {
            if (zipFile != null) {
                Files.deleteIfExists(zipFile);
            }
        }

        uploadResponse.setCreateDate(LocalDateTime.now());
        uploadResponse.setMetadata(savedFilesMetadata);
        return uploadResponse;
    }

    @Transactional
    public UploadResponse save(MultipartFile file, String description, String path) {
        UploadResponse uploadResponse = new UploadResponse();
        List<Metadata> createdMetadata = new ArrayList<>();

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

        String filename = fileDiskStorageService.store(file, path, pathParts[pathParts.length - 1]);
        LocalDateTime createDate = LocalDateTime.now();
        uploadResponse.setCreateDate(createDate);

        Metadata stored = fileDBStorageService.store(createDate, path, filename, description, FilenameUtils.getExtension(filename), false);
        createdMetadata.add(stored);
        uploadResponse.setMetadata(createdMetadata);

        return uploadResponse;
    }

    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> getDirectoriesInPath(String path) {
        return fileDBStorageService.loadByPath(path).stream().filter(Metadata::isDirectory).collect(Collectors.toList());
    }

    public Path doBackup() throws IOException, InterruptedException {
        return fileDiskStorageService.doBackup();
    }

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
    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public Set<Metadata> load() {
        return fileDBStorageService.load();
    }

    @Override
    public void delete(Metadata item) {
        fileDBStorageService.delete(item);
        try {
            fileDiskStorageService.delete(item.getPath(), item.getFilename());
        } catch (Exception e) {
            log.error("Could not delete file from storage!", e);
        }
    }

    @Override
    public void saveIfValid(List<? extends ExcelIEEntity<UUID>> objects) {
        throw new RuntimeException("Unsupported");
    }

    //    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public Set<Metadata> loadByPath(String path) {
        return fileDBStorageService.loadByPath(path);
    }

    @Transactional
    public Metadata createEmptyDirectory(String path, String value) {
        Metadata metadata = fileDBStorageService.createEmptyDirectory(path, value);
        fileDiskStorageService.createEmptyDirectory(path, value);

        return metadata;
    }

//    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> loadVideoById(String videoId) {
        return fileDBStorageService.loadById(UUID.fromString(videoId));
    }

//    @PostFilter("(T(com.bervan.common.service.AuthService).hasAccess(filterObject.owners))")
    public List<Metadata> loadVideosByPathStartsWith(String path) {
        return fileDBStorageService.loadByPathStartsWith(path);
    }

    public Path getFile(UUID uuid) {
        try {
            Metadata metadata = fileDBStorageService.loadById(uuid).get(0);
            return fileDiskStorageService.getFile(metadata.getPath() + File.separator + metadata.getFilename());
        } catch (Exception e) {
            throw new FileDownloadException("Cannot get file: " + uuid);
        }
    }
}
