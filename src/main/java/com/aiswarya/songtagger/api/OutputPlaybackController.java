package com.aiswarya.songtagger.api;

import com.aiswarya.songtagger.service.OutputDirectoryService;
import com.aiswarya.songtagger.service.WorkflowException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/output")
public class OutputPlaybackController {
    private static final MediaType AUDIO_FLAC = MediaType.parseMediaType("audio/flac");
    private static final String INVALID_RANGE = "That FLAC does not support the requested byte range.";

    private final OutputDirectoryService outputDirectoryService;

    public OutputPlaybackController(OutputDirectoryService outputDirectoryService) {
        this.outputDirectoryService = outputDirectoryService;
    }

    @GetMapping("/play")
    public ResponseEntity<StreamingResponseBody> play(
            @RequestParam String directory,
            @RequestParam String name,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
            HttpServletRequest request) {
        if (!isLocalSameOriginRequest(request)) {
            throw new WorkflowException(HttpStatus.FORBIDDEN,
                    "Audio playback is only available to this local app page.");
        }

        Path file = outputDirectoryService.resolvePlayableFile(directory, name);
        try {
            long fileSize = Files.size(file);
            ByteRange range = rangeHeader == null ? new ByteRange(0, fileSize == 0 ? -1 : fileSize - 1)
                    : parseRange(rangeHeader, fileSize);
            if (range == null) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize)
                        .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                        .build();
            }

            long contentLength = range.end() < range.start()
                    ? 0
                    : range.end() - range.start() + 1;
            boolean partial = rangeHeader != null;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(AUDIO_FLAC);
            headers.setContentLength(contentLength);
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.setCacheControl(CacheControl.noStore());
            headers.set("X-Content-Type-Options", "nosniff");
            headers.setContentDisposition(ContentDisposition.inline()
                    .filename(name, StandardCharsets.UTF_8).build());
            if (partial) {
                headers.set(HttpHeaders.CONTENT_RANGE,
                        "bytes " + range.start() + "-" + range.end() + "/" + fileSize);
            }
            StreamingResponseBody body = output -> writeFileRange(
                    file, output, range.start(), contentLength);
            return ResponseEntity.status(partial ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK)
                    .headers(headers)
                    .body(body);
        } catch (IOException exception) {
            throw new WorkflowException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "The selected FLAC could not be read for playback.", exception);
        }
    }

    private static ByteRange parseRange(String header, long fileSize) {
        if (!header.startsWith("bytes=") || header.indexOf(',') >= 0 || fileSize <= 0) {
            return null;
        }
        String[] parts = header.substring("bytes=".length()).split("-", -1);
        if (parts.length != 2) {
            return null;
        }
        try {
            long start;
            long end;
            if (parts[0].isBlank()) {
                long suffixLength = Long.parseLong(parts[1]);
                if (suffixLength <= 0) {
                    return null;
                }
                start = Math.max(0, fileSize - suffixLength);
                end = fileSize - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isBlank() ? fileSize - 1 : Long.parseLong(parts[1]);
            }
            if (start < 0 || start >= fileSize || end < start) {
                return null;
            }
            return new ByteRange(start, Math.min(end, fileSize - 1));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static void writeFileRange(Path file, OutputStream output, long start, long length)
            throws IOException {
        try (var input = Files.newInputStream(file)) {
            input.skipNBytes(start);
            byte[] buffer = new byte[16 * 1024];
            long remaining = length;
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("The FLAC changed while it was being streamed.");
                }
                output.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static boolean isLocalSameOriginRequest(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        if (remoteAddress == null || fetchSite == null || !"same-origin".equals(fetchSite)) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddress).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private record ByteRange(long start, long end) {
    }
}
