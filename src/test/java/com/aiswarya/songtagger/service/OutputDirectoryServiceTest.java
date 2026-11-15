package com.aiswarya.songtagger.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutputDirectoryServiceTest {
    @TempDir
    private Path tempDir;

    @Test
    void defaultsToMusicOutputFolderUnderUserProfile() {
        OutputDirectoryService service = new OutputDirectoryService("", tempDir.toString());

        assertEquals(tempDir.resolve("Music").resolve("SongMetadataStudio").resolve("Output"),
                service.defaultDirectory());
    }

    @Test
    void createsAndResolvesExplicitUnicodeDirectory() {
        Path requested = tempDir.resolve("Música").resolve("Song Metadata Output");
        OutputDirectoryService service = new OutputDirectoryService("", tempDir.toString());

        Path resolved = service.resolve(requested.toString());

        assertEquals(requested, resolved);
        assertTrue(Files.isDirectory(resolved));
    }

    @Test
    void persistsPlaybackFoldersAndRejectsTraversalOutsideThem() throws Exception {
        Path defaultDirectory = tempDir.resolve("default");
        Path customDirectory = tempDir.resolve("custom");
        Path index = tempDir.resolve("state").resolve("playback-directories.txt");
        Files.createDirectories(customDirectory);
        Path flac = customDirectory.resolve("Beyoncé - Déjà Vu.flac");
        Files.write(flac, new byte[]{1, 2, 3});

        OutputDirectoryService first = new OutputDirectoryService(
                defaultDirectory.toString(), tempDir.toString(), index);
        first.registerPlaybackDirectory(customDirectory);
        OutputDirectoryService restarted = new OutputDirectoryService(
                defaultDirectory.toString(), tempDir.toString(), index);

        assertEquals(flac.toRealPath(), restarted.resolvePlayableFile(
                customDirectory.toString(), flac.getFileName().toString()));
        WorkflowException traversal = assertThrows(WorkflowException.class,
                () -> restarted.resolvePlayableFile(customDirectory.toString(), "..\\outside.flac"));
        assertEquals(404, traversal.status().value());
        WorkflowException unregistered = assertThrows(WorkflowException.class,
                () -> restarted.resolvePlayableFile(tempDir.toString(), flac.getFileName().toString()));
        assertEquals(404, unregistered.status().value());
    }
}
