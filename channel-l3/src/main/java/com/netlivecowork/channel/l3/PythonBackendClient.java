package com.netlivecowork.channel.l3;

import com.netlivecowork.channel.contract.ChannelContractException;
import com.netlivecowork.channel.contract.ChannelErrorCode;
import com.netlivecowork.channel.contract.FileMetadata;
import com.netlivecowork.channel.contract.FileReadResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 后端专用 HTTP 客户端（l3-backend.md §3.2、§4、§5.3）。只发送业务请求：平台 ID、目的地、按钮与
 * 用户自报身份都不进入请求体（INV-09）。凭证只作为 Authorization 头发送，不写日志、不回显。
 */
final class PythonBackendClient implements BackendTransport {

    /** 原始 HTTP 结果：状态码、响应体与可选的 Retry-After。 */
    record Reply(int status, String body, Long retryAfterMs) {
        boolean success() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http;
    private final L3Settings settings;
    private final ServiceCredentialResolver credentials;

    PythonBackendClient(L3Settings settings, ServiceCredentialResolver credentials) {
        this.settings = settings;
        this.credentials = credentials == null ? ServiceCredentialResolver.NOOP : credentials;
        this.http = HttpClient.newBuilder()
            // FastAPI/Uvicorn serves HTTP/1.1; do not send h2c upgrade frames with request bodies.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(settings.httpTimeoutMs()))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public Reply post(BackendService service, String pathAndQuery, String jsonBody)
        throws IOException, InterruptedException {
        return send(service, "POST", pathAndQuery, jsonBody);
    }

    public Reply get(BackendService service, String pathAndQuery) throws IOException, InterruptedException {
        return send(service, "GET", pathAndQuery, null);
    }

    public Reply delete(BackendService service, String pathAndQuery) throws IOException, InterruptedException {
        return send(service, "DELETE", pathAndQuery, null);
    }

    /**
     * 出站文件读取：流式读入内存并强制 32 MiB 上限，超限明确拒绝而不是静默截断（INV-38、46）。
     * 文件可能较大，使用比普通调用更宽的超时。
     */
    public FileReadResult readFile(BackendService service, String backendFileId, FileMetadata metadata)
        throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(service, "GET", fill(settings.endpoints().file(), "fileId",
            backendFileId))
            .timeout(Duration.ofMillis(Math.max(settings.httpTimeoutMs(), 30_000L)));
        HttpResponse<InputStream> response = http.send(builder.build(),
            HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ChannelContractException(statusCode(response.statusCode()),
                    "backend file read failed with HTTP " + response.statusCode());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) >= 0) {
                total += read;
                if (total > L3Settings.FILE_SIZE_LIMIT_BYTES) {
                    throw new ChannelContractException(ChannelErrorCode.UNSUPPORTED_CONTENT,
                        "backend file exceeds the 32 MiB read limit");
                }
                out.write(buffer, 0, read);
            }
            return new FileReadResult(metadata, out.toByteArray());
        }
    }

    static ChannelErrorCode statusCode(int status) {
        return switch (status) {
            case 401 -> ChannelErrorCode.AUTH_REQUIRED;
            case 403 -> ChannelErrorCode.PERMISSION_DENIED;
            case 404 -> ChannelErrorCode.REQUEST_NOT_FOUND;
            case 409 -> ChannelErrorCode.REQUEST_CONFLICT;
            case 429 -> ChannelErrorCode.RATE_LIMITED;
            default -> ChannelErrorCode.UNAVAILABLE;
        };
    }

    /** 路径模板替换：段值做 URL 编码，模板只来自登记配置，不由用户输入决定（INV-09）。 */
    static String fill(String template, String... pairs) {
        String filled = template;
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            filled = filled.replace("{" + pairs[index] + "}", encode(pairs[index + 1]));
        }
        return filled;
    }

    static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8)
            .replace("+", "%20");
    }

    private Reply send(BackendService service, String method, String pathAndQuery, String jsonBody)
        throws IOException, InterruptedException {
        return exchange(service, method, pathAndQuery, jsonBody, java.util.Map.of());
    }

    public Reply exchange(BackendService service, String method, String pathAndQuery, String jsonBody,
                   java.util.Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder builder = request(service, method, pathAndQuery);
        headers.forEach(builder::header);
        if (jsonBody == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8");
            builder.method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        }
        HttpResponse<String> response = http.send(builder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(response.statusCode(), response.body(), retryAfterMs(response));
    }

    private HttpRequest.Builder request(BackendService service, String method, String pathAndQuery) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(service.baseUrl(), pathAndQuery))
            .timeout(Duration.ofMillis(settings.httpTimeoutMs()))
            .header("Accept", "application/json");
        String token = token(service);
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private String token(BackendService service) {
        if (service.credentialRef() == null) {
            return null;
        }
        try {
            String value = credentials.resolve(service.credentialRef());
            return value == null || value.isBlank() ? null : value;
        } catch (RuntimeException e) {
            // 凭证设施故障不暴露细节；不以空凭证继续，按不可用处理。
            throw new ChannelContractException(ChannelErrorCode.UNAVAILABLE, "service credential is unavailable");
        }
    }

    private static URI uri(String baseUrl, String pathAndQuery) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return URI.create(base + path);
    }

    private static Long retryAfterMs(HttpResponse<String> response) {
        var header = response.headers().firstValue("Retry-After");
        if (header.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(header.get().trim()) * 1000L;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
