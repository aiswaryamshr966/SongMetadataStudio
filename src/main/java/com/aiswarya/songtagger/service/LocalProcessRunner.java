package com.aiswarya.songtagger.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class LocalProcessRunner {
    private LocalProcessRunner() {
    }

    public static Result run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException exception) {
            throw new WorkflowException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not start " + command.getFirst() + ". Check that it is installed and on PATH.",
                    exception);
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> stdout = executor.submit(() -> read(process.getInputStream()));
            Future<String> stderr = executor.submit(() -> read(process.getErrorStream()));
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new WorkflowException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "The media command was interrupted.",
                        exception);
            }

            if (!finished) {
                process.destroyForcibly();
                throw new WorkflowException(
                        org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                        command.getFirst() + " did not finish within " + timeout.toSeconds() + " seconds.");
            }

            try {
                return new Result(process.exitValue(), stdout.get(), stderr.get());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new WorkflowException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not collect the media command output.",
                        exception);
            } catch (ExecutionException exception) {
                throw new WorkflowException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not read the media command output.",
                        exception.getCause());
            }
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record Result(int exitCode, String stdout, String stderr) {
    }
}
