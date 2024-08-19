package com.bervan.filestorage.view;

import com.bervan.common.AbstractTableView;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.server.StreamResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public abstract class AbstractFileStorageView extends AbstractTableView<Metadata> {
    public static final String ROUTE_NAME = "file-storage-app/files";
    private final FileServiceManager fileServiceManager;


    public AbstractFileStorageView(FileServiceManager service, String maxFileSize) {
        super(new FileStorageAppPageLayout(ROUTE_NAME), service, "Storage");
        this.fileServiceManager = service;

        add(new Text("Max File Size: " + maxFileSize));
        addButton.setText("Upload file");
    }

    @Override
    protected Grid<Metadata> getGrid() {
        Grid<Metadata> grid = new Grid<>(Metadata.class, false);
        grid.addColumn(new ComponentRenderer<>(metadata -> formatTextComponent(metadata.getFilename())))
                .setHeader("Filename").setKey("filename").setResizable(true).setSortable(true);
        grid.addColumn(new ComponentRenderer<>(metadata -> formatTextComponent(String.valueOf(metadata.getDescription()))))
                .setHeader("Description").setKey("description").setResizable(true);
        grid.addColumn(new ComponentRenderer<>(metadata -> formatTextComponent(String.valueOf(metadata.getCreateDate()))))
                .setHeader("Created").setKey("created").setResizable(true);

        grid.getElement().getStyle().set("--lumo-size-m", 100 + "px");

        return grid;
    }

    @Override
    protected void openClickOnColumnDialog(ItemClickEvent<Metadata> event) {
        Dialog dialog = new Dialog();
        dialog.setWidth("80vw");

        VerticalLayout dialogLayout = new VerticalLayout();

        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        String clickedColumn = event.getColumn().getKey();
        TextArea field = new TextArea(clickedColumn);
        field.setWidth("100%");

        Metadata item = event.getItem();

        H4 descriptionLabel = new H4("Description");
        Text description = new Text(item.getDescription());
        H4 filenameLabel = new H4("Filename");
        Text filename = new Text(item.getFilename());

        Button prepareExportButton = new Button("Prepare file to download");
        prepareExportButton.addClickListener(buttonClickEvent -> {
            StreamResource resource = prepareDownloadResource(item.getFilename());
            Anchor downloadLink = new Anchor(resource, "");
            downloadLink.getElement().setAttribute("download", true);
            Button downloadButton = new Button("Download");
            downloadLink.add(downloadButton);
            dialogLayout.add(downloadLink);
            dialogLayout.remove(prepareExportButton);
        });

        Button deleteButton = new Button("Delete (Not Working Yet)");

        dialogLayout.add(headerLayout, filenameLabel, filename, descriptionLabel, description, prepareExportButton, deleteButton);
        dialog.add(dialogLayout);

        dialog.open();
    }

    private StreamResource prepareDownloadResource(String fileName) {
        Path filePath = fileServiceManager.getFile(fileName);

        return new StreamResource(fileName, () -> {
            try {
                return new FileInputStream(filePath.toFile());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    protected void openAddDialog() {
        Dialog dialog = new Dialog();
        dialog.setWidth("80vw");

        VerticalLayout dialogLayout = new VerticalLayout();

        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        TextArea description = new TextArea("Description");
        description.setWidth("100%");

        MemoryBuffer buffer = new MemoryBuffer();
        Upload upload = new Upload(buffer);
//        upload.setAcceptedFileTypes("application/pdf", "image/jpeg", "image/png");
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
                e.printStackTrace();
                Notification.show("Error uploading file: " + e.getMessage());
            }
        });

        Button save = new Button("Save and upload");
        save.addClickListener(buttonClickEvent -> {
            if (holder.size() > 0) {
                UploadResponse saved = fileServiceManager.save(holder.get(0), description.getValue());
                data.add(saved.getMetadata());
                grid.getDataProvider().refreshAll();
                Notification.show("File uploaded successfully!");
                dialog.close();
            } else {
                Notification.show("Please attach a file!");
            }
        });

        dialogLayout.add(headerLayout, description, upload, save);
        dialog.add(dialogLayout);
        dialog.open();
    }
}
