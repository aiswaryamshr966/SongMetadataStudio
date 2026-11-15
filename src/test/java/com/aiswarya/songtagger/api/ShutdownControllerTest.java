package com.aiswarya.songtagger.api;

import com.aiswarya.songtagger.model.ApiModels.ShutdownResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.timeout;

class ShutdownControllerTest {
    private final ConfigurableApplicationContext applicationContext =
            mock(ConfigurableApplicationContext.class);
    private final ShutdownController controller = new ShutdownController(applicationContext);

    @Test
    void rejectsRequestsFromNonLoopbackClients() {
        MockHttpServletRequest request = localRequest();
        request.setRemoteAddr("192.0.2.10");

        ResponseEntity<?> response = controller.shutdown(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(applicationContext);
    }

    @Test
    void rejectsRequestsFromAnotherOriginEvenWhenClientIsLocal() {
        MockHttpServletRequest request = localRequest("https://attacker.example");

        ResponseEntity<?> response = controller.shutdown(request);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        verifyNoInteractions(applicationContext);
    }

    @Test
    void closesTheApplicationAfterAcceptingSameOriginLoopbackRequest() {
        ResponseEntity<?> response = controller.shutdown(localRequest());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        ShutdownResponse body = assertInstanceOf(ShutdownResponse.class, response.getBody());
        assertEquals("The local server is shutting down. Saved FLAC copies are kept.", body.message());
        verify(applicationContext, timeout(2000)).close();
    }

    private static MockHttpServletRequest localRequest() {
        return localRequest("http://127.0.0.1:8765");
    }

    private static MockHttpServletRequest localRequest(String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("http");
        request.setServerName("127.0.0.1");
        request.setServerPort(8765);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Origin", origin);
        return request;
    }
}
