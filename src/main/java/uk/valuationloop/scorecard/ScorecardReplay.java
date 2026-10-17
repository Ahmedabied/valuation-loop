package uk.valuationloop.scorecard;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * `make replay`: start the app with --scorecard.replay=true and, once it is up, rebuild the scorecard from the topics
 * and check the result. Only exists in that mode (@ConditionalOnProperty: like a Django management command that is
 * wired in only when asked for).
 *
 * With --scorecard.replay.snapshot=PATH the rebuilt facts are compared row by row with a snapshot: if the file does
 * not exist yet, the facts as they are before the truncate are written to it first, so one run is "snapshot, rebuild,
 * compare". It logs IDENTICAL or the differing rows. With --scorecard.replay.exit=true the app then exits, status 0
 * when identical (or no snapshot asked for) and 1 when not, so a Makefile can use it.
 *
 * Run it with --valuationloop.scorecard.listener.auto-startup=false so the listener does not start on its own before
 * the snapshot; the rebuilder stops it anyway if it did.
 */
@Component
@ConditionalOnProperty(name = "scorecard.replay", havingValue = "true")
public class ScorecardReplay implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScorecardReplay.class);

    private final ScorecardRebuilder rebuilder;
    private final ScorecardFactRepository facts;
    private final ObjectMapper objectMapper;
    private final ConfigurableApplicationContext context;
    private final String snapshotPath;
    private final Duration timeout;
    private final boolean exitWhenDone;

    public ScorecardReplay(ScorecardRebuilder rebuilder, ScorecardFactRepository facts, ObjectMapper objectMapper,
                           ConfigurableApplicationContext context,
                           @Value("${scorecard.replay.snapshot:}") String snapshotPath,
                           @Value("${scorecard.replay.timeout-seconds:120}") long timeoutSeconds,
                           @Value("${scorecard.replay.exit:false}") boolean exitWhenDone) {
        this.rebuilder = rebuilder;
        this.facts = facts;
        this.objectMapper = objectMapper;
        this.context = context;
        this.snapshotPath = snapshotPath;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.exitWhenDone = exitWhenDone;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<FactSnapshot> expected = snapshotPath.isBlank() ? null : expectedSnapshot(Path.of(snapshotPath));

        long rebuilt = rebuilder.rebuild(timeout);
        log.info("replay: {} facts rebuilt", rebuilt);

        boolean identical = true;
        if (expected != null) {
            List<FactSnapshot> actual = facts.findAllByOrderByLeadId().stream().map(FactSnapshot::of).toList();
            List<String> diff = ScorecardRebuilder.diff(expected, actual);
            identical = diff.isEmpty();
            if (identical) {
                log.info("replay: IDENTICAL to {} ({} facts)", snapshotPath, actual.size());
            } else {
                log.warn("replay: {} difference(s) from {}:\n  {}", diff.size(), snapshotPath, String.join("\n  ", diff));
            }
        }
        if (exitWhenDone) {
            int status = identical ? 0 : 1;
            System.exit(SpringApplication.exit(context, () -> status));
        }
    }

    /** The snapshot to compare against: the file if it exists, else the current table, saved to the file first. */
    private List<FactSnapshot> expectedSnapshot(Path path) throws IOException {
        if (Files.exists(path)) {
            log.info("replay: comparing against the existing snapshot {}", path);
            return objectMapper.readValue(path.toFile(), new TypeReference<List<FactSnapshot>>() {});
        }
        List<FactSnapshot> current = facts.findAllByOrderByLeadId().stream().map(FactSnapshot::of).toList();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), current);
        log.info("replay: wrote the current {} facts to {} before the rebuild", current.size(), path);
        return current;
    }
}
