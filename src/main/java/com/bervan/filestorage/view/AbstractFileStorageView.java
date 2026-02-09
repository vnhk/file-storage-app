package com.bervan.filestorage.view;

import com.bervan.asynctask.AsyncTask;
import com.bervan.asynctask.AsyncTaskService;
import com.bervan.common.component.BervanButton;
import com.bervan.common.config.BervanViewConfig;
import com.bervan.common.view.AbstractBervanTableView;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB;
import com.bervan.filestorage.view.fileviever.FileViewerView;
import com.bervan.logging.JsonLogger;
import com.vaadin.flow.component.AbstractField;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridSortOrder;
import com.vaadin.flow.component.grid.ItemClickEvent;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.server.StreamResource;
import io.micrometer.common.util.StringUtils;
import org.apache.commons.io.FilenameUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public abstract class AbstractFileStorageView extends AbstractBervanTableView<UUID, Metadata> {
    public static final String ROUTE_NAME = "file-storage-app/files";
    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    private final FileServiceManager fileServiceManager;
    private final String maxFileSize;
    private final AsyncTaskService asyncTaskService;
    private final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    private final H4 pathInfoComponent = new H4();
    private String path = "/";

    public AbstractFileStorageView(FileServiceManager service, String maxFileSize, LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB,
                                   AsyncTaskService asyncTaskService, BervanViewConfig bervanViewConfig) {
        super(new FileStorageAppPageLayout(ROUTE_NAME), service, bervanViewConfig, Metadata.class);
        this.asyncTaskService = asyncTaskService;
        this.fileServiceManager = service;
        this.maxFileSize = maxFileSize;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        render();
    }

    private void render() {
        searchBarVisible = false;
        renderCommonComponents();
        newItemButton.setVisible(false);
        TextField searchField = getTextField();
        int gridIndex = contentLayout.indexOf(grid);

        if (gridIndex >= 0) {
            contentLayout.addComponentAtIndex(gridIndex, searchField);
        } else {
            contentLayout.add(searchField);
        }

        // Add path info and max file size at the top
        contentLayout.addComponentAtIndex(0, pathInfoComponent);
        contentLayout.addComponentAtIndex(0, new Hr());
        contentLayout.addComponentAtIndex(0, new H5("Max File Size: " + maxFileSize));

        paginationBar.setVisible(false);
    }

    private TextField getTextField() {
        TextField searchField = new TextField();
        searchField.setPlaceholder("Search visible items...");
        searchField.setPrefixComponent(new Icon(VaadinIcon.SEARCH));
        searchField.addClassName("grid-search-field");
        searchField.setClearButtonVisible(true);
        searchField.setValueChangeMode(ValueChangeMode.LAZY);
        searchField.setWidthFull();
        searchField.setValueChangeTimeout(300);
        searchField.addValueChangeListener(e -> {
            String filterText = e.getValue();

            @SuppressWarnings("unchecked")
            ListDataProvider<Metadata> dataProvider = (ListDataProvider<Metadata>) grid.getDataProvider();

            dataProvider.setFilter(item -> {
                if (filterText == null || filterText.isEmpty()) {
                    return true;
                }

                return matchesAnyColumn(item, filterText.toLowerCase(), List.of("filename"));
            });
        });
        return searchField;
    }

    @Override
    protected void customizeTopTableActions(HorizontalLayout topTableActions) {
        // Synchronize All button with text
        Button synchronizeButton = new BervanButton("Synchronize");
        synchronizeButton.addClickListener(buttonClickEvent -> {
            try {
                SecurityContext context = SecurityContextHolder.getContext();

                AsyncTask newAsyncTask = asyncTaskService.createAndStoreAsyncTask();
                showPrimaryNotification("Synchronization in progress. Please wait... You will be notified.");
                new Thread(() -> {
                    SecurityContextHolder.setContext(context);
                    AsyncTask asyncTask = asyncTaskService.setInProgress(newAsyncTask, "Synchronizing DB with storage files for all files");
                    try {
                        loadStorageAndIntegrateWithDB.synchronizeStorageWithDB();
                        asyncTaskService.setFinished(asyncTask, "Synchronization for all files finished successfully.");
                    } catch (Exception e) {
                        log.error("Synchronization for all files failed.", e);
                        asyncTaskService.setFailed(asyncTask, e.getMessage());
                    }
                }).start();
            } catch (Exception e) {
                log.error("Synchronization for all files failed.", e);
                showErrorNotification(e.getMessage());
            }
        });

        // New Folder button with icon
        Button newFolderButton = new BervanButton(new Icon(VaadinIcon.FOLDER_ADD));
        newFolderButton.addClassName("bervan-icon-btn");
        newFolderButton.getElement().setAttribute("title", "New Folder");
        newFolderButton.addClickListener(buttonClickEvent -> openNewFolderDialog());

        // Upload file button with icon
        Button uploadButton = new BervanButton(new Icon(VaadinIcon.UPLOAD));
        uploadButton.addClassName("bervan-icon-btn");
        uploadButton.addClassName("primary");
        uploadButton.getElement().setAttribute("title", "Upload File");
        uploadButton.addClickListener(e -> newItemButtonClick());

        topTableActions.add(synchronizeButton, newFolderButton, uploadButton);
    }

    private void openNewFolderDialog() {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");

        VerticalLayout dialogLayout = new VerticalLayout();

        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        TextField field = new TextField("Directory name:");
        field.setWidth("50%");

        Button createButton = new BervanButton("Create");

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
    }

    @Override
    protected List<Metadata> loadData() {
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
            path = parameters.getOrDefault("path", "/");
        });


        pathInfoComponent.setText("Path: " + path);
        Set<Metadata> metadata = fileServiceManager.loadByPath(path);

        // Populate fileSize for entries that don't have it yet
        for (Metadata m : metadata) {
            if (!m.isDirectory() && m.getFileSize() == null) {
                try {
                    Path filePath = fileServiceManager.getFile(m);
                    if (Files.exists(filePath)) {
                        m.setFileSize(Files.size(filePath));
                        fileServiceManager.updateMetadata(m);
                    }
                } catch (Exception e) {
                    // ignore - size will remain null
                }
            }
        }

        if (!path.isBlank() && !"/".equals(path)) {
            String previousFolderPath = "";

            String[] subFolders = path.split("/");
            if (subFolders.length != 0) {
                for (int i = 0; i < subFolders.length - 2; i++) {
                    previousFolderPath += subFolders[i] + "/";
                }
            }

            Metadata previousFolderMetadata = new Metadata(previousFolderPath, "../", null, null, true);
            metadata.add(previousFolderMetadata);
        }

        return metadata.stream().toList();
    }

    @Override
    protected Grid<Metadata> getGrid() {
        Grid<Metadata> grid = new Grid<>(Metadata.class, false);

        // Checkbox column
        buildSelectAllCheckboxesComponent();
        grid.addColumn(createCheckboxComponent())
                .setHeader(selectAllCheckbox)
                .setKey(CHECKBOX_COLUMN_KEY)
                .setWidth("10px")
                .setTextAlign(ColumnTextAlign.CENTER)
                .setResizable(false)
                .setSortable(false);

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

        // Size column
        grid.addColumn(new ComponentRenderer<>(metadata -> {
                    if (metadata.isDirectory() || metadata.getFileSize() == null) {
                        return new Span();
                    }
                    return new Span(formatFileSize(metadata.getFileSize()));
                }))
                .setHeader("Size").setKey("size")
                .setAutoWidth(true).setResizable(true);

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

    @Override
    protected void addFloatingToolbar() {
        super.addFloatingToolbar();
        floatingToolbar.setEditEnabled(false);
        floatingToolbar.setExportEnabled(false);

        floatingToolbar.addCustomAction("download-zip", "vaadin:download", "Download as ZIP", "success",
                e -> handleDownloadZip());
        floatingToolbar.addCustomAction("copy", "vaadin:copy", "Copy selected", "primary",
                e -> handleCopy());
        floatingToolbar.addCustomAction("move", "vaadin:arrow-right", "Move selected", "info",
                e -> handleMove());
    }

    @Override
    protected void updateSelectedItemsLabel() {
        long selectedCount = checkboxes.stream().filter(AbstractField::getValue).count();
        if (selectedCount == 0) {
            selectedItemsCountLabel.setText("Selected 0 item(s)");
            return;
        }

        Set<String> selectedIds = getSelectedItemsByCheckbox();
        long totalSize = data.stream()
                .filter(e -> e.getId() != null && selectedIds.contains(e.getId().toString()))
                .filter(e -> !e.isDirectory() && e.getFileSize() != null)
                .mapToLong(Metadata::getFileSize)
                .sum();

        selectedItemsCountLabel.setText("Selected " + selectedCount + " item(s) (" + formatFileSize(totalSize) + ")");
    }

    @Override
    protected void handleFloatingToolbarDelete() {
        Set<String> selected = getSelectedItemsByCheckbox();
        if (selected.isEmpty()) return;

        List<Metadata> toDelete = data.stream()
                .filter(e -> e.getId() != null && selected.contains(e.getId().toString()))
                .collect(Collectors.toList());

        ConfirmDialog confirmDialog = new ConfirmDialog();
        confirmDialog.setHeader("Confirm Deletion");
        confirmDialog.setText("Are you sure you want to delete " + toDelete.size() + " item(s)?");
        confirmDialog.setConfirmText("Delete");
        confirmDialog.setConfirmButtonTheme("error primary");
        confirmDialog.setCancelable(true);

        confirmDialog.addConfirmListener(e -> {
            SecurityContext context = SecurityContextHolder.getContext();
            UI ui = UI.getCurrent();
            AsyncTask newAsyncTask = asyncTaskService.createAndStoreAsyncTask();
            showPrimaryNotification("Deleting " + toDelete.size() + " item(s)... You will be notified.");
            clearSelection();

            new Thread(() -> {
                SecurityContextHolder.setContext(context);
                AsyncTask asyncTask = asyncTaskService.setInProgress(newAsyncTask,
                        "Deleting " + toDelete.size() + " items");
                try {
                    for (Metadata item : toDelete) {
                        fileServiceManager.delete(item);
                    }
                    asyncTaskService.setFinished(asyncTask,
                            "Deleted " + toDelete.size() + " item(s) successfully.");
                    ui.access(() -> {
                        data.removeAll(toDelete);
                        grid.getDataProvider().refreshAll();
                    });
                } catch (Exception ex) {
                    log.error("Bulk delete failed.", ex);
                    asyncTaskService.setFailed(asyncTask, ex.getMessage());
                }
            }).start();
        });

        confirmDialog.open();
    }

    private void handleDownloadZip() {
        Set<String> selected = getSelectedItemsByCheckbox();
        if (selected.isEmpty()) return;

        List<Metadata> toDownload = data.stream()
                .filter(e -> e.getId() != null && selected.contains(e.getId().toString()))
                .filter(e -> !e.isDirectory())
                .collect(Collectors.toList());

        if (toDownload.isEmpty()) {
            showWarningNotification("No files selected (directories are skipped)");
            return;
        }

        StreamResource resource = new StreamResource("files.zip", () -> {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                    for (Metadata item : toDownload) {
                        try {
                            Path filePath = fileServiceManager.getFile(item);
                            if (Files.exists(filePath)) {
                                zos.putNextEntry(new ZipEntry(item.getFilename()));
                                Files.copy(filePath, zos);
                                zos.closeEntry();
                            }
                        } catch (Exception e) {
                            log.error("Failed to add file to ZIP: " + item.getFilename(), e);
                        }
                    }
                }
                return new ByteArrayInputStream(baos.toByteArray());
            } catch (IOException e) {
                log.error("Failed to create ZIP", e);
                return new ByteArrayInputStream(new byte[0]);
            }
        });

        Anchor downloadAnchor = new Anchor(resource, "");
        downloadAnchor.getElement().setAttribute("download", true);
        downloadAnchor.setId("zip-download-anchor");
        add(downloadAnchor);

        downloadAnchor.getElement().executeJs(
                "const a = this; setTimeout(() => { a.click(); setTimeout(() => a.remove(), 1000); }, 100);"
        );

        clearSelection();
    }

    private void handleCopy() {
        Set<String> selected = getSelectedItemsByCheckbox();
        if (selected.isEmpty()) return;

        List<Metadata> toCopy = data.stream()
                .filter(e -> e.getId() != null && selected.contains(e.getId().toString()))
                .collect(Collectors.toList());

        FolderPickerDialog dialog = new FolderPickerDialog(fileServiceManager,
                "Copy " + toCopy.size() + " item(s) to...", destPath -> {
            SecurityContext context = SecurityContextHolder.getContext();
            AsyncTask newAsyncTask = asyncTaskService.createAndStoreAsyncTask();
            showPrimaryNotification("Copying " + toCopy.size() + " item(s)... You will be notified.");
            clearSelection();

            new Thread(() -> {
                SecurityContextHolder.setContext(context);
                AsyncTask asyncTask = asyncTaskService.setInProgress(newAsyncTask,
                        "Copying " + toCopy.size() + " items to " + destPath);
                try {
                    for (Metadata item : toCopy) {
                        fileServiceManager.copyFileToPath(item, destPath);
                    }
                    asyncTaskService.setFinished(asyncTask,
                            "Copied " + toCopy.size() + " item(s) successfully.");
                } catch (Exception ex) {
                    log.error("Bulk copy failed.", ex);
                    asyncTaskService.setFailed(asyncTask, ex.getMessage());
                }
            }).start();
        });
        dialog.open();
    }

    private void handleMove() {
        Set<String> selected = getSelectedItemsByCheckbox();
        if (selected.isEmpty()) return;

        List<Metadata> toMove = data.stream()
                .filter(e -> e.getId() != null && selected.contains(e.getId().toString()))
                .collect(Collectors.toList());

        FolderPickerDialog dialog = new FolderPickerDialog(fileServiceManager,
                "Move " + toMove.size() + " item(s) to...", destPath -> {
            SecurityContext context = SecurityContextHolder.getContext();
            UI ui = UI.getCurrent();
            AsyncTask newAsyncTask = asyncTaskService.createAndStoreAsyncTask();
            showPrimaryNotification("Moving " + toMove.size() + " item(s)... You will be notified.");
            clearSelection();

            new Thread(() -> {
                SecurityContextHolder.setContext(context);
                AsyncTask asyncTask = asyncTaskService.setInProgress(newAsyncTask,
                        "Moving " + toMove.size() + " items to " + destPath);
                try {
                    for (Metadata item : toMove) {
                        fileServiceManager.moveFileToPath(item, destPath);
                    }
                    asyncTaskService.setFinished(asyncTask,
                            "Moved " + toMove.size() + " item(s) successfully.");
                    ui.access(() -> {
                        data.removeAll(toMove);
                        grid.getDataProvider().refreshAll();
                    });
                } catch (Exception ex) {
                    log.error("Bulk move failed.", ex);
                    asyncTaskService.setFailed(asyncTask, ex.getMessage());
                }
            }).start();
        });
        dialog.open();
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

    static String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    @Override
    protected void buildOnColumnClickDialogContent(Dialog dialog, VerticalLayout dialogLayout, HorizontalLayout headerLayout, String clickedField, Metadata item) {
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
                    if (!item.getPath().endsWith("/")) {
                        newPath = item.getPath() + File.separator + item.getFilename();
                    } else {
                        newPath = item.getPath() + item.getFilename();
                    }
                }

                if (!newPath.endsWith("/")) {
                    newPath += "/";
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

        Path file = fileServiceManager.getFile(event.getItem());

        FileViewerView fileViewerView = new FileViewerView(file, fileServiceManager, event.getItem());

        VerticalLayout dialogLayout = new VerticalLayout();
        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

        Metadata item = event.getItem();

        H4 filenameLabel = new H4(item.isDirectory() ? "Directory:" : "File:");
        H5 filename = new H5(item.getFilename());

        // Rename button
        Button renameButton = new Button("Rename");
        renameButton.addClassName("option-button");
        renameButton.addClickListener(click -> {
            Dialog renameDialog = new Dialog();
            renameDialog.setHeaderTitle("Rename");
            renameDialog.setWidth("400px");

            TextField renameField = new TextField("New name");
            renameField.setValue(item.getFilename());
            renameField.setWidthFull();
            renameDialog.add(renameField);

            BervanButton confirmRename = new BervanButton("Rename", e -> {
                String newName = renameField.getValue().trim();
                if (newName.isEmpty() || newName.equals(item.getFilename())) {
                    renameDialog.close();
                    return;
                }
                try {
                    fileServiceManager.renameFile(item, newName);
                    filename.setText(newName);
                    grid.getDataProvider().refreshAll();
                    showSuccessNotification("Renamed successfully");
                    renameDialog.close();
                } catch (Exception ex) {
                    showErrorNotification("Rename failed: " + ex.getMessage());
                }
            });
            BervanButton cancelRename = new BervanButton("Cancel", e -> renameDialog.close());
            renameDialog.getFooter().add(cancelRename, confirmRename);
            renameDialog.open();
            renameField.focus();
        });

        H4 descriptionLabel = new H4("Description");
        TextArea editableDescription = new TextArea();
        if (item.getDescription() != null) {
            editableDescription.setValue(item.getDescription());
        }
        editableDescription.setWidth("100%");
        editableDescription.setVisible(false);

        H5 description = new H5(item.getDescription());
        description.setVisible(true);

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

        Button editButton = new Button("Edit description");
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
            dialogLayout.add(headerLayout, filenameLabel, filename, renameButton, deleteButton);
        } else {
            // File size info
            Span sizeInfo = new Span();
            if (item.getFileSize() != null) {
                sizeInfo.setText("Size: " + formatFileSize(item.getFileSize()));
                sizeInfo.getStyle().set("color", "var(--bervan-text-secondary, gray)");
            }

            if (fileViewerView.isFileSupportView) {
                dialogLayout.add(headerLayout, fileViewerView, filenameLabel, filename, sizeInfo, renameButton,
                        descriptionLabel, description, editableDescription, new Hr(),
                        editButton, saveButton, downloadLink, new Hr(), deleteButton);
            } else {
                dialogLayout.add(headerLayout, filenameLabel, filename, sizeInfo, renameButton,
                        descriptionLabel, description, editableDescription, new Hr(),
                        editButton, saveButton, downloadLink, new Hr(), deleteButton);
            }
        }

        dialog.add(dialogLayout);
        dialog.open();
    }

    @Override
    protected void newItemButtonClick() {
        UploadComponent uploadComponent = new FileTableUploadComponent<>(fileServiceManager, path, data, grid);
        uploadComponent.open();
    }

    static class FileTableUploadComponent<T> extends UploadComponent {

        protected Grid<Metadata> grid;
        protected List<Metadata> data;

        public FileTableUploadComponent(FileServiceManager fileServiceManager, String path, List<Metadata> data, Grid<Metadata> grid) {
            super(fileServiceManager, path);
            this.data = data;
            this.grid = grid;
        }

        @Override
        protected void postSaveActions() {
            grid.getDataProvider().refreshAll();
            super.postSaveActions();
        }

        @Override
        protected void postSavedZipActions(List<Metadata> addedInCurrentPath) {
            data.addAll(addedInCurrentPath);
        }

        @Override
        protected void postSaveActions(UploadResponse saved) {
            data.add(saved.getMetadata().get(0));
        }
    }
}
