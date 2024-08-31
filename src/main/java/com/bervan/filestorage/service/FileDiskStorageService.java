package com.bervan.filestorage.service;

import com.bervan.filestorage.model.FileDeleteException;
import com.bervan.filestorage.model.FileUploadException;
import com.bervan.filestorage.model.Metadata;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private String BACKUP_FILE;

    public String store(MultipartFile file, String path) {
        path = path.replaceAll(FOLDER, "");

        if (!path.isBlank()) {
            if (!path.startsWith(File.separator)) {
                path = File.separator + path;
            }
        }

        String fileName = getFileName(file.getOriginalFilename());
        try {
            String destination = getDestination(fileName);
            File fileTmp = new File(destination);
            File directory = new File(FOLDER + path + File.separator);
            directory.mkdirs();
            file.transferTo(fileTmp);
        } catch (IOException e) {
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
        if (absolutePath.startsWith(File.separator)) {
            absolutePath = absolutePath.replace(File.separator, "");
        }
        return absolutePath;
    }


    public Path getFile(String path) {
        File file = new File(getDestination(path));
        return file.toPath();
    }

    private String getDestination(String filename) {
        return FOLDER + File.separator + filename;
    }

    private String getFileName(String fileName) {
        String extension = FilenameUtils.getExtension(fileName);
        String tempFileName = fileName;
        boolean fileExist = isFileWithTheName(fileName);
        int i = 1;
        while (fileExist) {
            tempFileName = fileName.substring(0, fileName.indexOf(extension) - 1) + "(" + i++ + ")." + extension;
            fileExist = isFileWithTheName(tempFileName);
        }

        return tempFileName;
    }

    private boolean isFileWithTheName(String fileName) {
        return new File(getDestination(fileName)).exists();
    }

    public void delete(String filename) {
        String destination = getDestination(filename);
        File file = new File(destination);
        if (!file.delete()) {
            throw new FileDeleteException("File cannot be deleted!");
        }
    }

    public void deleteAll() {
        try {
            String destination = getDestination("");
            File file = new File(destination);
            FileUtils.deleteDirectory(file);
        } catch (IOException e) {
            throw new FileDeleteException("Files cannot be deleted!");
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
