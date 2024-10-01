package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;

public interface FileViewer {
    boolean supports(String path);

    Component buildView(String path);
}
