package com.bilibili.lib.ighttp;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

/** Fixture with the 9.16.1 shape of the host's native HTTP request; execute() never does I/O. */
public final class IgHttpRequest {
    final String mUrl;
    final String mMethod;
    final Map<String, String> mHeaders;
    public boolean closed;
    public int executed;

    private IgHttpRequest(Builder builder) {
        mUrl = builder.mUrl;
        mMethod = builder.mMethod;
        mHeaders = builder.mHeaders;
    }

    public IgHttpResponse execute() throws IOException {
        executed++;
        return new IgHttpResponse();
    }

    public String url() { return mUrl; }

    public String method() { return mMethod; }

    public Map<String, String> headers() { return mHeaders; }

    public void close() { closed = true; }

    public static class Builder {
        private String mUrl;
        private String mMethod = "GET";
        private final Map<String, String> mHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        public Builder url(String url) {
            mUrl = url;
            return this;
        }

        public Builder addHeaders(String name, String value) {
            mHeaders.put(name, value);
            return this;
        }

        public Builder method(String method, byte[] body) {
            mMethod = method;
            return this;
        }

        public IgHttpRequest build() { return new IgHttpRequest(this); }
    }
}
