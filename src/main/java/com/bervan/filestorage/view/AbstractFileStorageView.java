package com.bervan.filestorage.view;

import com.bervan.asynctask.AsyncTask;
import com.bervan.asynctask.AsyncTaskService;
import com.bervan.common.component.BervanButton;
import com.bervan.common.view.AbstractBervanTableView;
import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.model.UploadResponse;
import com.bervan.filestorage.service.FileServiceManager;
import com.bervan.filestorage.service.LoadStorageAndIntegrateWithDB;
import com.bervan.filestorage.view.fileviever.FileViewerView;
import com.vaadin.flow.component.Text;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
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
import com.vaadin.flow.data.provider.SortDirection;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.router.QueryParameters;
import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
public abstract class AbstractFileStorageView extends AbstractBervanTableView<UUID, Metadata> {
    public static final String ROUTE_NAME = "file-storage-app/files";
    private final FileServiceManager fileServiceManager;
    private final String maxFileSize;
    private final AsyncTaskService asyncTaskService;
    private final LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB;
    private final H4 pathInfoComponent = new H4();
    private String path = "/";
    @Value("${file.service.storage.folder}")
    private String FOLDER;

    public AbstractFileStorageView(FileServiceManager service, String maxFileSize, LoadStorageAndIntegrateWithDB loadStorageAndIntegrateWithDB, BervanLogger log, AsyncTaskService asyncTaskService) {
        super(new FileStorageAppPageLayout(ROUTE_NAME), service, log, Metadata.class);
        this.asyncTaskService = asyncTaskService;
        super.checkboxesColumnsEnabled = false;
        this.fileServiceManager = service;
        this.maxFileSize = maxFileSize;
        this.loadStorageAndIntegrateWithDB = loadStorageAndIntegrateWithDB;
        render();
    }

    private void render() {
        renderCommonComponents();
        contentLayout.remove(addButton);

        Button synchronizeDBWithStorageFilesButton = new BervanButton("Synchronize All", buttonClickEvent -> {
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
                        asyncTaskService.setFailed(asyncTask, e.getMessage());
                    }
                }).start();
            } catch (Exception e) {
                showErrorNotification(e.getMessage());
            }
        });

        Button createDirectory = new BervanButton("New Folder");

        createDirectory.addClickListener(buttonClickEvent -> {
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
        });

        addButton.setText("Upload file");
        addButton.addClassName("option-button");

        HorizontalLayout buttons = new HorizontalLayout(addButton, synchronizeDBWithStorageFilesButton, createDirectory);
        contentLayout.addComponentAtIndex(0, pathInfoComponent);
        contentLayout.addComponentAtIndex(0, new Hr());
        contentLayout.addComponentAtIndex(0, buttons);
        contentLayout.addComponentAtIndex(0, new Hr());
        contentLayout.addComponentAtIndex(0, new H4("Max File Size: " + maxFileSize));

        paginationBar.setVisible(false);
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

        FileViewerView fileViewerView = new FileViewerView(event.getItem(), FOLDER);

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
