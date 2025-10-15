package com.bervan.filestorage.view;

import com.bervan.common.view.AbstractPageView;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.FileBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public abstract class UploadComponent extends AbstractPageView {
    private final FileServiceManager fileServiceManager;
    private final String path;
    private String[] setSupportedFiles = null;

    public UploadComponent(FileServiceManager fileServiceManager, String path) {
        this.fileServiceManager = fileServiceManager;
        this.path = path;
    }

    public void open() {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");

        VerticalLayout dialogLayout = new VerticalLayout();

        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        TextArea description = new TextArea("Description");
        description.setWidth("100%");

        FileBuffer buffer = new FileBuffer();
        Upload upload = new Upload(buffer);
        upload.setAcceptedFileTypes(setSupportedFiles);
        List<MultipartFile> holder = new ArrayList<>();
        upload.addSucceededListener(event -> {
            if (holder.size() > 0) {
                holder.remove(0);
            }

            try {
                InputStream inputStream = buffer.getInputStream();
                holder.add(0, new MultipartFile() {
                    @Override
                    public String getName() {
                        return event.getFileName();
                    }

                    @Override
                    public String getOriginalFilename() {
                        return event.getFileName();
                    }

                    @Override
                    public String getContentType() {
                        return event.getMIMEType();
                    }

                    @Override
                    public boolean isEmpty() {
                        throw new RuntimeException("Not supported");
                    }

                    @Override
                    public long getSize() {
                        throw new RuntimeException("Not supported");

                    }

                    @Override
                    public byte[] getBytes() throws IOException {
                        throw new RuntimeException("Not supported");
                    }

                    @Override
                    public InputStream getInputStream() throws IOException {
                        return inputStream;
                    }

                    @Override
                    public void transferTo(File dest) throws IOException, IllegalStateException {
                        transferTo(dest.toPath());
                    }
                });
            } catch (Exception e) {
                log.error("Error uploading file: " + e.getMessage(), e);
                showErrorNotification("Error uploading file: " + e.getMessage());
            }
        });

        Button save = new Button("Save and upload");
        save.addClassName("option-button");

        Checkbox extractCheckbox = new Checkbox("Extract file");
        extractCheckbox.setVisible(false);

        upload.addSucceededListener(event -> {
            if (holder.size() > 0) {
                holder.remove(0);
            }

            try {
                InputStream inputStream = buffer.getInputStream();
                holder.add(0, new MultipartFile() {
                    @Override
                    public String getName() {
                        return event.getFileName();
                    }

                    @Override
                    public String getOriginalFilename() {
                        return event.getFileName();
                    }

                    @Override
                    public String getContentType() {
                        return event.getMIMEType();
                    }

                    @Override
                    public boolean isEmpty() {
                        throw new RuntimeException("Not supported");
                    }

                    @Override
                    public long getSize() {
                        throw new RuntimeException("Not supported");
                    }

                    @Override
                    public byte[] getBytes() throws IOException {
                        throw new RuntimeException("Not supported");
                    }

                    @Override
                    public InputStream getInputStream() throws IOException {
                        return inputStream;
                    }

                    @Override
                    public void transferTo(File dest) throws IOException, IllegalStateException {
                        transferTo(dest.toPath());
                    }
                });

                if (event.getFileName().endsWith(".zip")) {
                    extractCheckbox.setVisible(true);
                } else {
                    extractCheckbox.setVisible(false);
                }
            } catch (Exception e) {
                log.error("Error uploading file: " + e.getMessage(), e);
                showErrorNotification("Error uploading file: " + e.getMessage());
            }
        });

        save.addClickListener(buttonClickEvent -> {
            if (holder.size() > 0) {
                try {
                    MultipartFile uploadedFile = holder.get(0);

                    if (extractCheckbox.isVisible() && extractCheckbox.getValue() && uploadedFile.getOriginalFilename().endsWith(".zip")) {
                        UploadResponse savedZip = fileServiceManager.saveAndExtractZip(uploadedFile, description.getValue(), path);
                        List<Metadata> addedInCurrentPath = savedZip.getMetadata().stream().filter(e -> e.getPath().equals(path))
                                .toList();
                        postSavedZipActions(addedInCurrentPath);
                    } else {
                        UploadResponse saved = fileServiceManager.save(uploadedFile, description.getValue(), path);
                        postSaveActions(saved);
                    }

                    postSaveActions();
                    dialog.close();
                } catch (Exception e) {
                    log.error("Failed to save a file: ", e);
                    showErrorNotification("Failed to save a file: " + e.getMessage());
                }
            } else {
                showWarningNotification("Please attach a file!");
            }
        });

        dialogLayout.add(headerLayout, description, upload, extractCheckbox, save);
        dialog.add(dialogLayout);
        dialog.open();
    }

    protected void postSavedZipActions(List<Metadata> addedInCurrentPath) {
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
