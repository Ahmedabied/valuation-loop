package uk.valuationloop.evidence;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import java.io.IOException;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Looks a postcode up in the centroid index by document id (a GET, not a search).
 *
 * Why Elasticsearch and not a Postgres table: the loader runs with Elasticsearch only (profile "loader"), and the
 * centroids belong with the comparables they locate; one load writes both, and the demo reads both from ES.
 */
@Component
public class EsPostcodeCentroids implements PostcodeCentroids {

    private final ElasticsearchClient client;
    private final EvidenceProperties settings;

    public EsPostcodeCentroids(ElasticsearchClient client, EvidenceProperties settings) {
        this.client = client;
        this.settings = settings;
    }

    @Override
    public Optional<GeoPoint> find(String postcode) {
        try {
            var response = client.get(g -> g.index(settings.centroidIndex()).id(postcode), Centroid.class);
            return response.found() && response.source() != null
                    ? Optional.of(response.source().location()) : Optional.empty();
        } catch (IOException e) {
            throw new ComparablesSearch.Unavailable("centroid index " + settings.centroidIndex() + " unreachable", e);
        }
    }
}
