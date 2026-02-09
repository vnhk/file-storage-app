package com.bervan.filestorage.view;

import com.bervan.common.component.BervanButton;
import com.bervan.filestorage.model.Metadata;
import com.bervan.filestorage.service.FileServiceManager;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class FolderPickerDialog extends Dialog {

    private final FileServiceManager fileServiceManager;
    private final Consumer<String> onSelect;
    private String currentPath = "/";
    private final H4 pathLabel = new H4();
    private final VerticalLayout folderList = new VerticalLayout();

    public FolderPickerDialog(FileServiceManager fileServiceManager, String title, Consumer<String> onSelect) {
        this.fileServiceManager = fileServiceManager;
        this.onSelect = onSelect;

        setHeaderTitle(title);
        setWidth("500px");
        setHeight("60vh");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSizeFull();

        pathLabel.getStyle()
                .set("font-family", "monospace")
                .set("font-size", "0.9rem")
                .set("margin", "0");

        folderList.setPadding(false);
        folderList.setSpacing(false);
        folderList.getStyle().set("overflow-y", "auto").set("flex-grow", "1");

        content.add(pathLabel, folderList);
        content.setFlexGrow(1, folderList);
        add(content);

        BervanButton selectBtn = new BervanButton("Select this folder", e -> {
            onSelect.accept(currentPath);
            close();
        });

        BervanButton cancelBtn = new BervanButton("Cancel", e -> close());

        getFooter().add(cancelBtn, selectBtn);

        navigateTo("/");
    }

    private void navigateTo(String path) {
        this.currentPath = path;
        pathLabel.setText("Path: " + path);
        folderList.removeAll();

        // Parent folder link
        if (!"/".equals(path) && !path.isBlank()) {
            HorizontalLayout parentRow = createFolderRow("../", () -> {
                String parent = path;
                if (parent.endsWith("/")) parent = parent.substring(0, parent.length() - 1);
                int lastSlash = parent.lastIndexOf("/");
                if (lastSlash <= 0) {
                    navigateTo("/");
                } else {
                    navigateTo(parent.substring(0, lastSlash + 1));
                }
            });
            folderList.add(parentRow);
        }

        // Load directories at this path
        Set<Metadata> items = fileServiceManager.loadByPath(path);
        List<Metadata> directories = items.stream()
                .filter(Metadata::isDirectory)
                .sorted((a, b) -> a.getFilename().compareToIgnoreCase(b.getFilename()))
                .collect(Collectors.toList());

        for (Metadata dir : directories) {
            String childPath;
            if (path.endsWith("/")) {
                childPath = path + dir.getFilename() + "/";
            } else {
                childPath = path + "/" + dir.getFilename() + "/";
            }
            HorizontalLayout row = createFolderRow(dir.getFilename(), () -> navigateTo(childPath));
            folderList.add(row);
        }

        if (directories.isEmpty() && ("/".equals(path) || path.isBlank())) {
            folderList.add(new Span("No folders found"));
        }
    }

    private HorizontalLayout createFolderRow(String name, Runnable onClick) {
        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        row.getStyle()
                .set("padding", "6px 8px")
                .set("cursor", "pointer")
                .set("border-radius", "4px");
        row.getElement().addEventListener("mouseover",
                e -> {}).addEventData("element.style.background='var(--bervan-surface-hover, rgba(255,255,255,0.05))'");
        row.getElement().addEventListener("mouseout",
                e -> {}).addEventData("element.style.background=''");

        Icon folderIcon = VaadinIcon.FOLDER.create();
        folderIcon.setSize("16px");
        Span label = new Span(name);

        row.add(folderIcon, label);
        row.addClickListener(e -> onClick.run());
        return row;
    }
}
