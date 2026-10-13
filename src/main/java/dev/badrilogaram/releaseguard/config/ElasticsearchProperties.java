package dev.badrilogaram.releaseguard.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("releaseguard.elasticsearch")
public record ElasticsearchProperties(boolean enabled, URI url, String index, String apiKey) {
    public ElasticsearchProperties {
        if (enabled && (url == null || url.getScheme() == null || !java.util.Set.of("http", "https").contains(url.getScheme())
                || url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null
                || url.getFragment() != null || index == null || !index.matches("[a-zA-Z0-9._*-]+")))
            throw new IllegalArgumentException("Elasticsearch requires an HTTP(S) URL and a valid index pattern");
    }
}
