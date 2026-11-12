package com.aiswarya.songtagger.service;

import com.aiswarya.songtagger.model.ApiModels.ExportResponse;
import com.aiswarya.songtagger.model.ApiModels.MetadataUpdate;
import com.aiswarya.songtagger.model.ApiModels.TagEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class MediaService {
    private static final long MAX_ARTWORK_BYTES = 12L * 1024 * 1024;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MediaService(@Value("${songtagger.ffmpeg:ffmpeg}") String ffmpegExecutable,
                        @Value("${songtagger.ffprobe:ffprobe}") String ffprobeExecutable,
                        ObjectMapper objectMapper) {
        this.ffmpegExecutable = ffmpegExecutable;
        this.ffprobeExecutable = ffprobeExecutable;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public Inspection inspect(Path source) {
        JsonNode probe = probe(source);
        double duration = probe.path("format").path("duration").asDouble(-1);
        boolean hasAudio = false;
        for (JsonNode stream : probe.path("streams")) {
            if ("audio".equals(stream.path("codec_type").asText())) {
                hasAudio = true;
                break;
            }
        }
        if (!hasAudio || !Double.isFinite(duration) || duration <= 0) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "The selected file has no readable audio stream.");
        }
        return new Inspection(duration);
    }

    public ExportResponse export(Path source, MetadataUpdate metadata, String coverUrl,
                                 boolean replaceExisting) {
        validateMetadata(metadata);
        Path output = outputPath(source);
        if (output.equals(source)) {
            output = source.resolveSibling(stripExtension(source.getFileName().toString()) + " (tagged).flac");
        }
        if (Files.exists(output) && !replaceExisting) {
            throw new WorkflowException(HttpStatus.CONFLICT,
                    "A FLAC already exists at " + output + ". Enable replace to overwrite it.");
        }

        Path temporaryOutput = output.resolveSibling("." + output.getFileName() + "." + UUID.randomUUID() + ".tmp.flac");
        Path artwork = null;
        try {
            if (coverUrl != null && !coverUrl.isBlank()) {
                artwork = downloadArtwork(coverUrl);
            }

            List<String> command = new ArrayList<>(List.of(
                    ffmpegExecutable, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                    "-i", source.toString()));
            if (artwork != null) {
                command.addAll(List.of("-i", artwork.toString()));
            }
            command.addAll(List.of("-map", "0:a:0", "-map_metadata", "-1"));
            if (artwork != null) {
                command.addAll(List.of("-map", "1:v:0", "-c:v", "copy", "-disposition:v", "attached_pic"));
            } else {
                command.addAll(List.of("-vn"));
            }
            command.addAll(List.of("-c:a", "flac", "-compression_level", "8"));
            addTag(command, "title", metadata.title());
            addTag(command, "artist", metadata.artist());
            addTag(command, "album", metadata.album());
            addTag(command, "album_artist", metadata.albumArtist());
            addTag(command, "composer", metadata.composer());
            addTag(command, "writer", metadata.writer());
            addTag(command, "publisher", metadata.publisher());
            addTag(command, "genre", metadata.genre());
            addTag(command, "date", metadata.date());
            addTag(command, "track", number(metadata.trackNumber()));
            addTag(command, "tracktotal", number(metadata.trackTotal()));
            addTag(command, "disc", number(metadata.discNumber()));
            addTag(command, "disctotal", number(metadata.discTotal()));
            addTag(command, "comment", metadata.comment());
            command.add(temporaryOutput.toString());

            LocalProcessRunner.Result result = LocalProcessRunner.run(command, Duration.ofMinutes(5));
            if (result.exitCode() != 0 || !Files.isRegularFile(temporaryOutput)) {
                throw new WorkflowException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "FFmpeg could not create the FLAC. " + abbreviate(result.stderr()));
            }

            JsonNode outputProbe = probe(temporaryOutput);
            String actualTitle = tag(outputProbe, "title");
            String actualArtist = tag(outputProbe, "artist");
            if (!metadata.title().strip().equals(actualTitle)
                    || !metadata.artist().strip().equals(actualArtist)) {
                throw new WorkflowException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The FLAC was created but its title/artist tags did not verify.");
            }
            boolean artworkEmbedded = hasAttachedArtwork(outputProbe);
            if (artwork != null && !artworkEmbedded) {
                throw new WorkflowException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "The FLAC was created but the cover art could not be embedded.");
            }

            moveIntoPlace(temporaryOutput, output, replaceExisting);
            return new ExportResponse(output.toString(), writtenTags(metadata), artworkEmbedded);
        } catch (IOException exception) {
            throw new WorkflowException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not create or save the FLAC: " + exception.getMessage(), exception);
        } finally {
            deleteQuietly(temporaryOutput);
            if (artwork != null) {
                deleteQuietly(artwork);
            }
        }
    }

    private JsonNode probe(Path path) {
        LocalProcessRunner.Result result = LocalProcessRunner.run(
                List.of(ffprobeExecutable, "-v", "error", "-show_format", "-show_streams", "-of", "json",
                        path.toString()),
                Duration.ofSeconds(30));
        if (result.exitCode() != 0) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "FFprobe could not inspect the file. " + abbreviate(result.stderr()));
        }
        try {
            return objectMapper.readTree(result.stdout());
        } catch (IOException exception) {
            throw new WorkflowException(HttpStatus.BAD_GATEWAY,
                    "FFprobe returned invalid metadata.", exception);
        }
    }

    private Path downloadArtwork(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST, "The artwork URL is invalid.", exception);
        }
        requireArtworkHost(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "SongMetadataStudio/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WorkflowException(HttpStatus.BAD_GATEWAY,
                    "Artwork download was interrupted.", exception);
        }
        try (InputStream body = response.body()) {
            requireArtworkHost(response.uri());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WorkflowException(HttpStatus.BAD_GATEWAY,
                        "Artwork server returned HTTP " + response.statusCode() + ".");
            }
            byte[] bytes = body.readNBytes((int) MAX_ARTWORK_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_ARTWORK_BYTES) {
                throw new WorkflowException(HttpStatus.BAD_GATEWAY,
                        "Artwork is empty or larger than 12 MB.");
            }
            if (ImageIO.read(new java.io.ByteArrayInputStream(bytes)) == null) {
                throw new WorkflowException(HttpStatus.BAD_GATEWAY,
                        "The selected artwork is not a supported image.");
            }
            Path temporaryArtwork = Files.createTempFile("song-metadata-cover-", ".img");
            Files.write(temporaryArtwork, bytes);
            return temporaryArtwork;
        }
    }

    private static void requireArtworkHost(URI uri) {
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com")
                || host.equals("ytimg.com") || host.endsWith(".ytimg.com"))) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "Artwork must come from a YouTube Music image host.");
        }
    }

    private static void validateMetadata(MetadataUpdate metadata) {
        if (metadata == null || clean(metadata.title()).isBlank() || clean(metadata.artist()).isBlank()) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST, "Title and artist are required.");
        }
        for (String value : List.of(
                safe(metadata.title()), safe(metadata.artist()), safe(metadata.album()),
                safe(metadata.albumArtist()), safe(metadata.composer()), safe(metadata.writer()),
                safe(metadata.publisher()), safe(metadata.genre()), safe(metadata.date()),
                safe(metadata.comment()))) {
            if (value.length() > 1000 || value.chars().anyMatch(Character::isISOControl)) {
                throw new WorkflowException(HttpStatus.BAD_REQUEST,
                        "Metadata fields must be under 1000 characters and contain no control characters.");
            }
        }
    }

    private static void addTag(List<String> command, String key, String value) {
        if (value != null && !value.isBlank()) {
            command.add("-metadata");
            command.add(key + "=" + value.strip());
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String number(Integer value) {
        return value == null || value < 1 ? "" : value.toString();
    }

    private static Path outputPath(Path source) {
        return source.resolveSibling(stripExtension(source.getFileName().toString()) + ".flac");
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }

    private static boolean hasAttachedArtwork(JsonNode probe) {
        for (JsonNode stream : probe.path("streams")) {
            if (stream.path("disposition").path("attached_pic").asInt(0) == 1) {
                return true;
            }
        }
        return false;
    }

    private static String tag(JsonNode probe, String key) {
        JsonNode tags = probe.path("format").path("tags");
        JsonNode value = tags.get(key);
        if (value == null) {
            value = tags.get(key.toUpperCase(Locale.ROOT));
        }
        return value == null ? "" : value.asText();
    }

    private static List<TagEntry> writtenTags(MetadataUpdate metadata) {
        List<TagEntry> tags = new ArrayList<>();
        addEntry(tags, "Title", metadata.title());
        addEntry(tags, "Artist", metadata.artist());
        addEntry(tags, "Album", metadata.album());
        addEntry(tags, "Album artist", metadata.albumArtist());
        addEntry(tags, "Composer", metadata.composer());
        addEntry(tags, "Writer", metadata.writer());
        addEntry(tags, "Publisher", metadata.publisher());
        addEntry(tags, "Genre", metadata.genre());
        addEntry(tags, "Date", metadata.date());
        addEntry(tags, "Track", number(metadata.trackNumber()));
        addEntry(tags, "Track total", number(metadata.trackTotal()));
        addEntry(tags, "Disc", number(metadata.discNumber()));
        addEntry(tags, "Disc total", number(metadata.discTotal()));
        addEntry(tags, "Comment", metadata.comment());
        return List.copyOf(tags);
    }

    private static void addEntry(List<TagEntry> tags, String name, String value) {
        if (value != null && !value.isBlank()) {
            tags.add(new TagEntry(name, value.strip()));
        }
    }

    private static void moveIntoPlace(Path temporaryOutput, Path output, boolean replaceExisting)
            throws IOException {
        try {
            if (replaceExisting) {
                Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.move(temporaryOutput, output, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            if (replaceExisting) {
                Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporaryOutput, output);
            }
        }
    }

    private static String abbreviate(String value) {
        String text = value == null ? "" : value.strip();
        return text.length() <= 1500 ? text : text.substring(0, 1500) + "...";
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            path.toFile().deleteOnExit();
        }
    }

    public record Inspection(double durationSeconds) {
    }
}
