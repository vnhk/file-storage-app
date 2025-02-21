package com.bervan.filestorage.service;

import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.FileUploadException;
import com.bervan.filestorage.model.Metadata;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class FileDiskStorageService {
    @Value("${file.service.storage.folder}")
    private String FOLDER;
    @Value("${global-tmp-dir.file-storage-relative-path}")
    private String GLOBAL_TMP_DIR;
    private String BACKUP_FILE;
    private final BervanLogger log;

    public FileDiskStorageService(BervanLogger log) {
        this.log = log;
    }

    public String store(MultipartFile file, String path, String fileName) {
        log.info("Saving on a disk: " + fileName + " in path: " + path);

        path = path.replaceAll(FOLDER, "");

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
            log.error(e);
            throw new FileUploadException(e.getMessage());
        }
        return fileName;
    }

    public String storeTmp(MultipartFile file, String fileName) {
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
            log.error(e);
            throw new FileUploadException(e.getMessage());
        }
        return fileName;
    }

    public List<Metadata> getAllFilesInFolder() {
        List<Metadata> fileInfos = new ArrayList<>();
        try {
            scanDirectory(new File(FOLDER), fileInfos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return fileInfos;
    }

    private void scanDirectory(File fileParent, List<Metadata> fileInfos) throws IOException {
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
                    fileInfos.add(new Metadata(getAbsolutePathThatHaveToBeSavedInMetadata(file), file.getName(), null, creationDateTime, true));
                    scanDirectory(file, fileInfos);
                } else {
                    fileInfos.add(new Metadata(getAbsolutePathThatHaveToBeSavedInMetadata(file), file.getName(), FilenameUtils.getExtension(file.getName()), creationDateTime, false));
                }
            }
        }
    }

    private String getAbsolutePathThatHaveToBeSavedInMetadata(File file) {
        String absolutePath = file.getAbsolutePath();
        absolutePath = absolutePath.replace(FOLDER, "").replace(file.getName(), "");
        if (absolutePath.startsWith(File.separator) && absolutePath.length() > 2) {
            absolutePath = absolutePath.substring(1, absolutePath.length() - 1);
        }
        return absolutePath;
    }


    public Path getFile(String path) {
        File file = new File(getDestination(path, ""));
        return file.toPath();
    }

    public Path getTmpFile(String path) {
        File file = new File(getDestination(path, GLOBAL_TMP_DIR));
        return file.toPath();
    }

    private String getDestination(String filename, String path) {
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


    public Path doBackup() throws IOException, InterruptedException {
        BACKUP_FILE = FOLDER + "backup.zip";
        String[] env = {"PATH=/bin:/usr/bin/"};
        deleteOldBackup(env);
        createZip(env);

        File file = new File(BACKUP_FILE);

        return Paths.get(getAbsolutePathThatHaveToBeSavedInMetadata(file));
    }

    private void deleteOldBackup(String[] env) throws IOException, InterruptedException {
        String cmd = "rm " + BACKUP_FILE;
        Process process = Runtime.getRuntime().exec(cmd, env);
        process.waitFor();
    }

    private void createZip(String[] env) throws IOException, InterruptedException {
        Thread.sleep(15000);
        String cmd = "zip -r " + BACKUP_FILE + " " + FOLDER;
        Process process = Runtime.getRuntime().exec(cmd, env);
        process.waitFor();
    }

    public void createEmptyDirectory(String path, String value) {
        path = path.replaceAll(FOLDER, "");
        File directory = new File(FOLDER + path + File.separator + value);
        directory.mkdirs();
    }
}
