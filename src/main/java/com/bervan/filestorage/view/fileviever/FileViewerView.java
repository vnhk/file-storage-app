package com.bervan.filestorage.view.fileviever;

import com.bervan.common.view.AbstractPageView;
import com.bervan.logging.JsonLogger;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FileViewerView extends AbstractPageView {
    private static List<FileViewer> fileViewers = new ArrayList<>();

    static {
        fileViewers.add(new PDFViewer());
        fileViewers.add(new PictureViewer());
        fileViewers.add(new VideoViewer());
        fileViewers.add(new TextViewer());
    }

    private final JsonLogger log = JsonLogger.getLogger(getClass(), "file-storage");
    public boolean isFileSupportView = false;
    private boolean fileExists = false;
    private boolean isFileBig = false;

    public FileViewerView(Path path) {
        removeClassName("bervan-page");
        try {
            Optional<FileViewer> fileViewer = fileViewers.stream().filter(e -> e.supports(path.toString()))
                    .findFirst();

            try (InputStream __ = new FileInputStream(path.toFile())) {
                fileExists = true;
            } catch (Exception e) {
                fileExists = false;
            }

            log.debug("Loading file view for file: " + path);

            if (fileViewer.isPresent() && fileExists) {
                FileViewer fileViewerProcessor = fileViewer.get();

                isFileBig = fileViewerProcessor.isFileBig(path.toString());

                if (!isFileBig) {
                    add(fileViewerProcessor.buildView(path.toString()));
                }

                Button openFileInWindowButton = new Button("Open file");
                openFileInWindowButton.addClassName("option-button");
                openFileInWindowButton.addClickListener(cEvent -> {
                    Dialog filePreview = new Dialog();
                    filePreview.setWidth("90vw");
                    filePreview.setHeight("90vh");
                    VerticalLayout filePreviewLayout = new VerticalLayout();
                    HorizontalLayout filePreviewHeaderLayout = getDialogTopBarLayout(filePreview);

                    filePreviewLayout.add(filePreviewHeaderLayout, fileViewerProcessor.buildView(path.toString()));
                    filePreview.add(filePreviewLayout);

                    filePreview.open();
                });

                add(openFileInWindowButton);
                isFileSupportView = true;
            } else {
                log.debug("FileViewer not found for: " + path);
            }

        } catch (Exception e) {
            log.error("Unable to load file!", e);
            showErrorNotification("Unable to load file!");
        }
    }

    public boolean isFileBig() {
        return isFileBig;
    }

    protected HorizontalLayout getDialogTopBarLayout(Dialog dialog) {
        Button closeButton = new Button(new Icon(VaadinIcon.CLOSE));
        closeButton.addClassName("option-button");

        closeButton.addClickListener(e -> dialog.close());
        HorizontalLayout headerLayout = new HorizontalLayout(closeButton);
        headerLayout.setWidthFull();
        headerLayout.setJustifyContentMode(FlexComponent.JustifyContentMode.END);
        return headerLayout;
    }
}

