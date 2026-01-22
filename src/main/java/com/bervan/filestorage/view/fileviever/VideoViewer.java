package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.server.StreamResource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

public class VideoViewer implements FileViewer {

    @Override
    public boolean supports(String path) {
        return getVideoType(path) != null;
    }

    @Override
    public Component buildView(String path) {
        File file = new File(path);

        StreamResource resource = new StreamResource(
                file.getName(),
                () -> {
                    try {
                        return new FileInputStream(file);
                    } catch (FileNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                }
        );

        String mimeType = getMimeType(path);
        resource.setContentType(mimeType);

        String html = """
            <video controls width="100%%" height="600">
                <source src="%s" type="%s">
                Your browser does not support the video tag.
            </video>
            """.formatted(resource, mimeType);

        return new Html(html);
    }

    private String getVideoType(String path) {
        path = path.toLowerCase();
        if (path.endsWith(".mp4")) return "mp4";
        if (path.endsWith(".mov")) return "mov";
        if (path.endsWith(".webm")) return "webm";
        return null;
    }

    private String getMimeType(String path) {
        if (path.endsWith(".mp4")) return "video/mp4";
        if (path.endsWith(".mov")) return "video/quicktime";
        if (path.endsWith(".webm")) return "video/webm";
        return "video/mp4";
    }
}