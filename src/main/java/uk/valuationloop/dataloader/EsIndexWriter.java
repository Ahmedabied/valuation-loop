package uk.valuationloop.dataloader;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Creates an index from a mapping file on the classpath and bulk-writes documents into it.
 * Plain client calls; no Spring Data Elasticsearch (H2-review E2-10).
 */
@Component
public class EsIndexWriter {

    private final ElasticsearchClient client;

    public EsIndexWriter(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * Makes sure the index exists with the mapping in {@code mappingResource} (e.g. "/es/comparables-v1.json").
     * @param recreate drop it first if it exists
     * @return true if the index was created now
     */
    public boolean ensureIndex(String index, String mappingResource, boolean recreate) throws IOException {
        boolean exists = client.indices().exists(e -> e.index(index)).value();
        if (exists && recreate) {
            client.indices().delete(d -> d.index(index));
            exists = false;
        }
        if (exists) {
            return false;
        }
        try (InputStream mapping = EsIndexWriter.class.getResourceAsStream(mappingResource)) {
            if (mapping == null) {
                throw new IOException("no mapping file " + mappingResource + " on the classpath");
            }
            client.indices().create(c -> c.index(index).withJson(mapping));
        }
        return true;
    }

    /** Indexes id -> document in one bulk request; fails loudly if any item was rejected. */
    public <T> void bulk(String index, Map<String, T> documentsById) throws IOException {
        if (documentsById.isEmpty()) {
            return;
        }
        BulkRequest.Builder bulk = new BulkRequest.Builder();
        documentsById.forEach((id, doc) -> bulk.operations(op -> op.index(i -> i.index(index).id(id).document(doc))));
        BulkResponse response = client.bulk(bulk.build());
        if (response.errors()) {
            String first = response.items().stream().filter(i -> i.error() != null).findFirst()
                    .map(i -> i.error().reason()).orElse("unknown");
            throw new IOException("bulk indexing into " + index + " had errors, first: " + first);
        }
    }

    public void refresh(String index) throws IOException {
        client.indices().refresh(r -> r.index(index));
    }

    public void deleteIndex(String index) throws IOException {
        client.indices().delete(d -> d.index(index).ignoreUnavailable(true));
    }
}
