package org.example.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.example.model.PlaceItem;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class GeoapifyApi extends ApiClient {
    private static final String API_KEY = "730d33e3b38347d8b05b6e5602d6280f";

    public CompletableFuture<List<PlaceItem>> getPlacesWithDescriptions(double lat, double lon) {
        String url = String.format(java.util.Locale.US,
                "https://api.geoapify.com/v2/places?categories=tourism,entertainment,leisure,sport,heritage&filter=circle:%.6f,%.6f,5000&limit=10&apiKey=%s",
                lon, lat, API_KEY);

        return sendGetRequest(url)
                .thenCompose(response -> {
                    JsonObject json = gson.fromJson(response, JsonObject.class);

                    if (!json.has("features")) {
                        return CompletableFuture.completedFuture(new ArrayList<PlaceItem>());
                    }

                    JsonArray features = json.getAsJsonArray("features");
                    List<CompletableFuture<PlaceItem>> detailsFutures = new ArrayList<>();

                    for (JsonElement element : features) {
                        JsonObject feature = element.getAsJsonObject();
                        JsonObject properties = feature.getAsJsonObject("properties");

                        String placeId = properties.has("place_id") && !properties.get("place_id").isJsonNull()
                                ? properties.get("place_id").getAsString()
                                : null;

                        String name = "Место без названия";
                        if (properties.has("name") && !properties.get("name").isJsonNull()) {
                            name = properties.get("name").getAsString();
                        }

                        String category = "interesting_place";
                        if (properties.has("categories") && properties.get("categories").isJsonArray()) {
                            JsonArray categories = properties.getAsJsonArray("categories");
                            if (categories.size() > 0) {
                                category = categories.get(0).getAsString();
                            }
                        }

                        if (placeId != null) {
                            CompletableFuture<PlaceItem> detailFuture = getPlaceDetails(placeId, name, category);
                            detailsFutures.add(detailFuture);
                        } else {
                            detailsFutures.add(CompletableFuture.completedFuture(
                                    new PlaceItem(name, "", category)
                            ));
                        }
                    }

                    if (detailsFutures.isEmpty()) {
                        return CompletableFuture.completedFuture(new ArrayList<PlaceItem>());
                    }

                    return CompletableFuture.allOf(detailsFutures.toArray(new CompletableFuture[0]))
                            .thenApply(v -> {
                                List<PlaceItem> results = new ArrayList<>();
                                for (CompletableFuture<PlaceItem> future : detailsFutures) {
                                    try {
                                        results.add(future.join());
                                    } catch (Exception e) {
                                    }
                                }
                                return results;
                            });
                })
                .exceptionally(ex -> new ArrayList<PlaceItem>());
    }

    private CompletableFuture<PlaceItem> getPlaceDetails(String placeId, String name, String category) {
        String url = String.format(
                "https://api.geoapify.com/v2/place-details?id=%s&apiKey=%s",
                placeId, API_KEY);

        return sendGetRequest(url)
                .thenApply(response -> {
                    try {
                        JsonObject json = gson.fromJson(response, JsonObject.class);
                        JsonObject properties = null;

                        if (json.has("features") && json.get("features").isJsonArray()) {
                            JsonArray features = json.getAsJsonArray("features");
                            if (features.size() > 0) {
                                properties = features.get(0).getAsJsonObject().getAsJsonObject("properties");
                            }
                        } else if (json.has("properties")) {
                            properties = json.getAsJsonObject("properties");
                        }

                        StringBuilder description = new StringBuilder();
                        
                        if (properties.has("address_line2") && !properties.get("address_line2").isJsonNull()) {
                            String address = properties.get("address_line2").getAsString();
                            if (!address.isEmpty() && address.length() < 100) {
                                description.append("Адрес: ").append(address);
                            }
                        }

                        if (properties.has("datasource") && !properties.get("datasource").isJsonNull()) {
                            JsonObject datasource = properties.getAsJsonObject("datasource");
                            if (datasource.has("raw") && !datasource.get("raw").isJsonNull()) {
                                JsonObject raw = datasource.getAsJsonObject("raw");
                                if (raw.has("description") && !raw.get("description").isJsonNull()) {
                                    String desc = raw.get("description").getAsString();
                                    if (desc.length() > 100) {
                                        desc = desc.substring(0, 97) + "...";
                                    }
                                    if (!desc.isEmpty()) {
                                        if (description.length() > 0) description.append("\n");
                                        description.append("Описание: ").append(desc);
                                    }
                                }
                            }
                        }

                        return new PlaceItem(name, description.toString(), category);

                    } catch (Exception e) {
                        return new PlaceItem(name, "", category);
                    }
                })
                .exceptionally(ex -> new PlaceItem(name, "", category));
    }
}
