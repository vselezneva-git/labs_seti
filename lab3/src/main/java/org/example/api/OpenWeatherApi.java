package org.example.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

public class OpenWeatherApi extends ApiClient {
    private static final String API_KEY = "2fdeeabe8f80c9fcdd7d68ffe2800d0a";

    public CompletableFuture<String> getWeather(double lat, double lon) {
        String url = String.format(
                "https://api.openweathermap.org/data/2.5/weather?lat=%f&lon=%f&appid=%s&units=metric&lang=ru",
                lat, lon, API_KEY);

        return sendGetRequest(url)
                .thenApply(response -> {
                    JsonObject json = gson.fromJson(response, JsonObject.class);

                    JsonObject main = json.getAsJsonObject("main");
                    JsonArray weatherArray = json.getAsJsonArray("weather");
                    JsonObject weather = weatherArray.get(0).getAsJsonObject();
                    JsonObject wind = json.getAsJsonObject("wind");

                    double temp = main.get("temp").getAsDouble();
                    double feelsLike = main.get("feels_like").getAsDouble();
                    int humidity = main.get("humidity").getAsInt();
                    int pressure = main.get("pressure").getAsInt();
                    String description = weather.get("description").getAsString();
                    double windSpeed = wind.get("speed").getAsDouble();

                    return String.format(
                            "Temperature: %.1f°C (feels like %.1f°C)\n" +
                                    "Description: %s\n" +
                                    "Humidity: %d%%\n" +
                                    "Wind: %.1f m/s\n" +
                                    "Pressure: %d mmHg",
                            temp, feelsLike, description, humidity, windSpeed, pressure);
                });
    }
}
