package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.IFrame;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.Base64;

public class PDFViewer implements FileViewer {
    @Override
    public boolean supports(String path) {
        return path.endsWith("pdf");
    }

    @Override
    public Component buildView(String path) {

        try {
            File pdfFile = new File(path);
            byte[] bytes = FileUtils.readFileToByteArray(pdfFile);
            String src = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(bytes);
            IFrame pdfFrame = new IFrame(src);
            pdfFrame.setWidth("100%");
            pdfFrame.setHeight("600px");
            pdfFrame.getElement().setAttribute("type", "application/pdf");
            return pdfFrame;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}
