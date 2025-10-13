package com.bervan.filestorage.service;

import com.bervan.common.search.SearchQueryOption;
import com.bervan.common.search.SearchRequest;
import com.bervan.common.search.SearchService;
import com.bervan.common.search.model.SearchOperation;
import com.bervan.common.search.model.SearchResponse;
import com.bervan.common.service.BaseService;
import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.BervanMockMultiPartFile;
import com.bervan.filestorage.model.FileDownloadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.repository.MetadataRepository;
import com.bervan.ieentities.ExcelIEEntity;
import jakarta.transaction.Transactional;
import org.apache.commons.io.FilenameUtils;
import org.springframework.security.access.prepost.PostFilter;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileServiceManager extends BaseService<UUID, Metadata> {
    private final FileDBStorageService fileDBStorageService;
    private final FileDiskStorageService fileDiskStorageService;
    private final SearchService searchService;
    private final BervanLogger log;

    public FileServiceManager(FileDBStorageService fileDBStorageService, SearchService searchService, FileDiskStorageService fileDiskStorageService, BervanLogger log, MetadataRepository repository, SearchService searchService1) {
        super(repository, searchService);
        this.fileDBStorageService = fileDBStorageService;
        this.fileDiskStorageService = fileDiskStorageService;
        this.log = log;
        this.searchService = searchService1;
    }

    public UploadResponse saveAndExtractZip(MultipartFile file, String description, final String path) throws IOException {
        if (!file.getOriginalFilename().endsWith(".zip")) {
            throw new RuntimeException("File is not a zip!");
        }

        UploadResponse uploadResponse = new UploadResponse();
        Path zipFile = null;
        List<Metadata> savedFilesMetadata = new ArrayList<>();

        try {
            String[] pathParts = file.getOriginalFilename().split(Pattern.quote(File.separator));
            String fileNameTmp = fileDiskStorageService.storeTmp(file, pathParts[pathParts.length - 1]);

            zipFile = fileDiskStorageService.getTmpFile(fileNameTmp);
            List<UploadResponse> uploadResponses = new ArrayList<>();

            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                ZipEntry zipEntry;

                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (zipEntry.getName().startsWith("__") || zipEntry.getName().startsWith(".") || zipEntry.isDirectory()) {
                        continue;
                    }

                    String extractedFilename = zipEntry.getName();
                    log.info("Started processing file: " + extractedFilename);

                    String[] extractedPathParts = extractedFilename.split(Pattern.quote(File.separator));
                    String fileName = extractedPathParts[extractedPathParts.length - 1];
                    Path tempFilePath = Files.createTempFile("extracted_", "_" + fileName);

                    log.info("File is BEING SAVED as temp: " + extractedFilename + " in " + tempFilePath);
                    Files.copy(zis, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("File is SAVED as temp: " + extractedFilename + " in " + tempFilePath);

                    log.info("File is being processed " + extractedFilename + " in destination path: " + path);
                    uploadResponses.add(processFile(tempFilePath, extractedFilename, description, path));
                    log.info("File is processed " + extractedFilename + " in destination path: " + path);

                    zis.closeEntry();
                }
            }

            uploadResponses.forEach(response -> savedFilesMetadata.addAll(response.getMetadata()));
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

    private UploadResponse processFile(Path tempFilePath, String extractedFilename, String description, String path) throws IOException {
        try {
            BervanMockMultiPartFile multipartFile = new BervanMockMultiPartFile(extractedFilename, extractedFilename, Files.probeContentType(tempFilePath), Files.newInputStream(tempFilePath));
            return save(multipartFile, description, path);
        } finally {
            Files.delete(tempFilePath);
        }
    }

    @Transactional
    public UploadResponse save(MultipartFile file, String description, String path) {
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

        filename = fileDiskStorageService.store(file, path, filename);
        LocalDateTime createDate = LocalDateTime.now();
        uploadResponse.setCreateDate(createDate);

        log.info("Saving metadata in database for file: " + filename + " and path: " + path);
        Metadata stored = fileDBStorageService.store(createDate, path, filename, description, FilenameUtils.getExtension(filename), false);
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

    public Set<Metadata> loadByPath(String path) {
        return fileDBStorageService.loadByPath(path);
    }

    @Transactional
    public Metadata createEmptyDirectory(String path, String value) {
        log.info("CREATING new empty directory: " + value + " in a path " + path);

        Metadata metadata = fileDBStorageService.createEmptyDirectory(path, value);
        fileDiskStorageService.createEmptyDirectory(path, value);

        log.info("CREATED new empty directory: " + value + " in a path " + path);

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
}
