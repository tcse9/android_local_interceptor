package dev.localinspector;

import okhttp3.EventListener;

final class TimingListener extends EventListener {
    private final EventListener delegate;
    private final CallTiming timing;

    TimingListener(EventListener delegate, CallTiming timing) {
        this.delegate = delegate;
        this.timing = timing;
    }

    @Override public void callStart(okhttp3.Call call) {
        timing.mark("callStart");
        delegate.callStart(call);
    }

    @Override public void proxySelectStart(okhttp3.Call call, okhttp3.HttpUrl url) {
        timing.mark("proxySelectStart");
        delegate.proxySelectStart(call, url);
    }

    @Override public void proxySelectEnd(okhttp3.Call call, okhttp3.HttpUrl url, java.util.List<java.net.Proxy> proxies) {
        timing.mark("proxySelectEnd");
        delegate.proxySelectEnd(call, url, proxies);
    }

    @Override public void dnsStart(okhttp3.Call call, String domain) {
        timing.mark("dnsStart");
        delegate.dnsStart(call, domain);
    }

    @Override public void dnsEnd(okhttp3.Call call, String domain, java.util.List<java.net.InetAddress> addresses) {
        timing.mark("dnsEnd");
        delegate.dnsEnd(call, domain, addresses);
    }

    @Override public void connectStart(okhttp3.Call call, java.net.InetSocketAddress address, java.net.Proxy proxy) {
        timing.mark("connectStart");
        delegate.connectStart(call, address, proxy);
    }

    @Override public void secureConnectStart(okhttp3.Call call) {
        timing.mark("secureConnectStart");
        delegate.secureConnectStart(call);
    }

    @Override public void secureConnectEnd(okhttp3.Call call, okhttp3.Handshake handshake) {
        timing.mark("secureConnectEnd");
        delegate.secureConnectEnd(call, handshake);
    }

    @Override public void connectEnd(okhttp3.Call call, java.net.InetSocketAddress address, java.net.Proxy proxy, okhttp3.Protocol protocol) {
        timing.mark("connectEnd");
        delegate.connectEnd(call, address, proxy, protocol);
    }

    @Override public void connectFailed(okhttp3.Call call, java.net.InetSocketAddress address, java.net.Proxy proxy, okhttp3.Protocol protocol, java.io.IOException error) {
        timing.mark("connectFailed");
        delegate.connectFailed(call, address, proxy, protocol, error);
    }

    @Override public void connectionAcquired(okhttp3.Call call, okhttp3.Connection connection) {
        timing.mark("connectionAcquired");
        delegate.connectionAcquired(call, connection);
    }

    @Override public void connectionReleased(okhttp3.Call call, okhttp3.Connection connection) {
        timing.mark("connectionReleased");
        delegate.connectionReleased(call, connection);
    }

    @Override public void requestHeadersStart(okhttp3.Call call) {
        timing.mark("requestHeadersStart");
        delegate.requestHeadersStart(call);
    }

    @Override public void requestHeadersEnd(okhttp3.Call call, okhttp3.Request request) {
        timing.mark("requestHeadersEnd");
        delegate.requestHeadersEnd(call, request);
    }

    @Override public void requestBodyStart(okhttp3.Call call) {
        timing.mark("requestBodyStart");
        delegate.requestBodyStart(call);
    }

    @Override public void requestBodyEnd(okhttp3.Call call, long bytes) {
        timing.mark("requestBodyEnd");
        delegate.requestBodyEnd(call, bytes);
    }

    @Override public void requestFailed(okhttp3.Call call, java.io.IOException error) {
        timing.mark("requestFailed");
        delegate.requestFailed(call, error);
    }

    @Override public void responseHeadersStart(okhttp3.Call call) {
        timing.mark("responseHeadersStart");
        delegate.responseHeadersStart(call);
    }

    @Override public void responseHeadersEnd(okhttp3.Call call, okhttp3.Response response) {
        timing.mark("responseHeadersEnd");
        delegate.responseHeadersEnd(call, response);
    }

    @Override public void responseBodyStart(okhttp3.Call call) {
        timing.mark("responseBodyStart");
        delegate.responseBodyStart(call);
    }

    @Override public void responseBodyEnd(okhttp3.Call call, long bytes) {
        timing.mark("responseBodyEnd");
        delegate.responseBodyEnd(call, bytes);
    }

    @Override public void responseFailed(okhttp3.Call call, java.io.IOException error) {
        timing.mark("responseFailed");
        delegate.responseFailed(call, error);
    }

    @Override public void callEnd(okhttp3.Call call) {
        timing.mark("callEnd");
        delegate.callEnd(call);
    }

    @Override public void callFailed(okhttp3.Call call, java.io.IOException error) {
        timing.mark("callFailed");
        delegate.callFailed(call, error);
    }

    @Override public void canceled(okhttp3.Call call) {
        timing.mark("canceled");
        delegate.canceled(call);
    }

    @Override public void satisfactionFailure(okhttp3.Call call, okhttp3.Response response) {
        timing.mark("satisfactionFailure");
        delegate.satisfactionFailure(call, response);
    }

    @Override public void cacheHit(okhttp3.Call call, okhttp3.Response response) {
        timing.mark("cacheHit");
        delegate.cacheHit(call, response);
    }

    @Override public void cacheMiss(okhttp3.Call call) {
        timing.mark("cacheMiss");
        delegate.cacheMiss(call);
    }

    @Override public void cacheConditionalHit(okhttp3.Call call, okhttp3.Response response) {
        timing.mark("cacheConditionalHit");
        delegate.cacheConditionalHit(call, response);
    }
}
