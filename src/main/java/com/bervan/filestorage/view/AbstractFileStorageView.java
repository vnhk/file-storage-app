package com.bervan.filestorage.view;

import com.bervan.common.AbstractTableView;
import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB;
import com.bervan.filestorage.view.fileviever.FileViewerView;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.H5;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.FileBuffer;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.QueryParameters;
import io.micrometer.common.util.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public abstract class AbstractFileStorageView extends AbstractTableView<UUID, Metadata> {
    public static final String ROUTE_NAME = "file-storage-app/files";
    private final FileServiceManager fileServiceManager;
    private final String maxFileSize;
    private final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    private String path = "";
    private final H4 pathInfoComponent = new H4();
    @Value("${file.service.storage.folder}")
    private String FOLDER;

    public AbstractFileStorageView(FileServiceManager service, String maxFileSize, LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB, BervanLogger log) {
        super(new FileStorageAppPageLayout(ROUTE_NAME), service, log, Metadata.class);
        this.fileServiceManager = service;
        this.maxFileSize = maxFileSize;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        render();
    }

    private void render() {
        renderCommonComponents();
        contentLayout.remove(addButton);

        Button synchronizeDBWithStorageFilesButton = new Button("Synchronize");
        synchronizeDBWithStorageFilesButton.addClassName("option-button");

        Button createDirectory = new Button("New Folder");
        createDirectory.addClassName("option-button");

        createDirectory.addClickListener(buttonClickEvent -> {
            Dialog dialog = new Dialog();
            dialog.setWidth("95vw");

            VerticalLayout dialogLayout = new VerticalLayout();

            HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

            TextField field = new TextField("Directory name:");
            field.setWidth("50%");

            Button createButton = new Button("Create");
            createButton.addClassName("option-button");

            createButton.addClickListener(createEvent -> {
                String value = field.getValue();
                if (!StringUtils.isNotBlank(value)) {
                    showWarningNotification("Incorrect directory name");
                    return;
                }

                if (value.length() > 50) {
                    showWarningNotification("Incorrect directory name");
                    return;
                }

                List<Metadata> directoriesInPath = fileServiceManager.getDirectoriesInPath(path);
                if (directoriesInPath.stream().anyMatch(e -> e.getFilename().equals(value))) {
                    showWarningNotification("Directory exists!");
                    return;
                }

                Metadata newDirectory = fileServiceManager.createEmptyDirectory(path, value);

                data.add(newDirectory);
                grid.getDataProvider().refreshAll();

                dialog.close();
            });

            dialogLayout.add(headerLayout, field, createButton);
            dialog.add(dialogLayout);

            dialog.open();
        });

        synchronizeDBWithStorageFilesButton.addClickListener(buttonClickEvent -> {
            try {
                loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
                data.removeAll(data);
                data.addAll(loadData());
                grid.getDataProvider().refreshAll();
            } catch (FileNotFoundException e) {
                showErrorNotification(e.getMessage());
            }
        });
        addButton.setText("Upload file");
        addButton.addClassName("option-button");

        HorizontalLayout buttons = new HorizontalLayout(addButton, synchronizeDBWithStorageFilesButton, createDirectory);
        contentLayout.addComponentAtIndex(0, pathInfoComponent);
        contentLayout.addComponentAtIndex(0, buttons);
        contentLayout.addComponentAtIndex(0, new H4("Max File Size: " + maxFileSize));
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
                    if ("../".equals(metadata.getFilename())) {
                        return new Span();
                    }
                    return VaadinIcon.INFO_CIRCLE.create();
                }))
                .setHeader("").setKey("actions").setResizable(false).setAutoWidth(false).setWidth("1px");

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
                .setComparator(createFilenameComparator())
                .setAutoWidth(true);

        grid.addColumn(new ComponentRenderer<>(metadata -> {
                    if (metadata.isDirectory()) {
                        return new Span();
                    }
                    return formatTextComponent(String.valueOf(metadata.getCreateDate()).split("T")[0]);
                }))
                .setHeader("Created").setKey("created")
                .setAutoWidth(true).setResizable(true);

        grid.sort(GridSortOrder.asc(grid.getColumnByKey("filename")).build());

        removeUnSortedState(grid, 0);

        return grid;
    }

    private Comparator<Metadata> createFilenameComparator() {
        return (metadata1, metadata2) -> {
            if (metadata1 == null) {
                return -1;
            } else if (metadata2 == null) {
                return 1;
            }
            String filename1 = metadata1.getFilename();
            String filename2 = metadata2.getFilename();
            SortDirection direction = grid.getSortOrder().get(0).getDirection();

            boolean isParent1 = "../".equals(filename1);
            boolean isParent2 = "../".equals(filename2);

            int cmp = Integer.MAX_VALUE;
            if (isParent1 && !isParent2) {
                // "../" always on top
                cmp = -1;
            } else if (!isParent1 && isParent2) {
                cmp = 1;
            } else if (isParent1 && isParent2) {
                // both "../" should not happen!
                cmp = 0;
            }

            if (cmp != Integer.MAX_VALUE) {
                return direction == SortDirection.ASCENDING ? cmp : -cmp;
            }

            boolean dir1 = metadata1.isDirectory();
            boolean dir2 = metadata2.isDirectory();

            if (dir1 && !dir2) {
                cmp = -1; // directory before file
            } else if (!dir1 && dir2) {
                cmp = 1;  // file after directory
            }

            if (cmp != Integer.MAX_VALUE) {
                return direction == SortDirection.ASCENDING ? cmp : -cmp;
            }

            // If both are directories or both files, we sort based on name
            return filename1.compareToIgnoreCase(filename2);
        };
    }

    @Override
    protected void buildOnColumnClickDialogContent(Dialog dialog, VerticalLayout dialogLayout, HorizontalLayout headerLayout, String clickedColumn, Metadata item) {
        throw new RuntimeException("Not valid for this view");
    }

    @Override
    protected void buildNewItemDialogContent(Dialog dialog, VerticalLayout dialogLayout, HorizontalLayout headerLayout) {
        throw new RuntimeException("Not valid for this view");
    }

    @Override
    protected void doOnColumnClick(ItemClickEvent<Metadata> event) {
        if (event.getColumn().getKey().equals("actions") && !event.getItem().getFilename().equals("../")) {
            openDetailsDialog(event);
        } else {
            Metadata item = event.getItem();
            if (item.isDirectory()) {
                String newPath = "";
                if (item.getFilename().equals("../")) {
                    Path actual = Paths.get(path);
                    Path parentPath = actual.getParent();
                    newPath = parentPath == null ? "" : parentPath.toString();
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
            }
        }
    }

    private void openDetailsDialog(ItemClickEvent<Metadata> event) {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");

        FileViewerView fileViewerView = new FileViewerView(log, event.getItem(), FOLDER);

        VerticalLayout dialogLayout = new VerticalLayout();
        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        String clickedColumn = event.getColumn().getKey();
        TextArea field = new TextArea(clickedColumn);
        field.setWidth("100%");

        Metadata item = event.getItem();

        H4 descriptionLabel = new H4("Description");
        TextArea editableDescription = new TextArea();
        if (item.getDescription() != null) {
            editableDescription.setValue(item.getDescription());
        }
        editableDescription.setWidth("100%");
        editableDescription.setVisible(false);

        H5 description = new H5(item.getDescription());
        description.setVisible(true);

        H4 filenameLabel = new H4(item.isDirectory() ? "Directory:" : "File:");
        H5 filename = new H5(item.getFilename());

        Button downloadLink = new Button("Download");
        downloadLink.addClassName("option-button");
        downloadLink.addClickListener(buttonClickEvent -> {
            UI.getCurrent().getPage().executeJs("window.open($0, '_blank')", ROUTE_NAME + "/download?uuid=" + item.getId());
        });

        Button deleteButton = new Button(item.isDirectory() ? "Delete entire directory" : "Delete file");
        deleteButton.addClassName("option-button");
        deleteButton.addClassName("option-button-warning");

        deleteButton.addClickListener(click -> {
            fileServiceManager.delete(item);
            removeItemFromGrid(item);
            grid.getDataProvider().refreshAll();
            dialog.close();
        });

        Button editButton = new Button("Edit");
        editButton.addClassName("option-button");

        Button saveButton = new Button("Save");
        saveButton.addClassName("option-button");
        saveButton.setVisible(false);

        editButton.addClickListener(click -> {
            description.setVisible(false);
            editableDescription.setVisible(true);
            editButton.setVisible(false);
            saveButton.setVisible(true);
        });

        saveButton.addClickListener(click -> {
            try {
                String updatedDescription = editableDescription.getValue();
                item.setDescription(updatedDescription);
                fileServiceManager.updateMetadata(item);
                description.setText(updatedDescription);
                description.setVisible(true);
                editableDescription.setVisible(false);
                editButton.setVisible(true);
                saveButton.setVisible(false);
            } catch (Exception e) {
                showErrorNotification("Unable to update item!");
            }
        });

        if (item.isDirectory()) {
            dialogLayout.add(headerLayout, filenameLabel, filename, deleteButton);
        } else {
            if (fileViewerView.isFileSupportView) {
                dialogLayout.add(headerLayout, fileViewerView, filenameLabel, filename, descriptionLabel, description, editableDescription, new Hr(), editButton, saveButton, downloadLink, new Hr(), deleteButton);
            } else {
                dialogLayout.add(headerLayout, filenameLabel, filename, descriptionLabel, description, editableDescription, new Hr(), editButton, saveButton, downloadLink, new Hr(), deleteButton);
            }
        }

        dialog.add(dialogLayout);
        dialog.open();
    }

    @Override
    protected void newItemButtonClick() {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");

        VerticalLayout dialogLayout = new VerticalLayout();

        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        TextArea description = new TextArea("Description");
        description.setWidth("100%");

        FileBuffer buffer = new FileBuffer();
        Upload upload = new Upload(buffer);
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
                        data.addAll(addedInCurrentPath);
                    } else {
                        UploadResponse saved = fileServiceManager.save(uploadedFile, description.getValue(), path);
                        data.add(saved.getMetadata().get(0));
                    }

                    grid.getDataProvider().refreshAll();
                    showSuccessNotification("File uploaded successfully!");
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
}
