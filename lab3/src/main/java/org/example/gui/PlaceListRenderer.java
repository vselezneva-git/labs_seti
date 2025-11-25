package org.example.gui;

import org.example.model.PlaceItem;

import javax.swing.*;
import java.awt.*;

public class PlaceListRenderer extends DefaultListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value,
                                                  int index, boolean isSelected, boolean cellHasFocus) {
        JTextArea textArea = new JTextArea();
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        if (value instanceof PlaceItem) {
            PlaceItem item = (PlaceItem) value;
            textArea.setText(item.getName() + "\n" + item.getDescription());
        } else {
            textArea.setText(value.toString());
        }

        if (isSelected) {
            textArea.setBackground(list.getSelectionBackground());
            textArea.setForeground(list.getSelectionForeground());
        } else {
            textArea.setBackground(list.getBackground());
            textArea.setForeground(list.getForeground());
        }

        textArea.setFont(list.getFont());
        return textArea;
    }
}
