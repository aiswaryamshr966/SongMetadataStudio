package com.aiswarya.songtagger.api;

import com.aiswarya.songtagger.model.ApiModels.ApiError;
import com.aiswarya.songtagger.model.ApiModels.ShutdownResponse;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;

@RestController
@RequestMapping("/api")
public class ShutdownController {
    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(ShutdownController.class);

    private final ConfigurableApplicationContext applicationContext;

    public ShutdownController(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostMapping("/shutdown")
    public ResponseEntity<?> shutdown(HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr()) || !hasSameOrigin(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new ApiError("Shutdown is only available to this local app page."));
        }

        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(250);
                applicationContext.close();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                logger.warn("The scheduled app shutdown was interrupted.", exception);
            }
        }, "song-metadata-studio-shutdown");
        shutdownThread.setDaemon(true);
        shutdownThread.start();
        return ResponseEntity.accepted().body(new ShutdownResponse(
                "The local server is shutting down. Saved FLAC copies are kept."));
    }

    private static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static boolean hasSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return false;
        }

        String scheme = request.getScheme();
        int port = request.getServerPort();
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                || ("https".equalsIgnoreCase(scheme) && port == 443);
        String expectedOrigin = scheme + "://" + request.getServerName()
                + (defaultPort ? "" : ":" + port);
        return expectedOrigin.equalsIgnoreCase(origin);
    }
}
