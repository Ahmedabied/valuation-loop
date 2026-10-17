package uk.valuationloop.completion;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.ComparablesSearch;
import uk.valuationloop.evidence.EvidenceProperties;

/**
 * {@link CompletedSales} against the comparables index. The query, as you would type it in Kibana:
 * <pre>
 * bool.filter: term postcode = P, term propertyType = T, range dateOfTransfer >= D
 * sort: dateOfTransfer asc, ppdTransactionId asc      (earliest sale first, ties broken the same way every time)
 * </pre>
 * Filters only, no scoring: every hit satisfies the rule exactly, and the order is a plain sort.
 */
@Component
public class EsCompletedSales implements CompletedSales {

    static final int MAX_HITS = 10;

    private final ElasticsearchClient client;
    private final EvidenceProperties settings;

    public EsCompletedSales(ElasticsearchClient client, EvidenceProperties settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public List<ComparableDocument> find(String postcode, String propertyType, LocalDate onOrAfter) {
        SearchRequest request = SearchRequest.of(s -> s
                .index(settings.index())
                .size(MAX_HITS)
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("postcode").value(postcode)))
                        .filter(f -> f.term(t -> t.field("propertyType").value(propertyType)))
                        .filter(f -> f.range(r -> r.date(d -> d.field("dateOfTransfer").gte(onOrAfter.toString()))))))
                .sort(so -> so.field(fs -> fs.field("dateOfTransfer").order(SortOrder.Asc)))
                .sort(so -> so.field(fs -> fs.field("ppdTransactionId").order(SortOrder.Asc))));
        try {
            return client.search(request, ComparableDocument.class)
                    .hits().hits().stream().map(Hit::source).filter(Objects::nonNull).toList();
        } catch (IOException e) {
            throw new ComparablesSearch.Unavailable("comparables index " + settings.index() + " unreachable", e);
        }
    }
}
