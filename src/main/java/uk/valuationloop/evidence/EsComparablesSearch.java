package uk.valuationloop.evidence;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** {@link ComparablesSearch} against the live index (valuationloop.evidence.index, default comparables-v1). */
@Component
public class EsComparablesSearch implements ComparablesSearch {

    private final ElasticsearchClient client;
    private final EvidenceProperties settings;

    public EsComparablesSearch(ElasticsearchClient client, EvidenceProperties settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public List<ComparableDocument> search(ComparablesQuery query) {
        try {
            // the second argument tells the client which class to turn each hit's _source into
            return client.search(query.searchRequest(settings.index()), ComparableDocument.class)
                    .hits().hits().stream().map(Hit::source).filter(Objects::nonNull).toList();
        } catch (IOException e) {
            throw new Unavailable("comparables index " + settings.index() + " unreachable", e);
        }
    }
}
