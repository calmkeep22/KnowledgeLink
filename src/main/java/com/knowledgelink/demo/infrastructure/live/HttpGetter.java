package com.knowledgelink.demo.infrastructure.live;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 공개 API GET 호출. 리다이렉트를 따르지 않고 2xx가 아니면 수집 실패로 본다. 응답 본문은 예외에 싣지 않는다. */
@FunctionalInterface
interface HttpGetter {
    String get(URI uri, Map<String, String> headers);

    static HttpGetter of(HttpClient client, Duration timeout) {
        return (uri, headers) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(timeout).GET();
            headers.forEach(builder::header);
            HttpResponse<String> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new LiveSourceException("수집 호출이 중단되었습니다: " + uri.getHost(), exception);
            } catch (IOException exception) {
                throw new LiveSourceException("수집 대상에 연결하지 못했습니다: " + uri.getHost(), exception);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LiveSourceException("수집 대상이 HTTP " + response.statusCode() + "을 반환했습니다: " + uri.getHost());
            }
            return response.body();
        };
    }
}
