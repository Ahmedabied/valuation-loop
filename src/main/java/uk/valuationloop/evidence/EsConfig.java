package uk.valuationloop.evidence;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Reuses Spring's ObjectMapper so documents serialise exactly like the rest of the app's JSON. */
    @Bean
    public JsonpMapper elasticsearchJsonpMapper(ObjectMapper objectMapper) {
        return new JacksonJsonpMapper(objectMapper.copy()); // a copy: the ES client never changes the app-wide mapper
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
