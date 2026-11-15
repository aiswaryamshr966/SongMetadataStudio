package com.aiswarya.songtagger.service;

import com.aiswarya.songtagger.model.ApiModels.Candidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AppleCatalogServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void selectsExactTitleAndMatchingArtistAndReturnsAvailableFields() throws Exception {
        var results = objectMapper.readTree("""
                {"results": [
                  {"trackName": "Mera Hua", "artistName": "Anmol Salh", "collectionName": "Mera Hua",
                   "primaryGenreName": "Pop", "releaseDate": "2024-08-03T00:00:00Z",
                   "trackNumber": 1, "trackCount": 8, "discNumber": 1, "discCount": 1,
                   "trackTimeMillis": 213000},
                  {"trackName": "Mera Hua", "artistName": "Different Artist", "collectionName": "Other",
                   "trackTimeMillis": 213000}
                ]}
                """);

        var match = AppleCatalogService.selectMatch(results.path("results"),
                candidate("Mera Hua", List.of("Anmol Salh"), 213));

        assertEquals("Anmol Salh", match.artist());
        assertEquals("Pop", match.genre());
        assertEquals(8, match.trackTotal());
        assertEquals("2024-08-03T00:00:00Z", match.date());
    }

    @Test
    void rejectsWrongTitleArtistAndIncompatibleRuntime() throws Exception {
        var results = objectMapper.readTree("""
                {"results": [
                  {"trackName": "Mera Huaa", "artistName": "Anmol Salh", "trackTimeMillis": 213000},
                  {"trackName": "Mera Hua", "artistName": "Another Artist", "trackTimeMillis": 213000},
                  {"trackName": "Mera Hua", "artistName": "Anmol Salh", "trackTimeMillis": 250000}
                ]}
                """);

        assertNull(AppleCatalogService.selectMatch(results.path("results"),
                candidate("Mera Hua", List.of("Anmol Salh"), 213)));
    }

    @Test
    void rejectsAmbiguousCatalogResults() throws Exception {
        var results = objectMapper.readTree("""
                {"results": [
                  {"trackName": "Mera Hua", "artistName": "Anmol Salh", "collectionName": "Album one"},
                  {"trackName": "Mera Hua", "artistName": "Anmol Salh", "collectionName": "Album two"}
                ]}
                """);

        assertNull(AppleCatalogService.selectMatch(results.path("results"),
                candidate("Mera Hua", List.of("Anmol Salh"), null)));
    }

    private static Candidate candidate(String title, List<String> artists, Integer durationSeconds) {
        return new Candidate("video", title, artists, "", "", "", durationSeconds, null,
                null, null, "", "");
    }
}
