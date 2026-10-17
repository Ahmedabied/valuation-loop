package uk.valuationloop.simulator;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import uk.valuationloop.evidence.ComparableDocument;
import uk.valuationloop.evidence.ComparablesSearch;
import uk.valuationloop.evidence.EvidenceProperties;

/**
 * {@link RecentSales} against the comparables index. The query, as you would type it in Kibana:
 * <pre>
 * bool.filter: term outcode = O, term propertyType = T, exists floorAreaM2
 * sort: dateOfTransfer desc, ppdTransactionId asc      (newest first, ties broken the same way every time)
 * </pre>
 */
@Component
@Profile("demo")
public class EsRecentSales implements RecentSales {

    private final ElasticsearchClient client;
    private final EvidenceProperties settings;

    public EsRecentSales(ElasticsearchClient client, EvidenceProperties settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public List<ComparableDocument> latestWithFloorArea(String outcode, String propertyType, int max) {
        SearchRequest request = SearchRequest.of(s -> s
                .index(settings.index())
                .size(max)
                .query(q -> q.bool(b -> b
                        .filter(f -> f.term(t -> t.field("outcode").value(outcode)))
                        .filter(f -> f.term(t -> t.field("propertyType").value(propertyType)))
                        .filter(f -> f.exists(e -> e.field("floorAreaM2")))))
                .sort(so -> so.field(fs -> fs.field("dateOfTransfer").order(SortOrder.Desc)))
                .sort(so -> so.field(fs -> fs.field("ppdTransactionId").order(SortOrder.Asc))));
        try {
            return client.search(request, ComparableDocument.class)
                    .hits().hits().stream().map(Hit::source).filter(Objects::nonNull).toList();
        } catch (IOException e) {
            throw new ComparablesSearch.Unavailable("comparables index " + settings.index() + " unreachable", e);
        }
    }
}
