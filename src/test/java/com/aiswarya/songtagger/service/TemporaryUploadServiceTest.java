package com.aiswarya.songtagger.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemporaryUploadServiceTest {
    @TempDir
    Path tempDirectory;

    @Test
    void storesAudioWithSanitizedNameAndPreservesUnicode() throws IOException {
        byte[] content = {5, 6, 7};
        TemporaryUploadService service = new TemporaryUploadService(tempDirectory.resolve("uploads"));
        try {
            var stored = service.store(new MockMultipartFile(
                    "file", "..\\outside\\Beyoncé - Déjà Vu.webm", "audio/webm", content));

            Path path = Path.of(stored.path());
            assertEquals("Beyoncé - Déjà Vu.webm", stored.filename());
            assertEquals("Beyoncé - Déjà Vu.webm", path.getFileName().toString());
            assertArrayEquals(content, Files.readAllBytes(path));
        } finally {
            service.close();
        }
        assertFalse(Files.exists(tempDirectory.resolve("uploads")));
    }

    @Test
    void rejectsUnsupportedExtensions() {
        TemporaryUploadService service = new TemporaryUploadService(tempDirectory.resolve("uploads"));
        try {
            WorkflowException exception = assertThrows(WorkflowException.class,
                    () -> service.store(new MockMultipartFile(
                            "file", "malware.exe", "application/octet-stream", new byte[]{1})));

            assertEquals(415, exception.status().value());
        } finally {
            service.close();
        }
    }

    @Test
    void rejectsFilesAboveTheConfiguredSizeLimit() {
        MultipartFile oversized = mock(MultipartFile.class);
        when(oversized.isEmpty()).thenReturn(false);
        when(oversized.getSize()).thenReturn(TemporaryUploadService.MAX_FILE_BYTES + 1);
        when(oversized.getOriginalFilename()).thenReturn("large.webm");
        TemporaryUploadService service = new TemporaryUploadService(tempDirectory.resolve("uploads"));
        try {
            WorkflowException exception = assertThrows(WorkflowException.class,
                    () -> service.store(oversized));

            assertEquals(413, exception.status().value());
        } finally {
            service.close();
        }
    }

    @Test
    void keepsCreatedFlacOutputWhenUploadSourcesAreCleanedUp() throws IOException {
        TemporaryUploadService service = new TemporaryUploadService(tempDirectory.resolve("uploads"));
        Path output;
        try {
            var stored = service.store(new MockMultipartFile(
                    "file", "track.webm", "audio/webm", new byte[]{1, 2, 3}));
            output = Path.of(stored.path()).resolveSibling("track.flac");
            Files.write(output, new byte[]{4, 5, 6});
        } finally {
            service.close();
        }

        assertFalse(Files.exists(tempDirectory.resolve("uploads").resolve("track.webm")));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(output));
    }
}
