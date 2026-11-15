package com.aiswarya.songtagger.api;

import com.aiswarya.songtagger.model.ApiModels.ApiError;
import com.aiswarya.songtagger.model.ApiModels.DetailsRequest;
import com.aiswarya.songtagger.model.ApiModels.ExportRequest;
import com.aiswarya.songtagger.model.ApiModels.ExportResponse;
import com.aiswarya.songtagger.model.ApiModels.HealthResponse;
import com.aiswarya.songtagger.model.ApiModels.MetadataSuggestion;
import com.aiswarya.songtagger.model.ApiModels.OutputDirectoryRequest;
import com.aiswarya.songtagger.model.ApiModels.OutputDirectoryResponse;
import com.aiswarya.songtagger.model.ApiModels.SearchRequest;
import com.aiswarya.songtagger.model.ApiModels.SearchResponse;
import com.aiswarya.songtagger.model.ApiModels.UploadResponse;
import com.aiswarya.songtagger.service.LocalProcessRunner;
import com.aiswarya.songtagger.service.OutputDirectoryService;
import com.aiswarya.songtagger.service.SongWorkflowService;
import com.aiswarya.songtagger.service.TemporaryUploadService;
import com.aiswarya.songtagger.service.YouTubeMusicBridge;
import com.aiswarya.songtagger.service.WorkflowException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final SongWorkflowService workflowService;
    private final YouTubeMusicBridge bridge;
    private final TemporaryUploadService temporaryUploadService;
    private final OutputDirectoryService outputDirectoryService;
    private final String ffmpegExecutable;
    private final String ffprobeExecutable;

    public ApiController(SongWorkflowService workflowService, YouTubeMusicBridge bridge,
                         TemporaryUploadService temporaryUploadService,
                         OutputDirectoryService outputDirectoryService,
                         @Value("${songtagger.ffmpeg:ffmpeg}") String ffmpegExecutable,
                         @Value("${songtagger.ffprobe:ffprobe}") String ffprobeExecutable) {
        this.workflowService = workflowService;
        this.bridge = bridge;
        this.temporaryUploadService = temporaryUploadService;
        this.outputDirectoryService = outputDirectoryService;
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

    @GetMapping("/output-directory")
    public OutputDirectoryResponse outputDirectory() {
        return new OutputDirectoryResponse(outputDirectoryService.resolve(null).toString());
    }

    @PostMapping("/output-directory/open")
    public OutputDirectoryResponse openOutputDirectory(@RequestBody OutputDirectoryRequest request) {
        return new OutputDirectoryResponse(outputDirectoryService.open(request.path()).toString());
    }

    @PostMapping("/search")
    public SearchResponse search(@RequestBody SearchRequest request) {
        return workflowService.search(request.path());
    }

    @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResponse upload(@RequestParam("file") MultipartFile file) {
        return temporaryUploadService.store(file);
    }

    @PostMapping("/details")
    public MetadataSuggestion details(@RequestBody DetailsRequest request) {
        return workflowService.details(request.searchId(), request.videoId());
    }

    @PostMapping("/export")
    public ExportResponse export(@RequestBody ExportRequest request) {
        return workflowService.export(request.searchId(), request.videoId(), request.metadata(),
                request.replaceExisting(), request.outputDirectory());
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

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> handleMaxUploadSize(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("Each uploaded audio file must be 512 MiB or smaller."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
                .error("Unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("The operation failed unexpectedly. See the app console for details."));
    }
}
