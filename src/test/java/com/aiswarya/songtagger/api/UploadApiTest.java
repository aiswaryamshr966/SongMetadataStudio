package com.aiswarya.songtagger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class UploadApiTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uploadsUnicodeAudioUsingSafeTemporaryPath() throws Exception {
        byte[] content = {1, 2, 3, 4};
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../Anmol Salh - Mera Hua.webm", "audio/webm", content);

        MvcResult result = mockMvc.perform(multipart("/api/uploads").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("Anmol Salh - Mera Hua.webm"))
                .andReturn();

        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Path storedPath = Path.of(response.path("path").asText()).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(storedPath));
        assertEquals("Anmol Salh - Mera Hua.webm", storedPath.getFileName().toString());
        assertArrayEquals(content, Files.readAllBytes(storedPath));
    }

    @Test
    void rejectsUnsupportedUploadTypes() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "not-audio.exe", "application/octet-stream", new byte[]{1});

        mockMvc.perform(multipart("/api/uploads").file(file))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(
                        "Unsupported audio file type. Choose a supported audio file."));
    }

    @Test
    void acceptsEachBatchFileAsAnIndependentUpload() throws Exception {
        for (String filename : new String[]{"first.webm", "second.flac"}) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", filename, "application/octet-stream", filename.getBytes());

            MvcResult result = mockMvc.perform(multipart("/api/uploads").file(file))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.filename").value(filename))
                    .andReturn();

            Path storedPath = Path.of(objectMapper.readTree(
                    result.getResponse().getContentAsByteArray()).path("path").asText());
            assertTrue(Files.isRegularFile(storedPath));
        }
    }
}
