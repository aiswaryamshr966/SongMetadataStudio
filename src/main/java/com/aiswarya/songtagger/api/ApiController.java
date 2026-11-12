package com.aiswarya.songtagger.api;

import com.aiswarya.songtagger.model.ApiModels.ApiError;
import com.aiswarya.songtagger.model.ApiModels.DetailsRequest;
import com.aiswarya.songtagger.model.ApiModels.ExportRequest;
import com.aiswarya.songtagger.model.ApiModels.ExportResponse;
import com.aiswarya.songtagger.model.ApiModels.HealthResponse;
import com.aiswarya.songtagger.model.ApiModels.MetadataSuggestion;
import com.aiswarya.songtagger.model.ApiModels.SearchRequest;
import com.aiswarya.songtagger.model.ApiModels.SearchResponse;
import com.aiswarya.songtagger.service.MediaService;
import com.aiswarya.songtagger.service.LocalProcessRunner;
import com.aiswarya.songtagger.service.SongWorkflowService;
import com.aiswarya.songtagger.service.YouTubeMusicBridge;
import com.aiswarya.songtagger.service.WorkflowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SongWorkflowService workflowService;
    private final YouTubeMusicBridge bridge;
    private final MediaService mediaService;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;

    public ApiController(SongWorkflowService workflowService, YouTubeMusicBridge bridge,
                         MediaService mediaService,
                         @Value("${songtagger.ffmpeg:ffmpeg}") String ffmpegExecutable,
                         @Value("${songtagger.ffprobe:ffprobe}") String ffprobeExecutable) {
        this.workflowService = workflowService;
        this.bridge = bridge;
        this.mediaService = mediaService;
        this.ffmpegExecutable = ffmpegExecutable;
        this.ffprobeExecutable = ffprobeExecutable;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        boolean mediaAvailable = commandAvailable(ffmpegExecutable) && commandAvailable(ffprobeExecutable);
        boolean ytmusicAvailable = bridge.isAvailable();
        String message = mediaAvailable && ytmusicAvailable
                ? "Ready"
                : "Check the setup instructions for missing local dependencies.";
        return new HealthResponse("ok", mediaAvailable, ytmusicAvailable, message);
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        return workflowService.search(request.path());
    }

    @PostMapping("/details")
    public MetadataSuggestion details(@RequestBody DetailsRequest request) {
        return workflowService.details(request.searchId(), request.videoId());
    }

    @PostMapping("/export")
    public ExportResponse export(@RequestBody ExportRequest request) {
        return workflowService.export(request.searchId(), request.videoId(), request.metadata(),
                request.replaceExisting());
    }

    private static boolean commandAvailable(String command) {
        try {
            return LocalProcessRunner.run(
                    java.util.List.of(command, "-version"),
                    java.time.Duration.ofSeconds(3)).exitCode() == 0;
        } catch (WorkflowException exception) {
            return false;
        }
    }
}

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(WorkflowException.class)
    ResponseEntity<ApiError> handleWorkflow(WorkflowException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
                .error("Unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("The operation failed unexpectedly. See the app console for details."));
    }
}
