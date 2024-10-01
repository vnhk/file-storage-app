package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.IFrame;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Base64;

public class TextViewer implements FileViewer {
    @Override
    public boolean supports(String path) {
        return getTextType(path) != null;
    }

    @Override
    public Component buildView(String path) {
        try {
            File file = new File(path);
            byte[] bytes = FileUtils.readFileToByteArray(file);
            String src = "data:text/" + getTextType(path) + ";base64," + Base64.getEncoder().encodeToString(bytes);
            IFrame frame = new IFrame(src);
            frame.setWidth("100%");
            frame.setHeight("600px");
            return frame;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getTextType(String path) {
        if (path.endsWith("txt")) {
            return "plain";
        } else if (path.endsWith("csv")) {
            return "csv";
        }

        return null;
    }
}
