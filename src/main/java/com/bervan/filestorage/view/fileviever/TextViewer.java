package com.bervan.filestorage.view.fileviever;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.IFrame;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.function.Consumer;

public class TextViewer implements FileViewer {
    private static final long MAX_EDITABLE_SIZE = 2 * FileUtils.ONE_MB;

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

    public boolean isEditable(String path) {
        File file = new File(path);
        return file.exists() && file.length() <= MAX_EDITABLE_SIZE && supports(path);
    }

    public Component buildEditView(String path, Consumer<byte[]> onSave) {
        try {
            File file = new File(path);
            byte[] bytes = FileUtils.readFileToByteArray(file);
            String content = new String(bytes, StandardCharsets.UTF_8);

            VerticalLayout layout = new VerticalLayout();
            layout.setPadding(false);
            layout.setWidthFull();

            TextArea textArea = new TextArea();
            textArea.setValue(content);
            textArea.setWidthFull();
            textArea.setHeight("600px");
            textArea.getStyle().set("font-family", "monospace");
            textArea.getStyle().set("font-size", "0.9rem");

            Button saveBtn = new Button("Save", e -> {
                onSave.accept(textArea.getValue().getBytes(StandardCharsets.UTF_8));
            });
            saveBtn.addClassName("option-button");

            layout.add(textArea, saveBtn);
            return layout;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private String getMimeType(Path path) {
        String name = path.toString().toLowerCase();

        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".vtt")) return "text/plain";
        if (name.endsWith(".srt")) return "text/plain";
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".csv")) return "text/csv";

        return null;
    }
}
