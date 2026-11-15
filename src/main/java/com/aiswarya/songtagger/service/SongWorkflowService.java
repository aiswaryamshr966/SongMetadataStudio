package com.aiswarya.songtagger.service;

import com.aiswarya.songtagger.model.ApiModels.AudioInfo;
import com.aiswarya.songtagger.model.ApiModels.Candidate;
import com.aiswarya.songtagger.model.ApiModels.ExportResponse;
import com.aiswarya.songtagger.model.ApiModels.MetadataSuggestion;
import com.aiswarya.songtagger.model.ApiModels.MetadataUpdate;
import com.aiswarya.songtagger.model.ApiModels.SearchResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aiswarya.songtagger.model.ApiModels.*;

@Service
public class SongWorkflowService {
    private static final Duration SESSION_TTL = Duration.ofHours(2);
    private final YouTubeMusicBridge bridge;
    private final MediaService mediaService;
    private final OutputDirectoryService outputDirectoryService;
    private final AppleCatalogService appleCatalogService;
    private final ObjectMapper objectMapper;
    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();

    public SongWorkflowService(YouTubeMusicBridge bridge, MediaService mediaService,
                              OutputDirectoryService outputDirectoryService,
                              AppleCatalogService appleCatalogService, ObjectMapper objectMapper) {
        this.bridge = bridge;
        this.mediaService = mediaService;
        this.outputDirectoryService = outputDirectoryService;
        this.appleCatalogService = appleCatalogService;
        this.objectMapper = objectMapper;
    }

    public SearchResponse search(String submittedPath) {
        Path source = resolveSource(submittedPath);
        MediaService.Inspection inspection = mediaService.inspect(source);
        String query = buildSearchQuery(source.getFileName().toString());
        JsonNode response = bridge.call("search", query, "20");
        JsonNode results = response.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new WorkflowException(HttpStatus.NOT_FOUND,
                    "YouTube Music found no song candidates for: " + query);
        }

        List<Candidate> candidates = new ArrayList<>();
        for (JsonNode result : results) {
            String videoId = text(result, "videoId");
            if (videoId.isBlank()) {
                continue;
            }
            Integer durationSeconds = integer(result, "durationSeconds");
            Double difference = durationSeconds == null
                    ? null
                    : Math.abs(inspection.durationSeconds() - durationSeconds);
            candidates.add(new Candidate(
                    videoId,
                    text(result, "title"),
                    stringList(result.path("artists")),
                    text(result, "album"),
                    text(result, "albumId"),
                    text(result, "duration"),
                    durationSeconds,
                    difference,
                    integer(result, "year"),
                    integer(result, "trackNumber"),
                    text(result, "thumbnailUrl"),
                    "https://music.youtube.com/watch?v=" + videoId));
        }
        if (candidates.isEmpty()) {
            throw new WorkflowException(HttpStatus.NOT_FOUND,
                    "YouTube Music returned no selectable songs for: " + query);
        }

        candidates.sort(Comparator.comparing(
                candidate -> candidate.durationDifferenceSeconds() == null
                        ? Double.MAX_VALUE
                        : candidate.durationDifferenceSeconds()));
        pruneExpiredSessions();
        String searchId = UUID.randomUUID().toString();
        SearchSession session = new SearchSession(
                source, inspection.durationSeconds(), query, candidates, inspection.tags());
        sessions.put(searchId, session);

        AudioInfo audio = new AudioInfo(source.toString(), source.getFileName().toString(),
                inspection.durationSeconds(), formatDuration(inspection.durationSeconds()), query);
        return new SearchResponse(searchId, audio, candidates);
    }

    public MetadataSuggestion details(String searchId, String videoId) {
        SearchSession session = requireSession(searchId);
        Candidate candidate = session.candidates().get(videoId);
        if (candidate == null) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "That song was not part of this YouTube Music search.");
        }

        JsonNode response = bridge.call("details", candidate.videoId(),
                candidate.albumId() == null ? "" : candidate.albumId());
        MetadataSuggestion suggestion;
        try {
            suggestion = objectMapper.treeToValue(response, MetadataSuggestion.class);
        } catch (Exception exception) {
            throw new WorkflowException(HttpStatus.BAD_GATEWAY,
                    "YouTube Music returned incomplete track details.", exception);
        }
        AppleCatalogService.Lookup lookup = appleCatalogService.lookup(candidate);
        suggestion = populateMissingMetadata(
                suggestion, candidate, session.sourceTags(), lookup.track(), lookup.warning());
        session.details().put(videoId, suggestion);
        return suggestion;
    }

    public ExportResponse export(String searchId, String videoId, MetadataUpdate metadata,
                                 boolean replaceExisting, String requestedOutputDirectory) {
        SearchSession session = requireSession(searchId);
        if (!session.candidates().containsKey(videoId)) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "That song was not part of this YouTube Music search.");
        }
        MetadataSuggestion selection = session.details().get(videoId);
        if (selection == null) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "Review the selected song details before creating the FLAC.");
        }
        Path outputDirectory = outputDirectoryService.resolve(requestedOutputDirectory);
        outputDirectoryService.registerPlaybackDirectory(outputDirectory);
        return mediaService.export(session.source(), metadata, selection.coverUrl(), replaceExisting,
                outputDirectory);
    }

    static String buildSearchQuery(String filename) {
        String stem = filename.replaceFirst("(?i)\\.[^.]+$", "");
        return stem.replaceAll("[_-]+", " ")
                .replaceAll("\\s*-\\s*", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    static String formatDuration(double seconds) {
        long totalSeconds = Math.max(0, Math.round(seconds));
        long hours = totalSeconds / 3600;
        long minutes = totalSeconds % 3600 / 60;
        long remainder = totalSeconds % 60;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, remainder)
                : String.format("%d:%02d", minutes, remainder);
    }

    static MetadataSuggestion populateMissingMetadata(MetadataSuggestion suggestion, Candidate candidate,
                                                       Map<String, String> sourceTags,
                                                       AppleCatalogService.AppleTrack appleTrack,
                                                       String catalogWarning) {
        String trackTag = sourceTag(sourceTags, "track", "tracknumber");
        String discTag = sourceTag(sourceTags, "disc", "discnumber");
        List<String> warnings = new ArrayList<>(suggestion.warnings() == null
                ? List.of()
                : suggestion.warnings());
        if (catalogWarning != null && !catalogWarning.isBlank()) {
            warnings.add(catalogWarning);
        }
        return new MetadataSuggestion(
                firstText(appleText(appleTrack, AppleCatalogService.AppleTrack::title),
                        suggestion.title(), candidate.title(), sourceTag(sourceTags, "title")),
                firstText(appleText(appleTrack, AppleCatalogService.AppleTrack::artist),
                        suggestion.artist(), String.join(", ", candidate.artists()),
                        sourceTag(sourceTags, "artist", "performer")),
                firstText(appleText(appleTrack, AppleCatalogService.AppleTrack::album),
                        suggestion.album(), candidate.album(), sourceTag(sourceTags, "album")),
                firstText(suggestion.albumArtist(), sourceTag(sourceTags, "albumartist")),
                firstText(suggestion.composer(), sourceTag(sourceTags, "composer")),
                firstText(suggestion.writer(), sourceTag(sourceTags, "writer", "writers", "lyricist")),
                firstText(suggestion.publisher(), sourceTag(sourceTags, "publisher", "label", "recordlabel")),
                firstText(appleText(appleTrack, AppleCatalogService.AppleTrack::genre),
                        suggestion.genre(), sourceTag(sourceTags, "genre")),
                firstText(appleText(appleTrack, AppleCatalogService.AppleTrack::date),
                        suggestion.date(), candidate.year() == null ? "" : candidate.year().toString(),
                        sourceTag(sourceTags, "date", "releasedate", "originaldate", "year")),
                firstNumber(appleNumber(appleTrack, AppleCatalogService.AppleTrack::trackNumber),
                        suggestion.trackNumber(), candidate.trackNumber(), taggedNumber(trackTag)),
                firstNumber(appleNumber(appleTrack, AppleCatalogService.AppleTrack::trackTotal),
                        suggestion.trackTotal(),
                        taggedNumber(sourceTag(sourceTags, "tracktotal", "totaltracks")), taggedTotal(trackTag)),
                firstNumber(appleNumber(appleTrack, AppleCatalogService.AppleTrack::discNumber),
                        suggestion.discNumber(), taggedNumber(discTag)),
                firstNumber(appleNumber(appleTrack, AppleCatalogService.AppleTrack::discTotal),
                        suggestion.discTotal(),
                        taggedNumber(sourceTag(sourceTags, "disctotal", "totaldiscs")), taggedTotal(discTag)),
                suggestion.sourceUrl(),
                suggestion.coverUrl(),
                suggestion.creditedArtists(),
                List.copyOf(warnings),
                firstText(suggestion.comment(), sourceTag(sourceTags, "comment")),
                appleTrack == null ? "" : appleTrack.catalogMatch());
    }

    private static String appleText(AppleCatalogService.AppleTrack track,
                                    java.util.function.Function<AppleCatalogService.AppleTrack, String> getter) {
        return track == null ? "" : getter.apply(track);
    }

    private static Integer appleNumber(AppleCatalogService.AppleTrack track,
                                       java.util.function.Function<AppleCatalogService.AppleTrack, Integer> getter) {
        return track == null ? null : getter.apply(track);
    }

    private static String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static Integer firstNumber(Integer... values) {
        for (Integer value : values) {
            if (value != null && value > 0) {
                return value;
            }
        }
        return null;
    }

    private static String sourceTag(Map<String, String> tags, String... names) {
        for (String name : names) {
            String value = tags.get(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private static Integer taggedNumber(String value) {
        String number = value.split("/", 2)[0].strip();
        try {
            return Integer.valueOf(number);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Integer taggedTotal(String value) {
        String[] parts = value.split("/", 2);
        if (parts.length < 2) {
            return null;
        }
        try {
            return Integer.valueOf(parts[1].strip());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Path resolveSource(String submittedPath) {
        if (submittedPath == null || submittedPath.isBlank()) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST, "Enter a full song path.");
        }
        String rawPath = submittedPath.strip();
        if (rawPath.length() >= 2 && rawPath.startsWith("\"") && rawPath.endsWith("\"")) {
            rawPath = rawPath.substring(1, rawPath.length() - 1);
        }
        try {
            Path source = Path.of(rawPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
                throw new WorkflowException(HttpStatus.BAD_REQUEST,
                        "The path is not a readable file: " + source);
            }
            return source;
        } catch (java.nio.file.InvalidPathException exception) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST, "The song path is invalid.", exception);
        }
    }

    private SearchSession requireSession(String searchId) {
        SearchSession session = sessions.get(searchId);
        if (session == null || session.createdAt().plus(SESSION_TTL).isBefore(Instant.now())) {
            sessions.remove(searchId);
            throw new WorkflowException(HttpStatus.GONE,
                    "This search has expired. Search for the file again.");
        }
        return session;
    }

    private void pruneExpiredSessions() {
        Instant cutoff = Instant.now().minus(SESSION_TTL);
        sessions.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(cutoff));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.canConvertToInt() ? null : value.asInt();
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                String text = value.asText().strip();
                if (!text.isBlank()) {
                    values.add(text);
                }
            });
        }
        return List.copyOf(values);
    }

    private record SearchSession(Path source, double durationSeconds, String query,
                                 Map<String, Candidate> candidates,
                                 Map<String, MetadataSuggestion> details,
                                 Map<String, String> sourceTags,
                                 Instant createdAt) {
        private SearchSession(Path source, double durationSeconds, String query,
                              List<Candidate> candidates, Map<String, String> sourceTags) {
            this(source, durationSeconds, query, indexCandidates(candidates),
                    new ConcurrentHashMap<>(), Map.copyOf(sourceTags), Instant.now());
        }

        private static Map<String, Candidate> indexCandidates(List<Candidate> candidates) {
            Map<String, Candidate> indexed = new ConcurrentHashMap<>();
            candidates.forEach(candidate -> indexed.put(candidate.videoId(), candidate));
            return indexed;
        }
    }
}
