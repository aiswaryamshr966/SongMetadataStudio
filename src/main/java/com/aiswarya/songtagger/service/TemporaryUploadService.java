package com.aiswarya.songtagger.service;

import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aiswarya.songtagger.model.ApiModels.UploadResponse;

@Service
public class TemporaryUploadService {
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    private static final Duration UPLOAD_TTL = Duration.ofHours(4);
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "3gp", "aac", "ac3", "aif", "aiff", "alac", "amr", "ape", "dff", "dsf",
            "eac3", "flac", "m4a", "mka", "mkv", "mov", "mp2", "mp3", "mp4", "oga",
            "ogg", "opus", "wav", "webm", "wma");

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(TemporaryUploadService.class);

    private final Path root;
    private final Map<Path, Instant> uploadedFiles = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "song-metadata-upload-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    public TemporaryUploadService() {
        this(createRoot());
    }

    TemporaryUploadService(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not create temporary upload storage.", exception);
        }
        cleanupExecutor.scheduleWithFixedDelay(
                this::deleteExpiredUploads, 15, 15, TimeUnit.MINUTES);
    }

    public UploadResponse store(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST, "The selected file is empty.");
        }
        if (upload.getSize() > MAX_FILE_BYTES) {
            throw new WorkflowException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Each uploaded audio file must be 512 MiB or smaller.");
        }

        String originalName = upload.getOriginalFilename();
        String safeName = safeFilename(originalName);
        String extension = extension(safeName);
        if (!SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new WorkflowException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported audio file type. Choose a supported audio file.");
        }

        Path directory = root.resolve(UUID.randomUUID().toString());
        Path target = directory.resolve(safeName).normalize();
        if (!target.startsWith(directory)) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST, "The selected filename is invalid.");
        }

        try {
            Files.createDirectory(directory);
            try (InputStream input = upload.getInputStream()) {
                Files.copy(input, target);
            }
            if (Files.size(target) > MAX_FILE_BYTES) {
                throw new WorkflowException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "Each uploaded audio file must be 512 MiB or smaller.");
            }
            uploadedFiles.put(target, Instant.now());
            return new UploadResponse(target.toString(), safeName);
        } catch (WorkflowException exception) {
            deleteDirectory(directory);
            throw exception;
        } catch (IOException exception) {
            deleteDirectory(directory);
            throw new WorkflowException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The selected audio file could not be stored temporarily.", exception);
        }
    }

    static String safeFilename(String originalName) {
        String portable = originalName == null ? "" : originalName.replace('\\', '/');
        String basename = portable.substring(portable.lastIndexOf('/') + 1);
        String safe = Normalizer.normalize(basename, Normalizer.Form.NFC)
                .replaceAll("[<>:\"/\\\\|?*\\p{Cntrl}]", "_")
                .replaceAll("[. ]+$", "")
                .strip();
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            safe = "audio";
        }
        if (safe.matches("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$")) {
            safe = "_" + safe;
        }
        if (safe.length() > 180) {
            String extension = extension(safe);
            String suffix = extension.isBlank() ? "" : "." + extension;
            safe = safe.substring(0, Math.max(1, 180 - suffix.length())) + suffix;
        }
        return safe;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1
                ? ""
                : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteExpiredUploads() {
        Instant cutoff = Instant.now().minus(UPLOAD_TTL);
        uploadedFiles.forEach((file, uploadedAt) -> {
            if (!uploadedAt.isAfter(cutoff) && deleteUploadedFile(file)) {
                uploadedFiles.remove(file, uploadedAt);
            }
        });
    }

    private boolean deleteUploadedFile(Path file) {
        try {
            Files.deleteIfExists(file);
            try {
                Files.deleteIfExists(file.getParent());
            } catch (java.nio.file.DirectoryNotEmptyException ignored) {
                // Keep generated FLAC copies beside their temporary source files.
            }
            return true;
        } catch (IOException exception) {
            logger.warn("Could not remove temporary upload {}", file, exception);
            return false;
        }
    }

    private boolean deleteDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return true;
        }
        try (var files = Files.walk(directory)) {
            ArrayList<Path> ordered = new ArrayList<>(files.toList());
            ordered.sort(Comparator.reverseOrder());
            for (Path path : ordered) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException exception) {
            logger.warn("Could not remove temporary upload directory {}", directory, exception);
            return false;
        }
    }

    @PreDestroy
    public void close() {
        cleanupExecutor.shutdownNow();
        uploadedFiles.keySet().forEach(this::deleteUploadedFile);
        deleteDirectoryIfEmpty(root);
    }

    private void deleteDirectoryIfEmpty(Path directory) {
        try {
            Files.deleteIfExists(directory);
        } catch (java.nio.file.DirectoryNotEmptyException ignored) {
            // Generated FLAC output is user data and must outlive upload cleanup.
        } catch (IOException exception) {
            logger.warn("Could not remove empty temporary upload root {}", directory, exception);
        }
    }

    private static Path createRoot() {
        try {
            return Files.createTempDirectory("song-metadata-studio-uploads-");
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not initialize temporary upload storage.", exception);
        }
    }
}
