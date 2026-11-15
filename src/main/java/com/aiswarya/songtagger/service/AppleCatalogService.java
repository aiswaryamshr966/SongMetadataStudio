package com.aiswarya.songtagger.service;

import com.aiswarya.songtagger.model.ApiModels.Candidate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AppleCatalogService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AppleCatalogService.class);
    private static final double MIN_ARTIST_OVERLAP = 0.8;
    private static final double MIN_AMBIGUITY_GAP = 0.02;
    private static final double MAX_RUNTIME_DIFFERENCE_SECONDS = 5;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AppleCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Lookup lookup(Candidate candidate) {
        String title = candidate.title() == null ? "" : candidate.title().strip();
        if (title.isBlank() || candidate.artists() == null || candidate.artists().isEmpty()) {
            return new Lookup(null, "");
        }

        String query = title + " " + String.join(" ", candidate.artists());
        URI uri = URI.create("https://itunes.apple.com/search?entity=song&limit=25&country=US&term="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "SongMetadataStudio/1.0")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("Apple catalog lookup returned HTTP {}", response.statusCode());
                return new Lookup(null, "Apple catalog lookup was unavailable; existing sources were used.");
            }
            JsonNode body = objectMapper.readTree(response.body());
            if (body == null) {
                return new Lookup(null, "Apple catalog returned an empty response; existing sources were used.");
            }
            JsonNode results = body.path("results");
            return new Lookup(selectMatch(results, candidate), "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Apple catalog lookup was interrupted.", exception);
            return new Lookup(null, "Apple catalog lookup was interrupted; existing sources were used.");
        } catch (IOException exception) {
            LOGGER.warn("Apple catalog lookup failed.", exception);
            return new Lookup(null, "Apple catalog lookup failed; existing sources were used.");
        }
    }

    static AppleTrack selectMatch(JsonNode results, Candidate candidate) {
        if (results == null || !results.isArray() || candidate.artists() == null
                || candidate.artists().isEmpty()) {
            return null;
        }
        String expectedTitle = normalize(candidate.title());
        if (expectedTitle.isBlank()) {
            return null;
        }
        String expectedArtist = String.join(" ", candidate.artists());
        List<RankedTrack> ranked = new ArrayList<>();
        for (JsonNode result : results) {
            String title = text(result, "trackName");
            String artist = text(result, "artistName");
            if (!expectedTitle.equals(normalize(title))) {
                continue;
            }
            double artistOverlap = artistOverlap(expectedArtist, artist);
            if (artistOverlap < MIN_ARTIST_OVERLAP) {
                continue;
            }
            Integer durationMillis = integer(result, "trackTimeMillis");
            double runtimeDifference = candidate.durationSeconds() == null || durationMillis == null
                    ? Double.NaN
                    : Math.abs(candidate.durationSeconds() - durationMillis / 1000.0);
            if (Double.isFinite(runtimeDifference)
                    && runtimeDifference > MAX_RUNTIME_DIFFERENCE_SECONDS) {
                continue;
            }

            double score = artistOverlap;
            if (Double.isFinite(runtimeDifference)) {
                score += Math.max(0, 0.2 - runtimeDifference * 0.04);
            }
            if (normalize(candidate.album()).equals(normalize(text(result, "collectionName")))) {
                score += 0.05;
            }
            ranked.add(new RankedTrack(result, score));
        }
        ranked.sort(Comparator.comparingDouble(RankedTrack::score).reversed());
        if (ranked.isEmpty() || (ranked.size() > 1
                && ranked.get(0).score() - ranked.get(1).score() < MIN_AMBIGUITY_GAP)) {
            return null;
        }

        JsonNode result = ranked.getFirst().result();
        String trackName = text(result, "trackName");
        String artistName = text(result, "artistName");
        String album = text(result, "collectionName");
        String description = "Apple/iTunes catalog: " + trackName + " — " + artistName
                + (album.isBlank() ? "" : " · " + album);
        return new AppleTrack(
                trackName,
                artistName,
                album,
                text(result, "primaryGenreName"),
                text(result, "releaseDate"),
                integer(result, "trackNumber"),
                integer(result, "trackCount"),
                integer(result, "discNumber"),
                integer(result, "discCount"),
                description);
    }

    private static double artistOverlap(String expected, String actual) {
        Set<String> expectedTokens = tokens(expected);
        Set<String> actualTokens = tokens(actual);
        if (expectedTokens.isEmpty() || actualTokens.isEmpty()) {
            return 0;
        }
        Set<String> shared = new HashSet<>(expectedTokens);
        shared.retainAll(actualTokens);
        return (double) shared.size() / expectedTokens.size();
    }

    private static Set<String> tokens(String value) {
        Set<String> tokens = new HashSet<>();
        for (String token : normalize(value).split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{Alnum}]+", " ")
                .strip();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText().strip();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.canConvertToInt() ? null : value.asInt();
    }

    private record RankedTrack(JsonNode result, double score) {
    }

    public record AppleTrack(String title, String artist, String album, String genre, String date,
                             Integer trackNumber, Integer trackTotal, Integer discNumber,
                             Integer discTotal, String catalogMatch) {
    }

    public record Lookup(AppleTrack track, String warning) {
    }
}
