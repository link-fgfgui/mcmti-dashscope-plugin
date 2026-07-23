package me.jaffe2718.mcmti.dashscope;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import me.jaffe2718.mcmti.asr.AsrException;
import me.jaffe2718.mcmti.asr.AsrRequest;
import me.jaffe2718.mcmti.asr.AsrResult;
import me.jaffe2718.mcmti.asr.SpeechAsrClient;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DashScope streaming ASR client backed by {@code paraformer-realtime-v2}.
 * <p>
 * Uses the official DashScope Java SDK's {@link Recognition} WebSocket API to
 * stream PCM audio and collect sentence-end transcripts. Each
 * {@link #transcribe(AsrRequest)} call opens a fresh WebSocket session, sends
 * all audio frames, signals stop, and awaits the final {@code onComplete}.
 *
 * <p>Thread-safety: a new {@link Recognition} instance is created per call, so
 * concurrent transcriptions are isolated. The client itself is stateless after
 * construction.
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-java-sdk">
 *      Paraformer 实时语音识别 Java SDK</a>
 */
public final class DashScopeAsrClient implements SpeechAsrClient {

    private static final Logger LOGGER =
            Logger.getLogger(DashScopeAsrClient.class.getName());

    /** Provider id reported back to mcmti. */
    public static final String PROVIDER_ID = "dashscope";

    /** Default model for streaming real-time Mandarin/multilingual ASR. */
    public static final String DEFAULT_MODEL = "paraformer-realtime-v2";

    /** PCM chunk size: 100 ms at 16 kHz / 16-bit / mono = 3200 bytes. */
    private static final int CHUNK_BYTES = 3200;

    /** Inter-chunk sleep to avoid overwhelming the WebSocket send buffer. */
    private static final long CHUNK_SLEEP_MS = 20L;

    private final String apiKey;
    private final String model;
    private final String @Nullable [] languageHints;
    private final int timeoutMs;
    private volatile boolean closed;

    /**
     * @param apiKey        DashScope API key (sk-...)
     * @param model         model id, e.g. {@code paraformer-realtime-v2}
     * @param languageHints optional language hints (e.g. {@code ["zh","en"]});
     *                      {@code null}/empty to let the model auto-detect.
     *                      Only supported by {@code paraformer-realtime-v2}.
     * @param timeoutMs     total wall-clock budget for one
     *                      {@link #transcribe} call
     */
    public DashScopeAsrClient(
            @NotNull String apiKey,
            @NotNull String model,
            String @Nullable [] languageHints,
            int timeoutMs
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.languageHints = (languageHints == null || languageHints.length == 0)
                ? null
                : Arrays.copyOf(languageHints, languageHints.length);
        this.timeoutMs = timeoutMs;
    }

    @Override
    public @NotNull String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isReady() {
        return !closed
                && apiKey != null
                && !apiKey.isBlank()
                && model != null
                && !model.isBlank();
    }

    @Override
    public @NotNull AsrResult transcribe(@NotNull AsrRequest request) throws AsrException {
        if (closed) {
            throw new AsrException("DashScope ASR client has been closed");
        }
        if (request.pcmAudio().length == 0) {
            return AsrResult.EMPTY;
        }

        RecognitionParam param = buildParam(request.sampleRate());
        Recognition recognizer = new Recognition();

        StringBuilder transcript = new StringBuilder();
        AtomicReference<@Nullable Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ResultCallback<RecognitionResult> callback = new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                if (result.getSentence() == null) {
                    return;
                }
                String text = result.getSentence().getText();
                if (text == null || text.isEmpty()) {
                    return;
                }
                if (result.isSentenceEnd()) {
                    if (transcript.length() > 0) {
                        transcript.append(' ');
                    }
                    transcript.append(text);
                }
                // intermediate results are ignored — we only collect finals
            }

            @Override
            public void onComplete() {
                done.countDown();
            }

            @Override
            public void onError(Exception e) {
                error.set(e);
                done.countDown();
            }
        };

        try {
            recognizer.call(param, callback);
        } catch (Exception e) {
            closeQuietly(recognizer);
            throw new AsrException(
                    "Failed to start DashScope recognition session: "
                            + e.getMessage(),
                    e
            );
        }

        // Stream PCM frames to the WebSocket
        byte[] pcm = request.pcmAudio();
        long sendStart = System.currentTimeMillis();
        try {
            for (int offset = 0; offset < pcm.length; offset += CHUNK_BYTES) {
                int end = Math.min(offset + CHUNK_BYTES, pcm.length);
                ByteBuffer frame = ByteBuffer.wrap(pcm, offset, end - offset);
                recognizer.sendAudioFrame(frame);
                if (offset + CHUNK_BYTES < pcm.length) {
                    Thread.sleep(CHUNK_SLEEP_MS);
                }
            }
            // stop() blocks until onComplete/onError fires
            recognizer.stop();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeQuietly(recognizer);
            throw new AsrException(
                    "DashScope ASR interrupted while streaming audio",
                    ie
            );
        } catch (Exception e) {
            closeQuietly(recognizer);
            throw new AsrException(
                    "DashScope ASR failed while sending audio: "
                            + e.getMessage(),
                    e
            );
        }

        // stop() should have already triggered onComplete/onError, but guard
        // with a bounded wait in case the SDK's stop() returns early.
        long remaining = timeoutMs - (System.currentTimeMillis() - sendStart);
        if (remaining > 0 && done.getCount() > 0) {
            try {
                done.await(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                closeQuietly(recognizer);
                throw new AsrException(
                        "DashScope ASR interrupted while waiting for result",
                        ie
                );
            }
        }

        closeQuietly(recognizer);

        Exception err = error.get();
        if (err != null) {
            throw new AsrException(
                    "DashScope ASR callback error: " + err.getMessage(),
                    err
            );
        }

        if (done.getCount() > 0) {
            throw new AsrException(
                    "DashScope ASR timed out (timeoutMs=" + timeoutMs + ")",
                    -1
            );
        }

        String text = transcript.toString().trim();
        return new AsrResult(text, null);
    }

    /**
     * Build the SDK {@link RecognitionParam}.
     * <p>
     * Punctuation prediction and inverse-text-normalization are enabled by
     * default in {@code paraformer-realtime-v2} and require no explicit
     * configuration (per official docs).
     */
    private @NotNull RecognitionParam buildParam(int sampleRate) {
        RecognitionParam.RecognitionParamBuilder<?, ?> b = RecognitionParam.builder()
                .model(model)
                .apiKey(apiKey)
                .format("pcm")
                .sampleRate(sampleRate);

        if (languageHints != null) {
            b.parameter("language_hints", languageHints);
        }

        return b.build();
    }

    private static void closeQuietly(@NotNull Recognition recognizer) {
        try {
            recognizer.getDuplexApi().close(1000, "bye");
        } catch (Exception e) {
            LOGGER.log(Level.FINE,
                    "Error closing DashScope WebSocket (ignorable): "
                            + e.getMessage(),
                    e
            );
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
