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
    private final ObjectMapper objectMapper;
    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();

    public SongWorkflowService(YouTubeMusicBridge bridge, MediaService mediaService,
                              ObjectMapper objectMapper) {
        this.bridge = bridge;
        this.mediaService = mediaService;
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
        SearchSession session = new SearchSession(source, inspection.durationSeconds(), query, candidates);
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
        session.details().put(videoId, suggestion);
        return suggestion;
    }

    public ExportResponse export(String searchId, String videoId, MetadataUpdate metadata,
                                 boolean replaceExisting) {
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
        return mediaService.export(session.source(), metadata, selection.coverUrl(), replaceExisting);
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
                                 Instant createdAt) {
        private SearchSession(Path source, double durationSeconds, String query,
                              List<Candidate> candidates) {
            this(source, durationSeconds, query, indexCandidates(candidates),
                    new ConcurrentHashMap<>(), Instant.now());
        }

        private static Map<String, Candidate> indexCandidates(List<Candidate> candidates) {
            Map<String, Candidate> indexed = new ConcurrentHashMap<>();
            candidates.forEach(candidate -> indexed.put(candidate.videoId(), candidate));
            return indexed;
        }
    }
}
