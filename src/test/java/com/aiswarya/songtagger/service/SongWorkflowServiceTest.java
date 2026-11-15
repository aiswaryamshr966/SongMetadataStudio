package com.aiswarya.songtagger.service;

import com.aiswarya.songtagger.model.ApiModels.Candidate;
import com.aiswarya.songtagger.model.ApiModels.MetadataSuggestion;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SongWorkflowServiceTest {
    @Test
    void buildsSearchQueryFromArtistAndTitleFilename() {
        assertEquals("Pritam Falak Dekhun",
                SongWorkflowService.buildSearchQuery("Pritam - Falak Dekhun.webm"));
    }

    @Test
    void preservesVersionTextInSearchQuery() {
        assertEquals("Pritam Kiss Me Baby (Remix Version)",
                SongWorkflowService.buildSearchQuery("Pritam - Kiss Me Baby (Remix Version).webm"));
    }

    @Test
    void formatsDurationsForTheMatchList() {
        assertEquals("5:13", SongWorkflowService.formatDuration(313.341));
        assertEquals("1:01:02", SongWorkflowService.formatDuration(3662));
    }

    @Test
    void fillsMissingFieldsFromSelectedTrackWithoutInventingCredits() {
        var populated = SongWorkflowService.populateMissingMetadata(
                suggestion("", "", "", "", "", "", "", "", null, null, null, null),
                candidate("Mera Hua", List.of("Anmol Salh"), "Album title", 2024, 3),
                Map.of(), null, "");

        assertEquals("Mera Hua", populated.title());
        assertEquals("Anmol Salh", populated.artist());
        assertEquals("Album title", populated.album());
        assertEquals("2024", populated.date());
        assertEquals(3, populated.trackNumber());
        assertEquals("", populated.composer());
        assertEquals("", populated.writer());
        assertEquals("", populated.publisher());
        assertEquals("", populated.albumArtist());
    }

    @Test
    void fillsMissingCreditsAndNumbersOnlyFromSourceTags() {
        var populated = SongWorkflowService.populateMissingMetadata(
                suggestion("Title", "Artist", "", "", "", "", "", "", null, null, null, null),
                candidate("", List.of(), "", null, null),
                Map.of("album", "Source album", "composer", "Source composer",
                        "writer", "Source writer", "publisher", "Source label", "genre", "Pop",
                        "date", "2024-06-01", "track", "3/12", "disc", "1/2",
                        "comment", "Source comment"), null, "");

        assertEquals("Source album", populated.album());
        assertEquals("Source composer", populated.composer());
        assertEquals("Source writer", populated.writer());
        assertEquals("Source label", populated.publisher());
        assertEquals("Pop", populated.genre());
        assertEquals("2024-06-01", populated.date());
        assertEquals(3, populated.trackNumber());
        assertEquals(12, populated.trackTotal());
        assertEquals(1, populated.discNumber());
        assertEquals(2, populated.discTotal());
        assertEquals("Source comment", populated.comment());
    }

    @Test
    void keepsSelectedTrackDetailsAheadOfCandidateAndSourceFallbacks() {
        var populated = SongWorkflowService.populateMissingMetadata(
                suggestion("Selected title", "Selected artist", "Selected album", "", "", "", "", "2023",
                        2, null, null, null),
                candidate("Search title", List.of("Search artist"), "Search album", 2024, 4),
                Map.of("title", "Source title", "composer", "Source composer", "track", "5/10"),
                null, "");

        assertEquals("Selected title", populated.title());
        assertEquals("Selected artist", populated.artist());
        assertEquals("Selected album", populated.album());
        assertEquals("2023", populated.date());
        assertEquals(2, populated.trackNumber());
        assertEquals("Source composer", populated.composer());
    }

    @Test
    void prefersConfidentAppleFieldsAndKeepsCreditsFromExistingSources() {
        var populated = SongWorkflowService.populateMissingMetadata(
                suggestion("YouTube title", "YouTube artist", "YouTube album", "", "", "", "YTM genre",
                        "2022", 2, 10, null, null),
                candidate("Candidate title", List.of("Candidate artist"), "Candidate album", 2021, 3),
                Map.of("composer", "Source composer", "publisher", "Source label"),
                new AppleCatalogService.AppleTrack("Apple title", "Apple artist", "Apple album",
                        "Apple genre", "2023-04-15", 4, 12, 1, 2, "Apple match"),
                "");

        assertEquals("Apple title", populated.title());
        assertEquals("Apple artist", populated.artist());
        assertEquals("Apple album", populated.album());
        assertEquals("Apple genre", populated.genre());
        assertEquals("2023-04-15", populated.date());
        assertEquals(4, populated.trackNumber());
        assertEquals(12, populated.trackTotal());
        assertEquals(1, populated.discNumber());
        assertEquals(2, populated.discTotal());
        assertEquals("Source composer", populated.composer());
        assertEquals("Source label", populated.publisher());
        assertEquals("Apple match", populated.catalogMatch());
    }

    @Test
    void appendsCatalogFailureAsWarningWithoutDroppingCurrentMetadata() {
        var populated = SongWorkflowService.populateMissingMetadata(
                suggestion("YouTube title", "YouTube artist", "", "", "", "", "", "", null, null, null, null),
                candidate("", List.of(), "", null, null),
                Map.of(), null, "Apple catalog lookup failed; existing sources were used.");

        assertEquals("YouTube title", populated.title());
        assertEquals(List.of("Apple catalog lookup failed; existing sources were used."), populated.warnings());
    }

    private static MetadataSuggestion suggestion(String title, String artist, String album,
                                                 String composer, String writer, String publisher,
                                                 String genre, String date,
                                                 Integer track, Integer trackTotal, Integer disc,
                                                 Integer discTotal) {
        return new MetadataSuggestion(title, artist, album, "", composer, writer, publisher, genre,
                date, track, trackTotal, disc, discTotal,
                "source", "cover", List.of(), List.of(), "", "");
    }

    private static Candidate candidate(String title, List<String> artists, String album,
                                       Integer year, Integer trackNumber) {
        return new Candidate("video", title, artists, album, "album", "", null, null,
                year, trackNumber, "", "source");
    }
}
