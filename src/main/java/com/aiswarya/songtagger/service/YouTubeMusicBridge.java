package com.aiswarya.songtagger.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class YouTubeMusicBridge {
    private final ObjectMapper objectMapper;
    private final String pythonExecutable;
    private Path scriptPath;

    public YouTubeMusicBridge(ObjectMapper objectMapper,
                              @Value("${songtagger.python:python}") String pythonExecutable) {
        this.objectMapper = objectMapper;
        this.pythonExecutable = pythonExecutable;
    }

    @PostConstruct
    void initialize() {
        try {
            scriptPath = Files.createTempFile("song-metadata-ytmusic-", ".py");
            try (InputStream input = new ClassPathResource("ytmusic_bridge.py").getInputStream()) {
                Files.copy(input, scriptPath, StandardCopyOption.REPLACE_EXISTING);
            }
            scriptPath.toFile().deleteOnExit();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare the YouTube Music helper.", exception);
        }
    }

    public JsonNode call(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(pythonExecutable);
        command.add(scriptPath.toString());
        command.addAll(List.of(arguments));

        LocalProcessRunner.Result result = LocalProcessRunner.run(command, Duration.ofSeconds(60));
        JsonNode response;
        try {
            response = objectMapper.readTree(result.stdout());
        } catch (IOException exception) {
            String detail = result.stderr().isBlank() ? result.stdout().strip() : result.stderr().strip();
            throw new WorkflowException(
                    HttpStatus.BAD_GATEWAY,
                    "YouTube Music returned an unreadable response. " + detail,
                    exception);
        }

        if (result.exitCode() != 0 || response.hasNonNull("error")) {
            String detail = response.path("error").asText(result.stderr().strip());
            if (detail.isBlank()) {
                detail = "YouTube Music lookup failed.";
            }
            throw new WorkflowException(HttpStatus.BAD_GATEWAY, detail);
        }
        return response;
    }

    public boolean isAvailable() {
        try {
            JsonNode result = call("health");
            return result.path("available").asBoolean(false);
        } catch (WorkflowException exception) {
            return false;
        }
    }

    @PreDestroy
    void cleanup() {
        if (scriptPath != null) {
            try {
                Files.deleteIfExists(scriptPath);
            } catch (IOException ignored) {
                scriptPath.toFile().deleteOnExit();
            }
        }
    }
}
