package com.aiswarya.songtagger.api;

import com.aiswarya.songtagger.service.OutputDirectoryService;
import com.aiswarya.songtagger.service.WorkflowException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OutputPlaybackControllerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void streamsRequestedFlacByteRangeOnlyForSameOriginLocalPages() throws Exception {
        Path flac = tempDirectory.resolve("Beyoncé - Déjà Vu.flac");
        byte[] content = {0, 1, 2, 3, 4, 5};
        Files.write(flac, content);
        OutputDirectoryService outputDirectoryService = mock(OutputDirectoryService.class);
        when(outputDirectoryService.resolvePlayableFile(
                tempDirectory.toString(), flac.getFileName().toString())).thenReturn(flac);
        OutputPlaybackController controller = new OutputPlaybackController(outputDirectoryService);

        ResponseEntity<?> response = controller.play(
                tempDirectory.toString(), flac.getFileName().toString(), "bytes=2-4", localRequest());
        MockHttpServletResponse output = new MockHttpServletResponse();
        assertInstanceOf(StreamingResponseBody.class, response.getBody()).writeTo(output.getOutputStream());

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("audio/flac", response.getHeaders().getContentType().toString());
        assertEquals("bytes 2-4/6", response.getHeaders().getFirst("Content-Range"));
        assertArrayEquals(new byte[]{2, 3, 4}, output.getContentAsByteArray());
    }

    @Test
    void rejectsCrossSitePlaybackRequests() {
        OutputDirectoryService outputDirectoryService = mock(OutputDirectoryService.class);
        OutputPlaybackController controller = new OutputPlaybackController(outputDirectoryService);
        MockHttpServletRequest request = localRequest("cross-site");

        WorkflowException exception = assertThrows(WorkflowException.class,
                () -> controller.play(tempDirectory.toString(), "track.flac", null, request));

        assertEquals(HttpStatus.FORBIDDEN, exception.status());
        verifyNoInteractions(outputDirectoryService);
    }

    private static MockHttpServletRequest localRequest() {
        return localRequest("same-origin");
    }

    private static MockHttpServletRequest localRequest(String fetchSite) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Sec-Fetch-Site", fetchSite);
        return request;
    }
}
