package com.aiswarya.songtagger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MediaServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path tempDir;

    @Test
    void extractsNormalizedMetadataFromAudioAndFormatTags() throws Exception {
        var probe = objectMapper.readTree("""
                {
                  "streams": [
                    {"codec_type": "video", "tags": {"title": "Video title", "VIDEO_ONLY": "No"}},
                    {"codec_type": "audio", "tags": {"TITLE": "Stream title", "COMPOSER": "Composer"}}
                  ],
                  "format": {"tags": {"title": "Format title", "ALBUM ARTIST": "Album artist"}}
                }
                """);

        var tags = MediaService.extractMetadataTags(probe);

        assertEquals("Format title", tags.get("title"));
        assertEquals("Composer", tags.get("composer"));
        assertEquals("Album artist", tags.get("albumartist"));
        assertFalse(tags.containsKey("videoonly"));
    }

    @Test
    void verifiesUnicodeTitleAndArtistFromAudioStreamTags() throws Exception {
        var probe = objectMapper.readTree("""
                {
                  "streams": [
                    {"codec_type": "audio", "tags": {"TITLE": "मेरा हुआ", "ARTIST": "अनमोल साल्ह"}}
                  ],
                  "format": {"format_name": "flac"}
                }
                """);

        assertEquals("मेरा हुआ", MediaService.tag(probe, "title"));
        assertEquals("अनमोल साल्ह", MediaService.tag(probe, "artist"));
    }

    @Test
    void choosesCollisionSafeNamesUnlessReplacementIsExplicit() throws Exception {
        Path source = tempDir.resolve("Artist - Track.webm");
        Path outputDirectory = Files.createDirectory(tempDir.resolve("Output"));
        Files.writeString(outputDirectory.resolve("Artist - Track.flac"), "existing");

        assertEquals(outputDirectory.resolve("Artist - Track (2).flac"),
                MediaService.outputPath(source, outputDirectory, false));
        assertEquals(outputDirectory.resolve("Artist - Track.flac"),
                MediaService.outputPath(source, outputDirectory, true));

        Files.writeString(outputDirectory.resolve("Artist - Track (2).flac"), "existing");
        assertEquals(outputDirectory.resolve("Artist - Track (3).flac"),
                MediaService.outputPath(source, outputDirectory, false));
    }

    @Test
    void doesNotSelectTheSourceWhenTaggingAnExistingFlacInOutputFolder() {
        Path source = tempDir.resolve("already.flac");

        assertEquals(tempDir.resolve("already (tagged).flac"),
                MediaService.outputPath(source, tempDir, false));
    }

    @Test
    void exportsWhenWindowsPathsAndMetadataContainSpacesAndParentheses() throws Exception {
        assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"),
                "FFmpeg and FFprobe are required for this process integration test.");

        String baseName = "Anirudh Ravichander - Tiger Muthuvel Pandian (From _Jailer 2_)";
        Path source = tempDir.resolve(baseName + ".wav");
        Path outputDirectory = Files.createDirectory(tempDir.resolve("Music Library (2)"));
        Path existingOutput = outputDirectory.resolve(baseName + ".flac");
        Files.writeString(existingOutput, "existing output");
        LocalProcessRunner.Result sourceResult = LocalProcessRunner.run(List.of(
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100",
                "-t", "0.1", "-c:a", "pcm_s16le", source.toString()),
                Duration.ofSeconds(30));
        assertEquals(0, sourceResult.exitCode(), sourceResult.stderr());

        var metadata = new com.aiswarya.songtagger.model.ApiModels.MetadataUpdate(
                "Tiger Muthuvel Pandian (From \"Jailer 2\")", "Anirudh Ravichander",
                "Jailer 2: Original Soundtrack", "Anirudh Ravichander",
                "", "", "", "Soundtrack", "2026", 2, 2, 1, 1,
                "Version \"2)\" — ஜெயிலர், part\\two's");
        MediaService service = new MediaService("ffmpeg", "ffprobe", objectMapper);

        var response = service.export(source, metadata, null, false, outputDirectory);

        Path created = Path.of(response.outputPath());
        assertEquals(outputDirectory.resolve(baseName + " (2).flac"), created);
        assertTrue(Files.isRegularFile(created));
        assertEquals("existing output", Files.readString(existingOutput, StandardCharsets.UTF_8));
        var tags = service.inspect(created).tags();
        assertEquals(metadata.title(), tags.get("title"));
        assertEquals(metadata.artist(), tags.get("artist"));
        assertEquals(metadata.comment(), tags.get("comment"));
    }

    @Test
    void failedFfmpegExportKeepsSourceAndRemovesPartialOutput() throws Exception {
        assumeTrue(commandAvailable("ffmpeg") && commandAvailable("ffprobe"),
                "FFmpeg and FFprobe are required for this process integration test.");

        Path source = tempDir.resolve("Track with spaces (2).wav");
        byte[] sourceBytes = "not valid audio".getBytes(StandardCharsets.UTF_8);
        Files.write(source, sourceBytes);
        Path outputDirectory = Files.createDirectory(tempDir.resolve("Failed Output (2)"));
        var metadata = new com.aiswarya.songtagger.model.ApiModels.MetadataUpdate(
                "Track (2)", "Artist", "", "", "", "", "", "", "", null, null,
                null, null, "");
        MediaService service = new MediaService("ffmpeg", "ffprobe", objectMapper);

        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> service.export(source, metadata, null, false, outputDirectory));

        assertTrue(exception.getMessage().contains(outputDirectory.resolve("Track with spaces (2).flac")
                .toString()));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        try (var files = Files.list(outputDirectory)) {
            assertEquals(List.of(), files.toList());
        }
    }

    private static boolean commandAvailable(String command) {
        try {
            LocalProcessRunner.Result result = LocalProcessRunner.run(
                    List.of(command, "-version"), Duration.ofSeconds(5));
            return result.exitCode() == 0;
        } catch (RuntimeException exception) {
            return false;
        }
    }

}
