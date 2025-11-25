package org.example.model;

public class PlaceItem {
    private final String name;
    private final String description;
    private final String kind;

    public PlaceItem(String name, String description, String kind) {
        this.name = name;
        this.description = description;
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getKind() {
        return kind;
    }
}