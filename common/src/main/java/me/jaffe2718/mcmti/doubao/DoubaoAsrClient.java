package me.jaffe2718.mcmti.doubao;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.jaffe2718.mcmti.asr.AsrException;
import me.jaffe2718.mcmti.asr.AsrRequest;
import me.jaffe2718.mcmti.asr.AsrResult;
import me.jaffe2718.mcmti.asr.WavAudio;
import me.jaffe2718.mcmti.util.AudioRecorder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

/**
 * Volcengine Doubao big-model speech recognition client.
 * <p>
 * Uses the <em>record file flash recognition</em> HTTP endpoint
 * ({@code POST /api/v3/auc/bigmodel/recognize/flash}), which returns the whole
 * transcript synchronously — no submit/query polling. There is no official Java
 * SDK bundled here, so the HTTP call is implemented directly with
 * {@link java.net.http.HttpClient} and Gson.
 * <p>
 * Audio is submitted as {@code audio.data}, a plain Base64 string (no
 * {@code data:} URL prefix). The recorded PCM is wrapped in a RIFF/WAVE
 * container first; the endpoint also accepts wav/mp3/ogg-opus files up to
 * 100 MB / 2 hours.
 *
 * @see <a href="https://www.volcengine.com/docs/6561/1631584?lang=zh">
 *      录音文件极速版识别 HTTP</a>
 */
public final class DoubaoAsrClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(DoubaoAsrClient.class);

    /** Provider id reported to mcmti. */
    public static final String PROVIDER_ID = "doubao";

    /** Flash recognition endpoint (synchronous, single request). */
    public static final String ENDPOINT =
            "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash";

    /** Fixed resource id for the big-model flash service. */
    public static final String RESOURCE_ID = "volc.bigasr.auc_turbo";

    /** Documented max audio size for the flash service. */
    public static final long MAX_AUDIO_BYTES = 100L * 1024 * 1024;

    private static final String STATUS_OK = "20000000";
    private static final String STATUS_SILENT = "20000003";

    private final String apiKey;
    private final String model;
    private final @Nullable String language;
    private final boolean enableItn;
    private final boolean enablePunc;
    private final boolean enableDdc;
    private final int timeoutMs;
    private final HttpClient httpClient;

    public DoubaoAsrClient(
            @NotNull String apiKey,
            @NotNull String model,
            @Nullable String language,
            boolean enableItn,
            boolean enablePunc,
            boolean enableDdc,
            int timeoutMs
    ) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = (model == null || model.isBlank()) ? "bigmodel" : model.trim();
        this.language = resolveLanguage(language);
        this.enableItn = enableItn;
        this.enablePunc = enablePunc;
        this.enableDdc = enableDdc;
        this.timeoutMs = Math.max(1_000, timeoutMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public @NotNull String providerId() {
        return PROVIDER_ID;
    }

    public boolean isReady() {
        return !apiKey.isEmpty() && !model.isEmpty();
    }

    public @NotNull AsrResult transcribe(@NotNull AsrRequest request) throws AsrException {
        if (!isReady()) {
            throw new AsrException("Doubao ASR is not configured (missing API key)");
        }
        int audioLen = request.pcmAudio().length;
        if (audioLen == 0) {
            return AsrResult.EMPTY;
        }

        byte[] wav = WavAudio.pcmToWav(
                request.pcmAudio(),
                request.sampleRate() > 0 ? request.sampleRate() : AudioRecorder.SAMPLE_RATE,
                AudioRecorder.CHANNELS,
                AudioRecorder.SAMPLE_SIZE_BITS
        );
        if (wav.length > MAX_AUDIO_BYTES) {
            throw new AsrException("Audio file too large for Doubao flash ASR ("
                    + wav.length + " bytes, limit " + MAX_AUDIO_BYTES + " bytes)");
        }

        String requestId = UUID.randomUUID().toString();
        String base64Audio = Base64.getEncoder().encodeToString(wav);
        String body = buildRequestBody(base64Audio);
        LOGGER.info("transcribe(): audioBytes={}, wavBytes={}, sampleRate={}, model={}, requestId={}",
                audioLen, wav.length, request.sampleRate(), model, requestId);

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", RESOURCE_ID)
                .header("X-Api-Request-Id", requestId)
                .header("X-Api-Sequence", "-1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new AsrException("Doubao ASR network error: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrException("Doubao ASR request interrupted", e);
        }
        long elapsed = System.currentTimeMillis() - start;

        String statusCode = header(response, "X-Api-Status-Code");
        String statusMessage = header(response, "X-Api-Message");
        String logId = header(response, "X-Tt-Logid");
        LOGGER.info("Doubao ASR response: http={}, statusCode={}, message={}, logid={}, elapsedMs={}",
                response.statusCode(), statusCode, statusMessage, logId, elapsed);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AsrException("Doubao ASR HTTP " + response.statusCode() + ": "
                    + truncate(response.body(), 500), response.statusCode());
        }
        if (STATUS_SILENT.equals(statusCode)) {
            LOGGER.info("Doubao ASR reported silent audio, returning empty result");
            return AsrResult.EMPTY;
        }
        if (!STATUS_OK.equals(statusCode)) {
            throw new AsrException("Doubao ASR failed: " + statusCode
                    + (statusMessage.isBlank() ? "" : " " + statusMessage)
                    + (logId.isBlank() ? "" : " (logid=" + logId + ")"));
        }

        return parseResponse(response.body());
    }

    private @NotNull String buildRequestBody(@NotNull String base64Audio) {
        JsonObject user = new JsonObject();
        user.addProperty("uid", apiKey);

        JsonObject audio = new JsonObject();
        audio.addProperty("data", base64Audio);

        JsonObject req = new JsonObject();
        req.addProperty("model_name", model);
        if (language != null) {
            req.addProperty("language", language);
        }
        req.addProperty("enable_itn", enableItn);
        req.addProperty("enable_punc", enablePunc);
        req.addProperty("enable_ddc", enableDdc);

        JsonObject root = new JsonObject();
        root.add("user", user);
        root.add("audio", audio);
        root.add("request", req);
        return root.toString();
    }

    private static @NotNull AsrResult parseResponse(@Nullable String body) throws AsrException {
        if (body == null || body.isBlank()) {
            throw new AsrException("Doubao ASR returned an empty response body");
        }
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonElement result = root.get("result");
            String text = extractText(result);
            return new AsrResult(text == null ? "" : text.trim(), null);
        } catch (AsrException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new AsrException("Failed to parse Doubao ASR response: " + truncate(body, 300), e);
        }
    }

    /**
     * {@code result} is an object in the current API, but tolerate an array
     * shape as well (take the first element's text).
     */
    private static @Nullable String extractText(@Nullable JsonElement result) {
        if (result == null || result.isJsonNull()) {
            return null;
        }
        if (result.isJsonObject()) {
            return textOf(result.getAsJsonObject());
        }
        if (result.isJsonArray()) {
            JsonArray array = result.getAsJsonArray();
            for (JsonElement element : array) {
                if (element.isJsonObject()) {
                    String text = textOf(element.getAsJsonObject());
                    if (text != null && !text.isBlank()) {
                        return text;
                    }
                }
            }
        }
        return null;
    }

    private static @Nullable String textOf(@NotNull JsonObject object) {
        JsonElement text = object.get("text");
        return (text == null || text.isJsonNull()) ? null : text.getAsString();
    }

    /**
     * Map the free-form config language to a Doubao language code.
     * {@code auto}/blank → {@code null} (omit the field, let the model auto-detect).
     */
    static @Nullable String resolveLanguage(@Nullable String language) {
        if (language == null) {
            return null;
        }
        String lang = language.trim();
        if (lang.isEmpty() || "auto".equalsIgnoreCase(lang)) {
            return null;
        }
        return switch (lang.toLowerCase(Locale.ROOT)) {
            case "zh", "cn", "chinese", "mandarin", "zh-cn" -> "zh-CN";
            case "en", "english", "en-us" -> "en-US";
            case "yue", "cantonese", "yue-cn" -> "yue-CN";
            case "ja", "japanese", "ja-jp" -> "ja-JP";
            case "ko", "korean", "ko-kr" -> "ko-KR";
            default -> lang;
        };
    }

    private static @NotNull String header(@NotNull HttpResponse<?> response, @NotNull String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static @NotNull String truncate(@Nullable String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
