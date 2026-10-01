package kpurifyfixture;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** `Content` 形状：urls 关键词 → 跳转值。 */
public final class Content {
    private final Map<String, Object> urls;

    public Content(Map<String, Object> urls) { this.urls = Collections.unmodifiableMap(new LinkedHashMap<>(urls)); }

    public Map<String, Object> getUrlsMap() { return urls; }

    public static Builder newBuilder(Content prototype) { return new Builder(prototype); }

    public static final class Builder {
        private final Map<String, Object> urls;

        Builder(Content prototype) { urls = new LinkedHashMap<>(prototype.urls); }

        public Builder clearUrls() { urls.clear(); return this; }

        public Builder putAllUrls(Map<String, Object> values) { urls.putAll(values); return this; }

        public Content build() { return new Content(urls); }
    }
}
