package com.aiswarya.songtagger.model;

import java.util.List;

public final class ApiModels {
    private ApiModels() {
    }

    public record SearchRequest(String path) {
    }

    public record AudioInfo(String path, String filename, double durationSeconds,
                            String durationLabel, String searchQuery) {
    }

    public record Candidate(String videoId, String title, List<String> artists, String album,
                            String albumId, String duration, Integer durationSeconds,
                            Double durationDifferenceSeconds, Integer year, Integer trackNumber,
                            String thumbnailUrl, String sourceUrl) {
    }

    public record SearchResponse(String searchId, AudioInfo audio, List<Candidate> candidates) {
    }

    public record DetailsRequest(String searchId, String videoId) {
    }

    public record MetadataSuggestion(String title, String artist, String album, String albumArtist,
                                     String composer, String writer, String publisher, String genre,
                                     String date, Integer trackNumber, Integer trackTotal,
                                     Integer discNumber, Integer discTotal, String sourceUrl,
                                     String coverUrl, List<String> creditedArtists,
                                     List<String> warnings) {
    }

    public record ExportRequest(String searchId, String videoId, MetadataUpdate metadata,
                                boolean replaceExisting) {
    }

    public record MetadataUpdate(String title, String artist, String album, String albumArtist,
                                 String composer, String writer, String publisher, String genre,
                                 String date, Integer trackNumber, Integer trackTotal,
                                 Integer discNumber, Integer discTotal, String comment) {
    }

    public record TagEntry(String name, String value) {
    }

    public record ExportResponse(String outputPath, List<TagEntry> writtenTags,
                                 boolean artworkEmbedded) {
    }

    public record HealthResponse(String status, boolean mediaToolsAvailable,
                                 boolean ytmusicAvailable, String message) {
    }

    public record ApiError(String message) {
    }
}
