package uk.valuationloop.evidence;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The Elasticsearch client, built by hand from valuationloop.es.url (default http://127.0.0.1:9200).
 *
 * The 8.18 Java client sits on three layers: the low-level RestClient (HTTP connection pool), a transport that
 * turns typed requests into JSON (RestClientTransport + a JsonpMapper), and the typed ElasticsearchClient on top.
 * Declaring all of them here makes Spring Boot's own Elasticsearch auto-configuration step aside
 * (it only creates these beans when none exist), so there is exactly one client, pointed at one URL.
 *
 * Jackson: the 8.x client's JacksonJsonpMapper is built on Jackson 2, while Spring Boot 4 configures Jackson 3 for the
 * rest of the app. So the client gets its own small Jackson 2 mapper with the settings Boot 3 used to give it
 * (unknown fields ignored, dates as ISO strings). The documents are plain records with String dates, so they
 * serialise byte for byte as before (docs/adr/0005-boot-4-upgrade-attempt.md).
 *
 * Tolerant by construction: building a RestClient opens no connection, so the app context (and every test) starts
 * without Elasticsearch running. The first real call fails if ES is down, and callers turn that into a 503.
 */
@Configuration
@EnableConfigurationProperties({EsConfig.EsProperties.class, EvidenceProperties.class})
public class EsConfig {

    @ConfigurationProperties("valuationloop.es")
    public record EsProperties(@DefaultValue("http://127.0.0.1:9200") String url) {}

    @Bean(destroyMethod = "close")
    public RestClient elasticsearchRestClient(EsProperties props) {
        return RestClient.builder(HttpHost.create(props.url())).build();
    }

    /** The client's own Jackson 2 mapper, configured like Spring Boot 3's default ObjectMapper was. */
    @Bean
    public JsonpMapper elasticsearchJsonpMapper() {
        JsonMapper jackson2 = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)   // an extra field in a stored document is ignored
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(MapperFeature.DEFAULT_VIEW_INCLUSION)
                .build();
        return new JacksonJsonpMapper(jackson2);
    }

    @Bean(destroyMethod = "close")
    public ElasticsearchTransport elasticsearchTransport(RestClient restClient, JsonpMapper mapper) {
        return new RestClientTransport(restClient, mapper);
    }

    @Bean
    public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
        return new ElasticsearchClient(transport);
    }
}
