package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.IFrame;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

public class TextViewer implements FileViewer {
    @Override
    public boolean supports(String path) {
        return getMimeType(Path.of(path)) != null;
    }

    @Override
    public Component buildView(String path) {
        try {
            File file = new File(path);
            byte[] bytes = FileUtils.readFileToByteArray(file);
            String mime = getMimeType(Path.of(path));
            String src = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
            IFrame frame = new IFrame(src);
            frame.setWidth("100%");
            frame.setHeight("600px");
            return frame;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getMimeType(Path path) {
        String name = path.toString().toLowerCase();

        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".csv")) return "text/csv";

        return null;
    }
}
