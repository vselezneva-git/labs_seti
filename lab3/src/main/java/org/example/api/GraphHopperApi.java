package org.example.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.model.LocationItem;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GraphHopperApi extends ApiClient {
    private static final String API_KEY = "bd8f6de0-caa5-41d0-9288-e15e3d332efc";

    public CompletableFuture<List<LocationItem>> searchLocations(String query) {
        String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = String.format("https://graphhopper.com/api/1/geocode?q=%s&key=%s&locale=ru",
                encodedQuery, API_KEY);

        return sendGetRequest(url)
                .thenApply(response -> {
                    JsonObject json = gson.fromJson(response, JsonObject.class);
                    JsonArray hits = json.getAsJsonArray("hits");
                    List<LocationItem> locations = new ArrayList<>();

                    for (JsonElement hit : hits) {
                        JsonObject obj = hit.getAsJsonObject();
                        JsonObject point = obj.getAsJsonObject("point");

                        String name = obj.has("name") ? obj.get("name").getAsString() : "Без названия";
                        String country = obj.has("country") ? obj.get("country").getAsString() : "";
                        String city = obj.has("city") ? obj.get("city").getAsString() : "";

                        double lat = point.get("lat").getAsDouble();
                        double lon = point.get("lng").getAsDouble();

                        String displayName = String.format("%s, %s, %s", name, city, country)
                                .replaceAll(", , ", ", ")
                                .replaceAll("^, |, $", "");

                        locations.add(new LocationItem(displayName, lat, lon));
                    }

                    return locations;
                });
    }
}
