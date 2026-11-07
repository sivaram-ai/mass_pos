package com.masspos;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The icon the till shows in the taskbar.
 *
 * <p>The shortcut opens the screen in an Edge {@code --app} window, and such a window takes its
 * icon from the page rather than from the launcher that started it. The page had no icon at all,
 * so a till sat in the taskbar under the browser's blank-page mark. These are cheap checks, but
 * they are the ones that failed: the files were simply not there, and nothing pointed at them.
 */
@SpringBootTest(properties = {
        "pos.data-dir=" + AppIconApiIntegrationTest.DATA_DIR,
        "pos.printer.type=FILE"})
@AutoConfigureMockMvc
class AppIconApiIntegrationTest {

    static final String DATA_DIR = "target/test-data/app-icon-it";

    static {
        FileSystemUtils.deleteRecursively(new File(DATA_DIR));
    }

    @Autowired
    MockMvc mvc;

    @Test
    void thePagePointsAtTheRocket() throws Exception {
        String page = mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(page).contains("rel=\"icon\"", "/gorockit.ico", "/gorockit-192.png",
                "rel=\"manifest\"", "/manifest.json");
    }

    @Test
    void everyIconThePageAsksForIsThere() throws Exception {
        for (String icon : new String[] {"/gorockit.ico", "/gorockit-192.png", "/gorockit-512.png"}) {
            byte[] bytes = mvc.perform(get(icon))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(bytes).as(icon).isNotEmpty();
        }
    }

    /**
     * Named .json rather than .webmanifest so it is served as JSON: Tomcat has no mapping for
     * .webmanifest and would hand it over as a download, which is not a manifest as far as the
     * browser is concerned.
     */
    @Test
    void theManifestArrivesAsJson() throws Exception {
        String manifest = mvc.perform(get("/manifest.json"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith("application/json"))
                .andReturn().getResponse().getContentAsString();

        assertThat(manifest).contains("GO RockIt", "gorockit-192.png", "gorockit-512.png");
    }
}
