package uk.valuationloop.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The static side-by-side page (src/main/resources/static/comparison.html). Spring Boot serves classpath:/static
 * under every profile, so no controller is needed. Reuses LoopApiTestBase only to share its Spring context.
 */
class ComparisonPageTest extends LoopApiTestBase {

    // Words the seller page must never contain (ARCHITECTURE.md §0, §8). A plain substring check over the whole
    // file, markup and script included, so it is stricter than a word match.
    static final List<String> FORBIDDEN = List.of("score", "rank", "late", "overdue", "failed", "expired", "band");

    @Test
    void pageIsServedAsHtmlAndUsesTheComparisonApi() throws Exception {
        String page = mvc.perform(get("/comparison.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(page).contains("/valuation-requests/", "/comparison", "/engagement", "/selection",
                "view.disclaimer", "Range: ", "Comparables range: ", "No evidence attached",
                "I viewed this", "Contact", "Request a visit", "Choose this agent");
    }

    @Test
    void pageContainsNoneOfTheForbiddenWords() throws Exception {
        String page = mvc.perform(get("/comparison.html"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);

        for (String word : FORBIDDEN) {
            assertThat(page).as("the page must not contain \"%s\"", word).doesNotContain(word);
        }
    }
}
