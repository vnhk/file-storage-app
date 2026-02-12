package com.bervan.filestorage.view;

import com.bervan.asynctask.AsyncTask;
import com.bervan.asynctask.AsyncTaskService;
import com.bervan.common.view.AbstractPageView;
import com.bervan.filestorage.model.BervanMockMultiPartFile;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.logging.JsonLogger;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.FileBuffer;
import com.vaadin.flow.component.upload.receivers.MultiFileMemoryBuffer;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public abstract class UploadComponent extends AbstractPageView {
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final AsyncTaskService asyncTaskService;
    private final String path;
    private String[] setSupportedFiles = null;

    public UploadComponent(FileServiceManager fileServiceManager, AsyncTaskService asyncTaskService, String path) {
        this.fileServiceManager = fileServiceManager;
        this.asyncTaskService = asyncTaskService;
        this.path = path;
    }

    public void open() {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");

        VerticalLayout dialogLayout = new VerticalLayout();
        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        TextArea description = new TextArea("Description");
        description.setWidth("100%");

        // --- Single file upload ---
        VerticalLayout singleFileLayout = buildSingleFileLayout(description, dialog);

        // --- Folder upload ---
        VerticalLayout folderLayout = buildFolderUploadLayout(description, dialog);

        // --- Tabs ---
        Tab singleTab = new Tab("File");
        Tab folderTab = new Tab("Folder");
        Tabs tabs = new Tabs(singleTab, folderTab);

        folderLayout.setVisible(false);

        tabs.addSelectedChangeListener(event -> {
            boolean isFolderTab = event.getSelectedTab() == folderTab;
            singleFileLayout.setVisible(!isFolderTab);
            folderLayout.setVisible(isFolderTab);
        });

        dialogLayout.add(headerLayout, description, tabs, singleFileLayout, folderLayout);
        dialog.add(dialogLayout);
        dialog.open();
    }

    private VerticalLayout buildSingleFileLayout(TextArea description, Dialog dialog) {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);

        FileBuffer buffer = new FileBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(setSupportedFiles);
        List<MultipartFile> holder = new ArrayList<>();

        Checkbox extractCheckbox = new Checkbox("Extract file");
        extractCheckbox.setVisible(false);

        upload.addSucceededListener(event -> {
            if (!holder.isEmpty()) {
                holder.clear();
            }

            try {
                InputStream inputStream = buffer.getInputStream();
                holder.add(new MultipartFile() {
                    @Override
                    public String getName() { return event.getFileName(); }
                    @Override
                    public String getOriginalFilename() { return event.getFileName(); }
                    @Override
                    public String getContentType() { return event.getMIMEType(); }
                    @Override
                    public boolean isEmpty() { return false; }
                    @Override
                    public long getSize() { return event.getContentLength(); }
                    @Override
                    public byte[] getBytes() { throw new RuntimeException("Not supported"); }
                    @Override
                    public InputStream getInputStream() { return inputStream; }
                    @Override
                    public void transferTo(File dest) throws IOException {
                        transferTo(dest.toPath());
                    }
                });

                extractCheckbox.setVisible(event.getFileName().endsWith(".zip"));
            } catch (Exception e) {
                log.error("Error uploading file: " + e.getMessage(), e);
                showErrorNotification("Error uploading file: " + e.getMessage());
            }
        });

        Button save = new Button("Save and upload");
        save.addClassName("option-button");

        save.addClickListener(buttonClickEvent -> {
            if (holder.isEmpty()) {
                showWarningNotification("Please attach a file!");
                return;
            }

            MultipartFile uploadedFile = holder.get(0);
            dialog.close();

            SecurityContext context = SecurityContextHolder.getContext();
            UI ui = UI.getCurrent();
            AsyncTask newAsyncTask = asyncTaskService.createAndStoreAsyncTask();
            showPrimaryNotification("Upload in progress... You will be notified.");

            new Thread(() -> {
                SecurityContextHolder.setContext(context);
                AsyncTask asyncTask = asyncTaskService.setInProgress(newAsyncTask,
                        "Uploading file: " + uploadedFile.getOriginalFilename());
                try {
                    if (extractCheckbox.isVisible() && extractCheckbox.getValue()
                            && uploadedFile.getOriginalFilename().endsWith(".zip")) {
                        UploadResponse savedZip = fileServiceManager.saveAndExtractZip(
                                uploadedFile, description.getValue(), path);
                        List<Metadata> addedInCurrentPath = savedZip.getMetadata().stream()
                                .filter(e -> e.getPath().equals(path)).toList();
                        ui.access(() -> postSavedZipActions(addedInCurrentPath));
                    } else {
                        UploadResponse saved = fileServiceManager.save(
                                uploadedFile, description.getValue(), path);
                        ui.access(() -> postSaveActions(saved));
                    }

                    asyncTaskService.setFinished(asyncTask,
                            "File uploaded successfully: " + uploadedFile.getOriginalFilename());
                    ui.access(this::postSaveActions);
                } catch (Exception e) {
                    log.error("Failed to save a file: ", e);
                    asyncTaskService.setFailed(asyncTask, e.getMessage());
                    ui.access(() -> showErrorNotification("Failed to save a file: " + e.getMessage()));
                }
            }).start();
        });

        layout.add(upload, extractCheckbox, save);
        return layout;
    }

    private VerticalLayout buildFolderUploadLayout(TextArea description, Dialog dialog) {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);

        MultiFileMemoryBuffer multiBuffer = new MultiFileMemoryBuffer();
        Upload folderUpload = new Upload(multiBuffer);
        folderUpload.setMaxFiles(10000);
        folderUpload.setAcceptedFileTypes(setSupportedFiles);

        // Enable folder selection via webkitdirectory and rename files to include relative paths
        folderUpload.getElement().executeJs(
                "const el = this;" +
                "const input = el.shadowRoot ? el.shadowRoot.querySelector('input[type=file]') : el.querySelector('input[type=file]');" +
                "if(input) {" +
                "  input.setAttribute('webkitdirectory', '');" +
                "  input.setAttribute('directory', '');" +
                "  input.setAttribute('mozdirectory', '');" +
                "}" +
                "const orig = el._addFiles ? el._addFiles.bind(el) : null;" +
                "if(orig) {" +
                "  el._addFiles = function(files) {" +
                "    const renamed = [];" +
                "    for(let i = 0; i < files.length; i++) {" +
                "      const f = files[i];" +
                "      const rp = f.webkitRelativePath || f.name;" +
                "      renamed.push(new File([f], rp.replace(/\\\\/g, '/'), {type: f.type, lastModified: f.lastModified}));" +
                "    }" +
                "    orig(renamed);" +
                "  };" +
                "}"
        );

        // Track uploaded files with their relative paths
        Map<String, String> uploadedFiles = new LinkedHashMap<>(); // fileName -> mimeType
        Span fileCountLabel = new Span("No files selected");
        fileCountLabel.getStyle().set("color", "var(--bervan-text-secondary, gray)");

        folderUpload.addSucceededListener(event -> {
            uploadedFiles.put(event.getFileName(), event.getMIMEType());
            fileCountLabel.setText(uploadedFiles.size() + " file(s) ready to upload");
        });

        folderUpload.addFailedListener(event ->
                log.error("Failed to receive file: " + event.getFileName() + " - " + event.getReason().getMessage()));

        Button saveFolder = new Button("Upload folder");
        saveFolder.addClassName("option-button");

        saveFolder.addClickListener(buttonClickEvent -> {
            if (uploadedFiles.isEmpty()) {
                showWarningNotification("Please select a folder!");
                return;
            }

            dialog.close();

            SecurityContext context = SecurityContextHolder.getContext();
            UI ui = UI.getCurrent();
            AsyncTask newAsyncTask = asyncTaskService.createAndStoreAsyncTask();
            showPrimaryNotification("Folder upload in progress (" + uploadedFiles.size()
                    + " files)... You will be notified.");

            // Copy data before thread starts (UI-thread data)
            Map<String, String> filesToUpload = new LinkedHashMap<>(uploadedFiles);
            String descValue = description.getValue();

            new Thread(() -> {
                SecurityContextHolder.setContext(context);
                AsyncTask asyncTask = asyncTaskService.setInProgress(newAsyncTask,
                        "Uploading folder with " + filesToUpload.size() + " files");
                int successCount = 0;
                int failCount = 0;
                List<Metadata> addedInCurrentPath = new ArrayList<>();

                try {
                    // Collect all directory paths that need to be created
                    Set<String> createdDirs = new HashSet<>();

                    for (Map.Entry<String, String> entry : filesToUpload.entrySet()) {
                        String relativePath = entry.getKey(); // e.g. "folderName/sub/file.txt"
                        String mimeType = entry.getValue();

                        try {
                            // Parse relative path to extract subdirectories
                            String[] parts = relativePath.split("/");
                            String targetPath = path;

                            // Create subdirectories (all parts except the last one = filename)
                            for (int i = 0; i < parts.length - 1; i++) {
                                String dirName = parts[i];
                                String dirKey = targetPath + "/" + dirName;
                                if (!createdDirs.contains(dirKey)) {
                                    List<Metadata> existingDirs = fileServiceManager.getDirectoriesInPath(targetPath);
                                    if (existingDirs.stream().noneMatch(d -> d.getFilename().equals(dirName))) {
                                        Metadata newDir = fileServiceManager.createEmptyDirectory(targetPath, dirName);
                                        if (targetPath.equals(path)) {
                                            addedInCurrentPath.add(newDir);
                                        }
                                    }
                                    createdDirs.add(dirKey);
                                }
                                targetPath = targetPath.endsWith("/")
                                        ? targetPath + dirName
                                        : targetPath + "/" + dirName;
                            }

                            String fileName = parts[parts.length - 1];
                            InputStream is = multiBuffer.getInputStream(relativePath);
                            BervanMockMultiPartFile multipartFile = new BervanMockMultiPartFile(
                                    fileName, fileName, mimeType, is);
                            UploadResponse saved = fileServiceManager.save(multipartFile, descValue, targetPath);

                            if (targetPath.equals(path)) {
                                addedInCurrentPath.addAll(saved.getMetadata());
                            }
                            successCount++;
                        } catch (Exception e) {
                            log.error("Failed to upload file: " + relativePath, e);
                            failCount++;
                        }
                    }

                    String message = "Folder upload completed: " + successCount + " file(s) uploaded";
                    if (failCount > 0) {
                        message += ", " + failCount + " failed";
                    }

                    asyncTaskService.setFinished(asyncTask, message);

                    List<Metadata> finalAdded = addedInCurrentPath;
                    ui.access(() -> {
                        if (!finalAdded.isEmpty()) {
                            postSavedFolderActions(finalAdded);
                        }
                        postSaveActions();
                    });
                } catch (Exception e) {
                    log.error("Folder upload failed", e);
                    asyncTaskService.setFailed(asyncTask, e.getMessage());
                    ui.access(() -> showErrorNotification("Folder upload failed: " + e.getMessage()));
                }
            }).start();
        });

        layout.add(folderUpload, fileCountLabel, saveFolder);
        return layout;
    }

    protected void postSavedZipActions(List<Metadata> addedInCurrentPath) {
    }

    protected void postSavedFolderActions(List<Metadata> addedInCurrentPath) {
    }

    protected void postSaveActions(UploadResponse saved) {
    }

    protected void postSaveActions() {
        showSuccessNotification("File uploaded successfully!");
    }

    public void setSupportedFiles(String... supportedFiles) {
        this.setSupportedFiles = supportedFiles;
    }
}
