package com.bervan.filestorage.view;

import com.bervan.asynctask.AsyncTask;
import com.bervan.asynctask.AsyncTaskService;
import com.bervan.common.component.BervanButton;
import com.bervan.common.config.BervanViewConfig;
import com.bervan.common.view.AbstractBervanTableView;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileEncryptionService;
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
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
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
import java.util.concurrent.CompletableFuture;
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
    private final FileEncryptionService fileEncryptionService;
    private final H4 pathInfoComponent = new H4();
    private String path = "/";

    private static final String LS_TILE_VIEW = "file-storage-tile-view";
    private static final String LS_THUMBNAILS = "file-storage-show-thumbnails";

    private List<Metadata> allItemsInPath = new ArrayList<>();
    private boolean tileViewActive = false;
    private boolean showThumbnails = true;
    private Div tileContainer;
    private Button thumbnailToggleButton;
    private Button viewToggleButton;

    public AbstractFileStorageView(FileServiceManager service, String maxFileSize,
                                   LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB,
                                   AsyncTaskService asyncTaskService, BervanViewConfig bervanViewConfig,
                                   FileEncryptionService fileEncryptionService) {
        super(new FileStorageAppPageLayout(ROUTE_NAME), service, bervanViewConfig, Metadata.class);
        this.asyncTaskService = asyncTaskService;
        this.fileServiceManager = service;
        this.maxFileSize = maxFileSize;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        this.fileEncryptionService = fileEncryptionService;
        render();
    }

    private void render() {
        searchBarVisible = false;

        // Initialize tile container before renderCommonComponents so it's available in customizeTopTableActions
        tileContainer = new Div();
        tileContainer.addClassName("file-tiles-container");
        tileContainer.setVisible(false);

        renderCommonComponents();
        newItemButton.setVisible(false);
        TextField searchField = getTextField();
        int gridIndex = contentLayout.indexOf(grid);

        if (gridIndex >= 0) {
            contentLayout.addComponentAtIndex(gridIndex, searchField);
        } else {
            contentLayout.add(searchField);
        }

        // Add tile container after grid
        int currentGridIndex = contentLayout.indexOf(grid);
        if (currentGridIndex >= 0) {
            contentLayout.addComponentAtIndex(currentGridIndex + 1, tileContainer);
        } else {
            contentLayout.add(tileContainer);
        }

        // Add path info and max file size at the top
        contentLayout.addComponentAtIndex(0, pathInfoComponent);
        contentLayout.addComponentAtIndex(0, new Hr());
        contentLayout.addComponentAtIndex(0, new H5("Max File Size: " + maxFileSize));

        // Restore tile/thumbnail preferences from localStorage after component attaches
        addAttachListener(event -> restoreViewStateFromLocalStorage(event.getUI()));
    }

    private void restoreViewStateFromLocalStorage(UI ui) {
        // Read showThumbnails first, then tileView (so thumbnails setting is ready when tiles render)
        ui.getPage().executeJs("return localStorage.getItem($0)", LS_THUMBNAILS)
                .then(String.class, thumbValue -> {
                    if ("false".equals(thumbValue)) {
                        showThumbnails = false;
                        thumbnailToggleButton.setIcon(new Icon(VaadinIcon.EYE));
                        thumbnailToggleButton.getElement().setAttribute("title", "Show Thumbnails");
                    }
                    ui.getPage().executeJs("return localStorage.getItem($0)", LS_TILE_VIEW)
                            .then(String.class, tileValue -> {
                                if ("true".equals(tileValue)) {
                                    tileViewActive = true;
                                    grid.setVisible(false);
                                    tileContainer.setVisible(true);
                                    contentLayout.setFlexGrow(1, tileContainer);
                                    contentLayout.setFlexGrow(0, grid);
                                    viewToggleButton.setIcon(new Icon(VaadinIcon.LIST));
                                    viewToggleButton.getElement().setAttribute("title", "List View");
                                    thumbnailToggleButton.setVisible(true);
                                    if (!data.isEmpty()) {
                                        refreshTileView();
                                    }
                                    // If data is still loading, refreshData()'s completion handler
                                    // will call refreshTileView() because tileViewActive is now true
                                }
                            });
                });
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
        // Thumbnail toggle — only visible in tile view
        thumbnailToggleButton = new BervanButton(new Icon(VaadinIcon.EYE_SLASH));
        thumbnailToggleButton.addClassName("bervan-icon-btn");
        thumbnailToggleButton.getElement().setAttribute("title", "Hide Thumbnails");
        thumbnailToggleButton.setVisible(false);
        thumbnailToggleButton.addClickListener(e -> {
            showThumbnails = !showThumbnails;
            if (showThumbnails) {
                thumbnailToggleButton.setIcon(new Icon(VaadinIcon.EYE_SLASH));
                thumbnailToggleButton.getElement().setAttribute("title", "Hide Thumbnails");
            } else {
                thumbnailToggleButton.setIcon(new Icon(VaadinIcon.EYE));
                thumbnailToggleButton.getElement().setAttribute("title", "Show Thumbnails");
            }
            UI.getCurrent().getPage().executeJs("localStorage.setItem($0, $1)", LS_THUMBNAILS, String.valueOf(showThumbnails));
            refreshTileView();
        });

        // View toggle button (list/tile)
        viewToggleButton = new BervanButton(new Icon(VaadinIcon.GRID_SMALL));
        viewToggleButton.addClassName("bervan-icon-btn");
        viewToggleButton.getElement().setAttribute("title", "Tile View");
        viewToggleButton.addClickListener(e -> {
            tileViewActive = !tileViewActive;
            UI.getCurrent().getPage().executeJs("localStorage.setItem($0, $1)", LS_TILE_VIEW, String.valueOf(tileViewActive));
            if (tileViewActive) {
                grid.setVisible(false);
                tileContainer.setVisible(true);
                contentLayout.setFlexGrow(1, tileContainer);
                contentLayout.setFlexGrow(0, grid);
                viewToggleButton.setIcon(new Icon(VaadinIcon.LIST));
                viewToggleButton.getElement().setAttribute("title", "List View");
                thumbnailToggleButton.setVisible(true);
                refreshTileView();
            } else {
                grid.setVisible(true);
                tileContainer.setVisible(false);
                contentLayout.setFlexGrow(0, tileContainer);
                contentLayout.setFlexGrow(1, grid);
                viewToggleButton.setIcon(new Icon(VaadinIcon.GRID_SMALL));
                viewToggleButton.getElement().setAttribute("title", "Tile View");
                thumbnailToggleButton.setVisible(false);
            }
        });

        // Synchronize All button
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

        topTableActions.add(thumbnailToggleButton, viewToggleButton, synchronizeButton, newFolderButton, uploadButton);
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
            if (tileViewActive) {
                refreshTileView();
            }

            dialog.close();
        });

        dialogLayout.add(headerLayout, field, createButton);
        dialog.add(dialogLayout);

        dialog.open();
    }

    @Override
    protected List<Metadata> loadData() {
        checkboxes.removeAll(checkboxes);

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

        // Store all items for pagination
        allItemsInPath = new ArrayList<>(metadata);
        allFound = allItemsInPath.size();
        maxPages = Math.max(1, (int) Math.ceil((double) allFound / pageSize));

        // Clamp page number
        if (pageNumber >= maxPages) pageNumber = Math.max(0, maxPages - 1);
        if (pageNumber < 0) pageNumber = 0;

        // Get current page slice
        int start = pageNumber * pageSize;
        int end = Math.min(start + pageSize, (int) allFound);
        List<Metadata> pageItems = new ArrayList<>(allItemsInPath.subList(start, end));

        // Always prepend ../ if not at root
        if (!path.isBlank() && !"/".equals(path)) {
            String previousFolderPath = "";
            String[] subFolders = path.split("/");
            if (subFolders.length != 0) {
                for (int i = 0; i < subFolders.length - 2; i++) {
                    previousFolderPath += subFolders[i] + "/";
                }
            }
            Metadata previousFolderMetadata = new Metadata(previousFolderPath, "../", null, null, true);
            pageItems.add(0, previousFolderMetadata);
        }

        return pageItems;
    }

    @Override
    protected void refreshData() {
        showGridLoadingProgress(true);
        SecurityContext context = SecurityContextHolder.getContext();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        UI current = UI.getCurrent();

        CompletableFuture.runAsync(() -> {
            try {
                Thread.currentThread().setContextClassLoader(classLoader);
                SecurityContextHolder.setContext(context);
                data.removeAll(data);
                data.addAll(loadData());
                current.access(() -> {
                    reloadItemsCountInfo();
                    updateCurrentPageText();
                    showGridLoadingProgress(false);
                    grid.setItems(data);
                    grid.getDataProvider().refreshAll();
                    updateSelectedItemsLabel();
                    hideFloatingToolbar();
                    if (tileContainer != null && tileViewActive) {
                        refreshTileView();
                    }
                });
            } catch (Exception e) {
                log.error("Error while refreshing file storage data", e);
            } finally {
                showGridLoadingProgress(false);
            }
        });
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
                        if (tileViewActive) {
                            refreshTileView();
                        }
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
                        if (tileViewActive) {
                            refreshTileView();
                        }
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
            openDetailsDialog(event.getItem());
        } else {
            Metadata item = event.getItem();
            if (item.isDirectory()) {
                navigateToDirectory(item);
            }
        }
    }

    private void navigateToDirectory(Metadata item) {
        String newPath = computeNewPath(item);
        pageNumber = 0;
        UI.getCurrent().navigate(ROUTE_NAME, QueryParameters.of("path", newPath));
        path = newPath;
        data.removeAll(data);
        data.addAll(loadData());
        grid.getDataProvider().refreshAll();
        reloadItemsCountInfo();
        updateCurrentPageText();
        if (tileViewActive) {
            refreshTileView();
        }
    }

    private String computeNewPath(Metadata item) {
        String newPath;
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
        return newPath;
    }

    private void refreshTileView() {
        tileContainer.removeAll();
        for (Metadata item : data) {
            tileContainer.add(buildTile(item));
        }
    }

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp");

    private static boolean isImageFile(String filename) {
        if (filename == null || !filename.contains(".")) return false;
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private Div buildTile(Metadata item) {
        Div tile = new Div();
        tile.addClassName("file-tile");
        if (item.isEncrypted()) {
            tile.addClassName("encrypted");
        }

        boolean showThumbnail = showThumbnails
                && !item.isDirectory()
                && !"../".equals(item.getFilename())
                && item.getId() != null
                && isImageFile(item.getFilename())
                && !item.isEncrypted();

        if (showThumbnail) {
            Image img = new Image("/file-storage-app/files/thumbnail?uuid=" + item.getId(), "");
            img.addClassName("file-tile-thumbnail");
            img.getElement().setAttribute("loading", "lazy");
            tile.add(img);
        } else {
            Icon icon;
            if ("../".equals(item.getFilename())) {
                icon = VaadinIcon.ARROW_UP.create();
            } else if (item.isDirectory()) {
                icon = VaadinIcon.FOLDER.create();
                icon.addClassName("file-tile-icon-folder");
            } else if (item.isEncrypted()) {
                icon = VaadinIcon.LOCK.create();
            } else {
                icon = getFileTypeIcon(item.getFilename());
            }
            icon.addClassName("file-tile-icon");
            tile.add(icon);
        }

        Span nameSpan = new Span(item.getFilename());
        nameSpan.addClassName("file-tile-name");
        tile.add(nameSpan);

        if (!item.isDirectory() && item.getFileSize() != null) {
            Span sizeSpan = new Span(formatFileSize(item.getFileSize()));
            sizeSpan.addClassName("file-tile-size");
            tile.add(sizeSpan);
        }

        tile.addClickListener(e -> handleTileClick(item));

        return tile;
    }

    private Icon getFileTypeIcon(String filename) {
        if (filename == null) return VaadinIcon.FILE.create();
        String ext = FilenameUtils.getExtension(filename).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "bmp", "webp", "svg" -> VaadinIcon.PICTURE.create();
            case "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm" -> VaadinIcon.FILM.create();
            case "mp3", "wav", "flac", "aac", "ogg" -> VaadinIcon.MUSIC.create();
            case "zip", "tar", "gz", "rar", "7z" -> VaadinIcon.PACKAGE.create();
            case "txt", "md", "csv", "json", "xml", "html", "css", "js", "java", "py", "vtt", "srt" -> VaadinIcon.FILE_TEXT.create();
            default -> VaadinIcon.FILE.create();
        };
    }

    private void handleTileClick(Metadata item) {
        if (item.isDirectory()) {
            navigateToDirectory(item);
        } else {
            openDetailsDialog(item);
        }
    }

    private void openDetailsDialog(Metadata item) {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");

        FileViewerView fileViewerView = null;
        if (!item.isEncrypted()) {
            Path file = fileServiceManager.getFile(item);
            fileViewerView = new FileViewerView(file, fileServiceManager, item);
        }

        VerticalLayout dialogLayout = new VerticalLayout();
        HorizontalLayout headerLayout = getDialogTopBarLayout(dialog);

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
                    if (tileViewActive) {
                        refreshTileView();
                    }
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

        Button deleteButton = new Button(item.isDirectory() ? "Delete entire directory" : item.isEncrypted() ? "Delete encrypted file" : "Delete file");
        deleteButton.addClassName("option-button");
        deleteButton.addClassName("option-button-warning");

        deleteButton.addClickListener(click -> {
            fileServiceManager.delete(item);
            removeItemFromGrid(item);
            grid.getDataProvider().refreshAll();
            if (tileViewActive) {
                refreshTileView();
            }
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
            Span sizeInfo = new Span();
            if (item.getFileSize() != null) {
                sizeInfo.setText("Size: " + formatFileSize(item.getFileSize()));
                sizeInfo.getStyle().set("color", "var(--bervan-text-secondary, gray)");
            }

            if (item.isEncrypted()) {
                Paragraph encryptedLabel = new Paragraph("🔒 Encrypted");
                encryptedLabel.getStyle().set("color", "var(--bervan-warning, orange)");
                Button openEncryptedBtn = new Button("Open (enter password)");
                openEncryptedBtn.addClassName("option-button");
                openEncryptedBtn.addClickListener(e -> {
                    dialog.close();
                    openPasswordDialog(item);
                });
                dialogLayout.add(headerLayout, encryptedLabel, filenameLabel, filename, sizeInfo, renameButton,
                        descriptionLabel, description, editableDescription, new Hr(),
                        editButton, saveButton, openEncryptedBtn, new Hr(), deleteButton);
            } else {
                Button encryptButton = new Button("Encrypt file");
                encryptButton.addClassName("option-button");
                encryptButton.addClickListener(e -> openEncryptionDialog(item, dialog));

                if (fileViewerView != null && fileViewerView.isFileSupportView) {
                    dialogLayout.add(headerLayout, fileViewerView, filenameLabel, filename, sizeInfo, renameButton,
                            descriptionLabel, description, editableDescription, new Hr(),
                            editButton, saveButton, encryptButton, downloadLink, new Hr(), deleteButton);
                } else {
                    dialogLayout.add(headerLayout, filenameLabel, filename, sizeInfo, renameButton,
                            descriptionLabel, description, editableDescription, new Hr(),
                            editButton, saveButton, encryptButton, downloadLink, new Hr(), deleteButton);
                }
            }
        }

        dialog.add(dialogLayout);
        dialog.open();
    }

    private void openEncryptionDialog(Metadata item, Dialog parentDialog) {
        Dialog encDialog = new Dialog();
        encDialog.setHeaderTitle("Encrypt File");
        encDialog.setWidth("400px");
        VerticalLayout layout = new VerticalLayout();

        Paragraph warning = new Paragraph("Warning: If you lose the password, the file cannot be recovered!");
        warning.getStyle().set("color", "var(--bervan-warning, orange)");

        PasswordField passwordField = new PasswordField("Password");
        passwordField.setWidthFull();
        PasswordField confirmField = new PasswordField("Confirm Password");
        confirmField.setWidthFull();

        BervanButton encryptBtn = new BervanButton("Encrypt", e -> {
            String pwd = passwordField.getValue();
            String confirm = confirmField.getValue();
            if (pwd.isEmpty()) { showWarningNotification("Password cannot be empty"); return; }
            if (!pwd.equals(confirm)) { showWarningNotification("Passwords do not match"); return; }

            try {
                Path file = fileServiceManager.getFile(item);
                String[] result = fileEncryptionService.encryptFile(file, pwd);
                item.setEncrypted(true);
                item.setEncryptionIv(result[0]);
                item.setEncryptionSalt(result[1]);
                item.setEncryptionVerifier(result[2]);
                fileServiceManager.updateMetadata(item);
                grid.getDataProvider().refreshAll();
                if (tileViewActive) refreshTileView();
                encDialog.close();
                parentDialog.close();
                showSuccessNotification("File encrypted successfully");
            } catch (Exception ex) {
                log.error("Encryption failed", ex);
                showErrorNotification("Encryption failed: " + ex.getMessage());
            }
        });
        BervanButton cancelBtn = new BervanButton("Cancel", e -> encDialog.close());
        encDialog.getFooter().add(cancelBtn, encryptBtn);
        layout.add(warning, passwordField, confirmField);
        encDialog.add(layout);
        encDialog.open();
    }

    private void openPasswordDialog(Metadata item) {
        Dialog pwDialog = new Dialog();
        pwDialog.setHeaderTitle("Enter Password");
        pwDialog.setWidth("400px");
        VerticalLayout layout = new VerticalLayout();

        PasswordField passwordField = new PasswordField("Password");
        passwordField.setWidthFull();
        Paragraph errorMsg = new Paragraph();
        errorMsg.getStyle().set("color", "var(--bervan-danger, red)");
        errorMsg.setVisible(false);

        BervanButton unlockBtn = new BervanButton("Open", e -> {
            String pwd = passwordField.getValue();
            if (pwd.isEmpty()) { errorMsg.setText("Password cannot be empty"); errorMsg.setVisible(true); return; }
            try {
                byte[] key = fileEncryptionService.deriveKey(pwd, item.getEncryptionSalt());
                boolean valid = fileEncryptionService.verifyPassword(key, item.getEncryptionIv(), item.getEncryptionVerifier());
                if (!valid) {
                    errorMsg.setText("Wrong password");
                    errorMsg.setVisible(true);
                    passwordField.clear();
                    return;
                }
                // Store key in session for streaming endpoint
                VaadinSession.getCurrent().getSession().setAttribute("enc_key_" + item.getId(), key);
                VaadinSession.getCurrent().getSession().setAttribute("enc_iv_" + item.getId(), item.getEncryptionIv());
                pwDialog.close();
                openEncryptedViewerDialog(item);
            } catch (Exception ex) {
                log.error("Decryption key derivation failed", ex);
                errorMsg.setText("Error: " + ex.getMessage());
                errorMsg.setVisible(true);
            }
        });

        passwordField.addKeyPressListener(com.vaadin.flow.component.Key.ENTER, ev -> unlockBtn.click());

        BervanButton cancelBtn = new BervanButton("Cancel", e -> pwDialog.close());
        pwDialog.getFooter().add(cancelBtn, unlockBtn);
        layout.add(passwordField, errorMsg);
        pwDialog.add(layout);
        pwDialog.open();
        passwordField.focus();
    }

    private void openEncryptedViewerDialog(Metadata item) {
        Dialog dialog = new Dialog();
        dialog.setWidth("95vw");
        VerticalLayout layout = new VerticalLayout();
        HorizontalLayout header = getDialogTopBarLayout(dialog);

        String streamUrl = "/file-storage-app/files/stream?uuid=" + item.getId();
        String lower = item.getFilename().toLowerCase();

        com.vaadin.flow.component.Component viewer = null;
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov")) {
            String mimeType = lower.endsWith(".webm") ? "video/webm" : lower.endsWith(".mov") ? "video/quicktime" : "video/mp4";
            viewer = new com.vaadin.flow.component.Html(
                "<video controls width=\"100%\" height=\"600\" style=\"max-width:100%\">" +
                "<source src=\"" + streamUrl + "\" type=\"" + mimeType + "\">" +
                "</video>"
            );
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
            com.vaadin.flow.component.html.Image img = new com.vaadin.flow.component.html.Image(streamUrl, item.getFilename());
            img.setMaxWidth("100%");
            viewer = img;
        }

        H5 filename = new H5(item.getFilename());
        Button downloadBtn = new Button("Download (decrypted)");
        downloadBtn.addClassName("option-button");
        downloadBtn.addClickListener(e ->
            UI.getCurrent().getPage().executeJs("window.open($0, '_blank')", streamUrl));

        Button lockBtn = new Button("Lock (clear key)");
        lockBtn.addClassName("option-button");
        lockBtn.addClickListener(e -> {
            VaadinSession.getCurrent().getSession().removeAttribute("enc_key_" + item.getId());
            VaadinSession.getCurrent().getSession().removeAttribute("enc_iv_" + item.getId());
            dialog.close();
            showPrimaryNotification("File locked");
        });

        if (viewer != null) layout.add(header, viewer, filename, downloadBtn, lockBtn);
        else layout.add(header, filename, downloadBtn, lockBtn);

        dialog.add(layout);
        dialog.open();
    }

    @Override
    protected void newItemButtonClick() {
        UploadComponent uploadComponent = new FileTableUploadComponent<>(fileServiceManager, asyncTaskService, path, data, grid, tileContainer, () -> { if (tileViewActive) refreshTileView(); });
        uploadComponent.open();
    }

    static class FileTableUploadComponent<T> extends UploadComponent {

        protected Grid<Metadata> grid;
        protected List<Metadata> data;
        private final Div tileContainer;
        private final Runnable tileRefresher;

        public FileTableUploadComponent(FileServiceManager fileServiceManager, AsyncTaskService asyncTaskService,
                                        String path, List<Metadata> data, Grid<Metadata> grid,
                                        Div tileContainer, Runnable tileRefresher) {
            super(fileServiceManager, asyncTaskService, path);
            this.data = data;
            this.grid = grid;
            this.tileContainer = tileContainer;
            this.tileRefresher = tileRefresher;
        }

        @Override
        protected void postSaveActions() {
            grid.getDataProvider().refreshAll();
            tileRefresher.run();
            super.postSaveActions();
        }

        @Override
        protected void postSavedZipActions(List<Metadata> addedInCurrentPath) {
            data.addAll(addedInCurrentPath);
        }

        @Override
        protected void postSavedFolderActions(List<Metadata> addedInCurrentPath) {
            data.addAll(addedInCurrentPath);
        }

        @Override
        protected void postSaveActions(UploadResponse saved) {
            data.add(saved.getMetadata().get(0));
        }
    }
}
