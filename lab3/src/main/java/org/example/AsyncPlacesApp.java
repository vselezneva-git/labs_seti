package org.example;

import org.example.gui.MainWindow;

import javax.swing.*;

public class AsyncPlacesApp {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainWindow app = new MainWindow();
            app.setVisible(true);
        });
    }
}