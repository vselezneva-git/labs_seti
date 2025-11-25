package org.example.gui;

import org.example.model.LocationItem;
import org.example.model.PlaceItem;
import org.example.service.PlacesService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public class MainWindow extends JFrame {
    private final PlacesService placesService;

    private JTextField searchField;
    private JButton searchButton;
    private JList<LocationItem> locationList;
    private DefaultListModel<LocationItem> locationModel;
    private JTextArea weatherArea;
    private JList<PlaceItem> placesListView;
    private DefaultListModel<PlaceItem> placesModel;
    private JProgressBar progressBar;

    public MainWindow() {
        this.placesService = new PlacesService();

        setTitle("Places and Weather Search");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        initUI();
    }

    private void initUI() {
        JPanel searchPanel = new JPanel(new BorderLayout(5, 5));
        searchPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        searchField = new JTextField();
        searchButton = new JButton("Search");
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        searchPanel.add(new JLabel("Enter location name:"), BorderLayout.NORTH);
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);
        searchPanel.add(progressBar, BorderLayout.SOUTH);

        add(searchPanel, BorderLayout.NORTH);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

        JPanel leftPanel = new JPanel(new BorderLayout(5, 5));
        leftPanel.setBorder(BorderFactory.createTitledBorder("Found Locations"));

        locationModel = new DefaultListModel<>();
        locationList = new JList<>(locationModel);
        locationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        leftPanel.add(new JScrollPane(locationList), BorderLayout.CENTER);

        mainSplit.setLeftComponent(leftPanel);

        JPanel rightPanel = new JPanel(new BorderLayout(5, 5));

        JPanel weatherPanel = new JPanel(new BorderLayout());
        weatherPanel.setBorder(BorderFactory.createTitledBorder("Weather"));

        weatherArea = new JTextArea(5, 30);
        weatherArea.setEditable(false);
        weatherArea.setLineWrap(true);
        weatherArea.setWrapStyleWord(true);

        weatherPanel.add(new JScrollPane(weatherArea), BorderLayout.CENTER);

        JPanel placesPanel = new JPanel(new BorderLayout());
        placesPanel.setBorder(BorderFactory.createTitledBorder("Interesting Places"));

        placesModel = new DefaultListModel<>();
        placesListView = new JList<>(placesModel);
        placesListView.setCellRenderer(new PlaceListRenderer());

        placesPanel.add(new JScrollPane(placesListView), BorderLayout.CENTER);

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        rightSplit.setTopComponent(weatherPanel);
        rightSplit.setBottomComponent(placesPanel);
        rightSplit.setDividerLocation(150);

        rightPanel.add(rightSplit, BorderLayout.CENTER);
        mainSplit.setRightComponent(rightPanel);
        mainSplit.setDividerLocation(300);

        add(mainSplit, BorderLayout.CENTER);

        searchButton.addActionListener(e -> performSearch());
        searchField.addActionListener(e -> performSearch());

        locationList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                LocationItem selected = locationList.getSelectedValue();
                if (selected != null) {
                    handleLocationSelection(selected);
                }
            }
        });
    }

    private void performSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Enter a location name");
            return;
        }

        locationModel.clear();
        weatherArea.setText("");
        placesModel.clear();
        progressBar.setVisible(true);
        searchButton.setEnabled(false);

        placesService.searchLocations(query)
                .thenAccept(locations -> SwingUtilities.invokeLater(() -> {
                    progressBar.setVisible(false);
                    searchButton.setEnabled(true);
                    if (locations.isEmpty()) {
                        JOptionPane.showMessageDialog(this, "No locations found");
                    } else {
                        locations.forEach(locationModel::addElement);
                    }
                }))
                .exceptionally(ex -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setVisible(false);
                        searchButton.setEnabled(true);
                        JOptionPane.showMessageDialog(this,
                                "Search error: " + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                    });
                    ex.printStackTrace();
                    return null;
                });
    }

    private void handleLocationSelection(LocationItem location) {
        weatherArea.setText("Loading weather...");
        placesModel.clear();
        placesModel.addElement(new PlaceItem("Loading interesting places...", "", ""));

        var weatherFuture = placesService.getWeather(location.getLat(), location.getLon());
        var placesFuture = placesService.getPlacesWithDescriptions(location.getLat(), location.getLon());

        weatherFuture.thenAccept(weather ->
                SwingUtilities.invokeLater(() -> weatherArea.setText(weather))
        ).exceptionally(ex -> {
            SwingUtilities.invokeLater(() ->
                    weatherArea.setText("Weather loading error: " + ex.getMessage())
            );
            ex.printStackTrace();
            return null;
        });

        placesFuture.thenAccept(places ->
                SwingUtilities.invokeLater(() -> {
                    placesModel.clear();
                    if (places.isEmpty()) {
                        placesModel.addElement(new PlaceItem("No interesting places found",
                                "Try selecting a larger city", ""));
                    } else {
                        places.forEach(placesModel::addElement);
                    }
                })
        ).exceptionally(ex -> {
            SwingUtilities.invokeLater(() -> {
                placesModel.clear();
                placesModel.addElement(new PlaceItem("Places loading error",
                        ex.getMessage(), ""));
            });
            ex.printStackTrace();
            return null;
        });
    }
}
