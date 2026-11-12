package com.aiswarya.songtagger.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SongWorkflowServiceTest {
    @Test
    void buildsSearchQueryFromArtistAndTitleFilename() {
        assertEquals("Pritam Falak Dekhun",
                SongWorkflowService.buildSearchQuery("Pritam - Falak Dekhun.webm"));
    }

    @Test
    void preservesVersionTextInSearchQuery() {
        assertEquals("Pritam Kiss Me Baby (Remix Version)",
                SongWorkflowService.buildSearchQuery("Pritam - Kiss Me Baby (Remix Version).webm"));
    }

    @Test
    void formatsDurationsForTheMatchList() {
        assertEquals("5:13", SongWorkflowService.formatDuration(313.341));
        assertEquals("1:01:02", SongWorkflowService.formatDuration(3662));
    }
}
