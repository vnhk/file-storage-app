package com.bervan.filestorage.view;

import com.bervan.common.AbstractTableView;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.StreamResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

public abstract class AbstractFileStorageView extends AbstractTableView<Metadata> {
    public static final String ROUTE_NAME = "file-storage-app/files";
    private final FileServiceManager fileServiceManager;
    private final String maxFileSize;
    private final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    private String path = "";
    private H4 pathInfoComponent = new H4();

    public AbstractFileStorageView(FileServiceManager service, String maxFileSize, LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB) {
        super(new FileStorageAppPageLayout(ROUTE_NAME), service, "Storage");
        this.fileServiceManager = service;
        this.maxFileSize = maxFileSize;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        render();
    }

    private void render() {
        renderCommonComponents();
        contentLayout.remove(header);
        contentLayout.remove(addButton);

        Button synchronizeDBWithStorageFilesButton = new Button("Synchronize");
        synchronizeDBWithStorageFilesButton.addClickListener(buttonClickEvent -> {
            try {
                loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
                data.addAll(loadData());
                grid.getDataProvider().refreshAll();
            } catch (FileNotFoundException e) {
                Notification.show(e.getMessage());
            }
        });
        addButton.setText("Upload file");

        HorizontalLayout buttons = new HorizontalLayout(addButton, synchronizeDBWithStorageFilesButton);
        contentLayout.addComponentAtIndex(0, pathInfoComponent);
        contentLayout.addComponentAtIndex(0, buttons);
        contentLayout.addComponentAtIndex(0, new Text("Max File Size: " + maxFileSize));
    }

    @Override
    protected Set<Metadata> loadData() {
        getUI().ifPresent(ui -> {
            QueryParameters queryParameters = ui.getInternals().getActiveViewLocation().getQueryParameters();
            Map<String, String> parameters = queryParameters.getParameters()
                    .entrySet()
                    .stream()
                    .collect(
                            java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> String.join("", e.getValue())
                            )
                    );
            path = parameters.getOrDefault("path", "");
        });


        pathInfoComponent.setText("Path: /" + path);
        Set<Metadata> metadata = fileServiceManager.loadByPath(path);

        if (!path.isBlank()) {
            String previousFolderPath = "";

            String[] subFolders = path.split("/");
            if (subFolders.length != 0) {
                for (int i = 0; i < subFolders.length - 2; i++) {
                    previousFolderPath += subFolders[i] + "/";
                }
            }

            if (previousFolderPath.endsWith("/")) {
                previousFolderPath = previousFolderPath.substring(0, previousFolderPath.length() - 2);
            }
            Metadata previousFolderMetadata = new Metadata(previousFolderPath, "../", null, null, true);
            metadata.add(previousFolderMetadata);
        }

        return metadata;
    }

    @Override
    protected Grid<Metadata> getGrid() {
        Grid<Metadata> grid = new Grid<>(Metadata.class, false);

        grid.addColumn(new ComponentRenderer<>(metadata -> {
                    HorizontalLayout layout = new HorizontalLayout();

                    if (metadata.isDirectory()) {
                        Icon folderIcon = VaadinIcon.FOLDER.create();
                        layout.add(folderIcon);
                    } else {
                        Icon folderIcon = VaadinIcon.FILE.create();
                        layout.add(folderIcon);
                    }

                    layout.add(new Text(metadata.getFilename()));
                    return layout;
                }))
                .setHeader("Filename")
                .setKey("filename")
                .setResizable(true)
                .setSortable(true)
                .setComparator(createFilenameComparator());

        grid.addColumn(new ComponentRenderer<>(metadata -> {
                    if (metadata.isDirectory()) {
                        return new Span();
                    }

                    return formatTextComponent(String.valueOf(metadata.getDescription()));
                }))
                .setHeader("Description").setKey("description").setResizable(true);
        grid.addColumn(new ComponentRenderer<>(metadata -> {
                    if (metadata.isDirectory()) {
                        return new Span();
                    }
                    return formatTextComponent(String.valueOf(metadata.getCreateDate()));
                }))
                .setHeader("Created").setKey("created").setResizable(true);

        grid.getElement().getStyle().set("--lumo-size-m", 100 + "px");

        grid.sort(GridSortOrder.asc(grid.getColumnByKey("filename")).build());

        removeUnSortedState(grid, 0);

        return grid;
    }

    private Comparator<Metadata> createFilenameComparator() {
        return (metadata1, metadata2) -> {
            String filename1 = metadata1.getFilename();
            String filename2 = metadata2.getFilename();

            int orderVal = 0;

            if ("../".equals(filename1)) {
                orderVal = -1;
            } else if ("../".equals(filename2)) {
                orderVal = 1;
            }

            if (orderVal != 0) {
                return grid.getSortOrder().get(0).getDirection() == SortDirection.ASCENDING ? orderVal
                        : -orderVal;
            }

            return filename1.compareToIgnoreCase(filename2);
        };
    }

    @Override
    protected void doOnColumnClick(ItemClickEvent<Metadata> event) {
        Metadata item = event.getItem();
        if (item.isDirectory()) {
            String newPath = "";
            if (item.getFilename().equals("../")) {
                newPath = item.getPath();
            } else if (item.getPath().isBlank()) {
                newPath = item.getFilename();
            } else {
                newPath = item.getPath() + File.separator + item.getFilename();
            }

            UI.getCurrent().navigate(ROUTE_NAME, QueryParameters.of("path", newPath));
            path = newPath;
            data.removeAll(data);
            data.addAll(loadData());
            grid.getDataProvider().refreshAll();
        } else {
            openDetailsDialog(event);
        }
    }

    private void openDetailsDialog(ItemClickEvent<Metadata> event) {
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
                UploadResponse saved = fileServiceManager.save(holder.get(0), description.getValue(), "");
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
