package com.bervan.filestorage.view;

import com.bervan.common.AbstractPageLayout;
import com.vaadin.flow.component.notification.Notification;

public final class FileStorageAppPageLayout extends AbstractPageLayout {

    public FileStorageAppPageLayout(String currentRouteName) {
        super(currentRouteName);
    }

    public void notification(String message) {
        Notification.show(message);
    }
}
