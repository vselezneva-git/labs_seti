package org.example.service;

import org.example.api.GeoapifyApi;
import org.example.api.GraphHopperApi;
import org.example.api.OpenWeatherApi;
import org.example.model.LocationItem;
import org.example.model.PlaceItem;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PlacesService {
    private final GraphHopperApi graphHopperApi;
    private final OpenWeatherApi openWeatherApi;
    private final GeoapifyApi geoapifyApi;

    public PlacesService() {
        this.graphHopperApi = new GraphHopperApi();
        this.openWeatherApi = new OpenWeatherApi();
        this.geoapifyApi = new GeoapifyApi();
    }

    public CompletableFuture<List<LocationItem>> searchLocations(String query) {
        return graphHopperApi.searchLocations(query);
    }

    public CompletableFuture<String> getWeather(double lat, double lon) {
        return openWeatherApi.getWeather(lat, lon);
    }

    public CompletableFuture<List<PlaceItem>> getPlacesWithDescriptions(double lat, double lon) {
        return geoapifyApi.getPlacesWithDescriptions(lat, lon);
    }
}