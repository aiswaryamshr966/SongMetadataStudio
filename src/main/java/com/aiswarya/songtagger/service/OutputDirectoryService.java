package com.aiswarya.songtagger.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OutputDirectoryService {
    private final Path defaultDirectory;
    private final Path playbackDirectoryIndex;
    private final Set<Path> playbackDirectories = ConcurrentHashMap.newKeySet();

    @Autowired
    public OutputDirectoryService(
            @Value("${songtagger.output-directory:}") String configuredDirectory,
            @Value("${songtagger.state-directory:}") String configuredStateDirectory,
            @Value("${USERPROFILE:}") String userProfile) {
        this(configuredDirectory, userProfile,
                playbackIndexPath(configuredStateDirectory));
    }

    OutputDirectoryService(String configuredDirectory, String userProfile) {
        this(configuredDirectory, userProfile, (Path) null);
    }

    OutputDirectoryService(String configuredDirectory, String userProfile, Path playbackDirectoryIndex) {
        String home = userProfile == null || userProfile.isBlank()
                ? System.getProperty("user.home")
                : userProfile;
        try {
            Path configured = configuredDirectory == null || configuredDirectory.isBlank()
                    ? Path.of(home, "Music", "SongMetadataStudio", "Output")
                    : Path.of(configuredDirectory.strip());
            defaultDirectory = configured.toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "The configured output directory path is invalid.", exception);
        }
        this.playbackDirectoryIndex = playbackDirectoryIndex == null
                ? null
                : playbackDirectoryIndex.toAbsolutePath().normalize();
        playbackDirectories.add(defaultDirectory);
        loadPlaybackDirectories();
    }

    public Path defaultDirectory() {
        return defaultDirectory;
    }

    public Path resolve(String requestedDirectory) {
        Path directory;
        try {
            directory = requestedDirectory == null || requestedDirectory.isBlank()
                    ? defaultDirectory
                    : Path.of(requestedDirectory.strip()).toAbsolutePath().normalize();
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory)) {
                throw new WorkflowException(HttpStatus.BAD_REQUEST,
                        "The output path is not a directory: " + directory);
            }
            return directory;
        } catch (InvalidPathException exception) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "The output directory path is invalid.", exception);
        } catch (IOException exception) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "Could not create the output directory: " + exception.getMessage(), exception);
        }
    }

    public synchronized void registerPlaybackDirectory(Path directory) {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            throw new WorkflowException(HttpStatus.BAD_REQUEST,
                    "The output directory is not available for playback: " + normalized);
        }
        if (!playbackDirectories.add(normalized) || playbackDirectoryIndex == null) {
            return;
        }

        Path parent = playbackDirectoryIndex.getParent();
        Path temporaryIndex = playbackDirectoryIndex.resolveSibling(
                playbackDirectoryIndex.getFileName() + ".new");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String contents = playbackDirectories.stream()
                    .map(Path::toString)
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(System.lineSeparator()));
            Files.writeString(temporaryIndex, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporaryIndex, playbackDirectoryIndex,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporaryIndex, playbackDirectoryIndex,
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            playbackDirectories.remove(normalized);
            throw new WorkflowException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not save the configured playback folder.", exception);
        } finally {
            try {
                Files.deleteIfExists(temporaryIndex);
            } catch (IOException exception) {
                org.slf4j.LoggerFactory.getLogger(OutputDirectoryService.class)
                        .warn("Could not remove temporary playback directory index {}",
                                temporaryIndex, exception);
            }
        }
    }

    public Path resolvePlayableFile(String requestedDirectory, String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/")
                || filename.contains("\\") || !filename.toLowerCase(Locale.ROOT).endsWith(".flac")) {
            throw notFoundForPlayback();
        }
        try {
            Path directory = requestedDirectory == null || requestedDirectory.isBlank()
                    ? defaultDirectory
                    : Path.of(requestedDirectory.strip()).toAbsolutePath().normalize();
            if (!playbackDirectories.contains(directory)) {
                throw notFoundForPlayback();
            }
            Path candidate = directory.resolve(filename).normalize();
            if (!directory.equals(candidate.getParent()) || !Files.isRegularFile(candidate)
                    || !Files.isReadable(candidate)) {
                throw notFoundForPlayback();
            }
            Path realDirectory = directory.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (!realCandidate.startsWith(realDirectory)
                    || !realDirectory.equals(realCandidate.getParent())) {
                throw notFoundForPlayback();
            }
            return realCandidate;
        } catch (InvalidPathException | IOException exception) {
            throw notFoundForPlayback();
        }
    }

    public Path open(String requestedDirectory) {
        Path directory = resolve(requestedDirectory);
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
                new ProcessBuilder("explorer.exe", directory.toString()).start();
            } else if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(directory.toFile());
            } else {
                throw new WorkflowException(HttpStatus.SERVICE_UNAVAILABLE,
                        "The system cannot open folders from this app. Open this path manually: " + directory);
            }
            return directory;
        } catch (IOException | UnsupportedOperationException exception) {
            throw new WorkflowException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not open the output folder. Open this path manually: " + directory, exception);
        }
    }

    private void loadPlaybackDirectories() {
        if (playbackDirectoryIndex == null || !Files.isRegularFile(playbackDirectoryIndex)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(playbackDirectoryIndex, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    playbackDirectories.add(Path.of(line).toAbsolutePath().normalize());
                }
            }
        } catch (IOException | InvalidPathException exception) {
            org.slf4j.LoggerFactory.getLogger(OutputDirectoryService.class)
                    .warn("Could not load the saved playback folder registry {}",
                            playbackDirectoryIndex, exception);
        }
    }

    private static Path playbackIndexPath(String configuredStateDirectory) {
        String stateDirectory = configuredStateDirectory;
        if (stateDirectory == null || stateDirectory.isBlank()) {
            stateDirectory = System.getenv("LOCALAPPDATA");
        }
        if (stateDirectory == null || stateDirectory.isBlank()) {
            stateDirectory = Path.of(System.getProperty("user.home"), ".song-metadata-studio")
                    .toString();
        } else {
            stateDirectory = Path.of(stateDirectory, "SongMetadataStudio").toString();
        }
        return Path.of(stateDirectory, "playback-directories.txt");
    }

    private static WorkflowException notFoundForPlayback() {
        return new WorkflowException(HttpStatus.NOT_FOUND,
                "That FLAC is not available in a configured output folder.");
    }
}
