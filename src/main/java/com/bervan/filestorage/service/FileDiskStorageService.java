package com.bervan.filestorage.service;

import com.bervan.filestorage.model.FileUploadException;
import com.bervan.filestorage.model.Metadata;
import com.bervan.logging.JsonLogger;
import jakarta.annotation.PostConstruct;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FileDiskStorageService {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");

    @Value("${file.service.storage.folders}")
    private List<String> FOLDERS;

    @Value("${file.service.folder.automapping}")
    private String autoMappingStr;
    private Map<String, List<String>> autoMapping;

    @Value("${file.service.storage.folders.mapping}")
    private String folderMappingStr;
    private Map<String, String> folderMapping;

    @Value("${global-tmp-dir.file-storage-relative-path}")
    private String GLOBAL_TMP_DIR;

    public FileDiskStorageService() {

    }

    @PostConstruct
    public void init() {
        if (!FOLDERS.contains("main")) {
            throw new RuntimeException("Folder 'main' is required");
        }

        log.info("Folders: " + FOLDERS);

        // documents:/Documents/*;/Files/Documents/*,school:/School/*;/Files/School/*
        autoMapping = new HashMap<>();

        String[] folderMappings = autoMappingStr.split(",");
        for (String folderMapping : folderMappings) {
            String[] parts = folderMapping.split(":", 2);
            if (parts.length == 2) {
                String folderName = parts[0].trim();
                String[] patterns = parts[1].split(";");

                log.info("Auto mapping: " + folderName + " -> " + Arrays.toString(patterns));

                List<String> patternList = Arrays.stream(patterns)
                        .map(String::trim)
                        .collect(Collectors.toList());

                autoMapping.put(folderName, patternList);
            }
        }
        //example file.service.storage.folders.mapping=main:./storage/main,backup:./storage/backup,movies:/mnt/movies,temp:./tmp
        folderMapping = new HashMap<>();
        String[] folderMappings2 = folderMappingStr.split(",");
        for (String folderMapping2 : folderMappings2) {
            String[] parts = folderMapping2.split(":", 2);
            if (parts.length == 2) {
                String folderName = parts[0].trim();
                String path = parts[1].trim();
                folderMapping.put(folderName, path);
                log.info("Folder mapping: " + folderName + " -> " + path);
            }
        }

    }

    public String store(MultipartFile file, String path, String fileName) {
        String FOLDER = getStorageFolderPath(path);
        log.info("Saving on a disk: " + fileName + " in path: " + path);

        //????
        path = path.replaceAll(FOLDER, "");
        // ???
        if (!path.isBlank()) {
            if (!path.startsWith(File.separator)) {
                path = File.separator + path;
            }
        }

        fileName = getFileName(path, fileName);

        try {
            String destination = FOLDER + path + File.separator + fileName;
            log.info("Saving on a disk " + fileName + " in destination: " + destination);
            File fileTmp = new File(destination);
            File directory = new File(FOLDER + path + File.separator);
            directory.mkdirs();
            file.transferTo(fileTmp);
            log.info("Saved on a disk" + fileName + " in destination: " + fileTmp.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to store file", e);
            throw new FileUploadException(e.getMessage());
        }
        return fileName;
    }

    public String storeTmp(MultipartFile file, String fileName) {
        String FOLDER = getStorageFolderPath(GLOBAL_TMP_DIR);

        log.info("Saving tmp " + fileName);

        fileName = getFileName(GLOBAL_TMP_DIR, fileName);
        try {
            String destination = FOLDER + GLOBAL_TMP_DIR + File.separator + fileName;
            log.info("Saving " + fileName + " in destination: " + destination);
            File fileTmp = new File(destination);
            File directory = new File(FOLDER + GLOBAL_TMP_DIR + File.separator);
            directory.mkdirs();
            file.transferTo(fileTmp);
            log.info("Saved " + fileName + " in destination: " + fileTmp.getAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to store tmp file", e);
            throw new FileUploadException(e.getMessage());
        }
        return fileName;
    }

    public List<Metadata> getAllFilesInFolder() {
        List<Metadata> fileInfos = new ArrayList<>();
        for (String FOLDER : FOLDERS) {
            try {
                String path = folderMapping.get(FOLDER);
                scanDirectory(new File(path), fileInfos, path);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

        }

        return fileInfos;
    }

    private String getStorageFolderPath(String path) {
        String folderNameBasedOnRules = null;
        for (Map.Entry<String, List<String>> mappings : autoMapping.entrySet()) {
            String folderName = mappings.getKey();
            List<String> patterns = mappings.getValue();

            for (String pattern : patterns) {
                if (matchesPattern(path, pattern)) {
                    folderNameBasedOnRules = folderName;
                    break;
                }
            }
        }

        if (folderNameBasedOnRules == null) {
            folderNameBasedOnRules = "main";
        }

        return folderMapping.get(folderNameBasedOnRules);
    }

    private boolean matchesPattern(String path, String pattern) {
        // (ex. "/Movies/*")
        if (pattern.endsWith("/*")) {
            String basePattern = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(basePattern);
        }

        // (ex. "*/Videos")
        if (pattern.startsWith("*/")) {
            String endPattern = pattern.substring(2);
            return path.endsWith(endPattern);
        }

        // (ex. "/Movies/*/HD")
        if (pattern.contains("*")) {
            String regex = pattern.replace("*", ".*");
            return path.matches(regex);
        }

        // exact match
        return path.equals(pattern);
    }

    private void scanDirectory(File fileParent, List<Metadata> fileInfos, final String PATH_MAPPING) throws IOException {
        try (Stream<Path> paths = Files.walk(Paths.get(fileParent.getAbsolutePath()))) {
            Set<Path> collect = paths.filter(path -> !path.toAbsolutePath()
                            .equals(fileParent.toPath().toAbsolutePath()))
                    .collect(Collectors.toSet());
            for (Path path : collect) {
                File file = path.toFile();
                BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);

                FileTime creationTime = attr.creationTime();
                LocalDateTime creationDateTime = LocalDateTime.ofInstant(creationTime.toInstant(), ZoneId.systemDefault());

                if (file.isDirectory()) {
                    Metadata metadata = new Metadata(getAbsolutePathThatHaveToBeSavedInMetadata(file, PATH_MAPPING), file.getName(), null, creationDateTime, true);
                    fileInfos.add(metadata);
                    scanDirectory(file, fileInfos, PATH_MAPPING);
                } else {
                    Metadata metadata = new Metadata(getAbsolutePathThatHaveToBeSavedInMetadata(file, PATH_MAPPING), file.getName(), FilenameUtils.getExtension(file.getName()), creationDateTime, false);
                    fileInfos.add(metadata);
                }
            }
        }
    }

    private String getAbsolutePathThatHaveToBeSavedInMetadata(File file, final String PATH_MAPPING) {
        String absolutePath = file.getAbsolutePath();
        absolutePath = absolutePath.replace(PATH_MAPPING, "");
        String FOLDER = getStorageFolderPath(absolutePath);
        absolutePath = absolutePath.replace(FOLDER, "").replace(file.getName(), "");
        if (!absolutePath.startsWith(File.separator)) {
            absolutePath = File.separator + absolutePath;
        }
        return absolutePath;
    }


    public Path getFile(String path) {
        File file = new File(getDestination(path, ""));
        return file.toPath();
    }

    public Path getTmpFile(String filename) {
        File file = new File(getDestination(filename, GLOBAL_TMP_DIR));
        return file.toPath();
    }

    public boolean isTmpFile(String fileName) {
        return new File(getDestination(fileName, GLOBAL_TMP_DIR)).exists();
    }

    private String getDestination(String filename, String path) {
        String FOLDER;
        if (path == null || path.isBlank()) {
            FOLDER = getStorageFolderPath(filename);
        } else {
            FOLDER = getStorageFolderPath(path);
        }

        return FOLDER + path + File.separator + filename;
    }

    private String getFileName(String path, String fileName) {
        String extension = FilenameUtils.getExtension(fileName);
        String tempFileName = fileName;
        boolean fileExist = isFileWithTheName(fileName, path);
        int i = 1;
        while (fileExist) {
            tempFileName = fileName.substring(0, fileName.indexOf(extension) - 1) + "(" + i++ + ")." + extension;
            fileExist = isFileWithTheName(tempFileName, path);
        }

        return tempFileName;
    }

    private boolean isFileWithTheName(String fileName, String path) {
        return new File(getDestination(fileName, path)).exists();
    }

    public void delete(String path, String filename) {
        String FOLDER = getStorageFolderPath(path);
        path = FOLDER + File.separator + path.replaceAll(FOLDER, "");
        Path targetPath = Paths.get(path, filename);
        try {
            if (Files.exists(targetPath)) {
                Files.walkFileTree(targetPath, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                log.error("File or directory does not exists!");
                throw new RuntimeException("File or directory does not exists!");
            }
        } catch (IOException e) {
            log.warn("Cannot delete! " + e.getMessage());
            throw new RuntimeException(e);
        }
    }


//    public Path doBackup() throws IOException, InterruptedException {
//        String BACKUP_FOLDER = "";
//        if (!FOLDERS.contains("backup")) {
//            BACKUP_FOLDER = folderMapping.get("main");
//        } else {
//            BACKUP_FOLDER = folderMapping.get("backup");
//        }
//
//        if (!BACKUP_FOLDER.endsWith(File.separator)) {
//            BACKUP_FOLDER += File.separator;
//        }
//
//        String BACKUP_FILE = BACKUP_FOLDER + "backup.zip";
//        String[] env = {"PATH=/bin:/usr/bin/"};
//        deleteOldBackup(env, BACKUP_FILE);
//        createZip(env, BACKUP_FOLDER, BACKUP_FILE);
//
//        File file = new File(BACKUP_FILE);
//
//        return Paths.get(getAbsolutePathThatHaveToBeSavedInMetadata(file, BACKUP_FOLDER));
//    }

//    private void deleteOldBackup(String[] env, String BACKUP_FILE) throws IOException, InterruptedException {
//        String cmd = "rm " + BACKUP_FILE;
//        Process process = Runtime.getRuntime().exec(cmd, env);
//        process.waitFor();
//    }
//
//    private void createZip(String[] env, String STORAGE_FOLDER, String BACKUP_FILE) throws IOException, InterruptedException {
//        String FOLDER = getStorageFolderPath(STORAGE_FOLDER);
//        Thread.sleep(15000);
//        String cmd = "zip -r " + BACKUP_FILE + " " + FOLDER;
//        Process process = Runtime.getRuntime().exec(cmd, env);
//        process.waitFor();
//    }

    public void createEmptyDirectory(String path, String value) {
        String FOLDER = getStorageFolderPath(path);
        path = path.replaceAll(FOLDER, "");
        File directory = new File(FOLDER + path + File.separator + value);
        directory.mkdirs();
    }
}
