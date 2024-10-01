package com.bervan.filestorage.view.fileviever;

import com.bervan.common.AbstractPageView;
import com.bervan.core.model.BervanLogger;
import com.bervan.filestorage.model.Metadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FileViewerView extends AbstractPageView {
    private final BervanLogger log;
    private static List<FileViewer> fileViewers = new ArrayList<>();
    public boolean isFileSupportView = false;
    private boolean fileExists = false;

    static {
        fileViewers.add(new PDFViewer());
        fileViewers.add(new PictureViewer());
        fileViewers.add(new TextViewer());
    }

    public FileViewerView(BervanLogger log, Metadata metadata, String fileServicePath) {
        this.log = log;
        final String finalPath = fileServicePath + File.separator + metadata.getPath() + File.separator + metadata.getFilename();
        log.debug("Loading file view for file: " + finalPath);

        try {
            Optional<FileViewer> fileViewer = fileViewers.stream().filter(e -> e.supports(finalPath))
                    .findFirst();

            try (InputStream __ = new FileInputStream(finalPath)) {
                fileExists = true;
            } catch (Exception e) {
                fileExists = false;
            }

            if (fileViewer.isPresent() && fileExists) {
                add(fileViewer.get().buildView(finalPath));
                isFileSupportView = true;
            } else {
                log.debug("FileViewer not found for: " + finalPath);
            }

        } catch (Exception e) {
            log.error("Unable to load file!", e);
            showErrorNotification("Unable to load file!");
        }
    }
}

