package com.aiswarya.songtagger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
}
