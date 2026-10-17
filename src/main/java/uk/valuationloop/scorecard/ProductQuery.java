package uk.valuationloop.scorecard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * The product question from ARCHITECTURE.md §9, run over scorecard_fact: of the seller engagement and the
 * instructions that went to ranked replies, what share went to the HIGHEST-range reply, split by whether that reply
 * carried evidence; and, for the leads that later completed (Land Registry late match), the median gap between the
 * completed price and the replied range's midpoint, for the highest-range reply and for the others. The median is
 * computed in the SQL (percentile_cont, which H2 and Postgres both run). The SQL lives in src/main/resources/sql/product-query.sql so it can be pasted into psql as is.
 *
 * Rightmove-internal product analytics: aggregated across all agents, never shown to an agent or a seller, and it
 * names no agent. It answers "do evidenced answers win the seller", not "which agent is best".
 */
@Component
public class ProductQuery {

    public record Row(boolean evidenceAttached, long rankedReplies,
                      long engaged, long engagedHighest, Double engagedHighestShare,
                      long instructed, long instructedHighest, Double instructedHighestShare,
                      long completed, Double medianPriceGapPctHighest, Double medianPriceGapPctOther) {}

    private final JdbcTemplate jdbc;
    private final String sql;

    public ProductQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.sql = load("sql/product-query.sql");
    }

    public List<Row> run() {
        return jdbc.query(sql, (rs, i) -> new Row(
                rs.getBoolean("evidence_attached"),
                rs.getLong("ranked_replies"),
                rs.getLong("engaged"),
                rs.getLong("engaged_highest"),
                toDouble(rs.getBigDecimal("engaged_highest_share")),
                rs.getLong("instructed"),
                rs.getLong("instructed_highest"),
                toDouble(rs.getBigDecimal("instructed_highest_share")),
                rs.getLong("completed"),
                toDouble(rs.getBigDecimal("median_price_gap_pct_highest")),
                toDouble(rs.getBigDecimal("median_price_gap_pct_other"))));
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    /** Reads the file and drops the trailing ';' (psql wants it, a JDBC statement does not). */
    private static String load(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            String text = StreamUtils.copyToString(in, StandardCharsets.UTF_8).strip();
            return text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + path, e);
        }
    }
}
