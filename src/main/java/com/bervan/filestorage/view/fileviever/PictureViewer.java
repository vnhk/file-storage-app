package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.IFrame;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Base64;

public class PictureViewer implements FileViewer {
    @Override
    public boolean supports(String path) {
        return getImageType(path) != null;
    }

    @Override
    public Component buildView(String path) {
        try {
            File file = new File(path);
            byte[] bytes = FileUtils.readFileToByteArray(file);
            String src = "data:image/" + getImageType(path) + ";base64," + Base64.getEncoder().encodeToString(bytes);
            IFrame frame = new IFrame(src);
            frame.setWidth("100%");
            frame.setHeight("600px");
            return frame;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getImageType(String path) {
        if (path.endsWith("png")) {
            return "png";
        } else if (path.endsWith("jpg")) {
            return "jpg";
        } else if (path.endsWith("jpeg")) {
            return "jpeg";
        }

        return null;
    }
}
