package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import org.apache.commons.io.FileUtils;

import java.io.File;


public interface FileViewer {
    boolean supports(String path);

    Component buildView(String path);

    default boolean isFileBig(String path) {
        File file = new File(path);
        return file.length() > 3 * FileUtils.ONE_MB;
    }
}
