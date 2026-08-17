package me.jaffe2718.mcmti.dashscope;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import me.jaffe2718.mcmti.asr.AsrException;
import me.jaffe2718.mcmti.asr.AsrRequest;
import me.jaffe2718.mcmti.asr.AsrResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DashScope streaming ASR client backed by {@code paraformer-realtime-v2}.
 * <p>
 * Uses the official DashScope Java SDK's {@link Recognition} WebSocket API to
 * stream PCM audio and collect sentence-end transcripts. Each
 * {@link #transcribe(AsrRequest)} call opens a fresh WebSocket session, sends
 * all audio frames, signals stop, and awaits the final {@code onComplete}.
 *
 * @see <a href="https://help.aliyun.com/zh/model-studio/paraformer-real-time-speech-recognition-java-sdk">
 *      Paraformer 实时语音识别 Java SDK</a>
 */
public final class DashScopeAsrClient {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DashScopeAsrClient.class);

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

    public @NotNull String providerId() {
        return PROVIDER_ID;
    }

    public boolean isReady() {
        return !closed
                && apiKey != null
                && !apiKey.isBlank()
                && model != null
                && !model.isBlank();
    }

    public @NotNull AsrResult transcribe(@NotNull AsrRequest request) throws AsrException {
        if (closed) {
            throw new AsrException("DashScope ASR client has been closed");
        }
        int audioLen = request.pcmAudio().length;
        LOGGER.info("transcribe() called: audioBytes={}, sampleRate={}, model={}",
                audioLen, request.sampleRate(), model);
        if (audioLen == 0) {
            LOGGER.warn("transcribe() called with empty PCM — returning EMPTY");
            return AsrResult.EMPTY;
        }

        RecognitionParam param = buildParam(request.sampleRate());
        Recognition recognizer = new Recognition();

        StringBuilder transcript = new StringBuilder();
        AtomicInteger eventCount = new AtomicInteger(0);
        AtomicInteger sentenceEndCount = new AtomicInteger(0);
        AtomicReference<@Nullable Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        ResultCallback<RecognitionResult> callback = new ResultCallback<>() {
            @Override
            public void onEvent(RecognitionResult result) {
                int idx = eventCount.incrementAndGet();
                boolean isEnd = result.isSentenceEnd();
                if (isEnd) {
                    sentenceEndCount.incrementAndGet();
                }
                if (result.getSentence() == null) {
                    LOGGER.debug("onEvent#{}: sentenceEnd={}, sentence=null", idx, isEnd);
                    return;
                }
                String text = result.getSentence().getText();
                LOGGER.info("onEvent#{}: sentenceEnd={}, text='{}'", idx, isEnd, text);
                if (text == null || text.isEmpty()) {
                    return;
                }
                if (isEnd) {
                    if (transcript.length() > 0) {
                        transcript.append(' ');
                    }
                    transcript.append(text);
                }
                // intermediate results are ignored — we only collect finals
            }

            @Override
            public void onComplete() {
                LOGGER.info("onComplete: totalEvents={}, sentenceEnds={}, transcript='{}'",
                        eventCount.get(), sentenceEndCount.get(), transcript);
                done.countDown();
            }

            @Override
            public void onError(Exception e) {
                LOGGER.error("onError: {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
                error.set(e);
                done.countDown();
            }
        };

        try {
            recognizer.call(param, callback);
            LOGGER.info("recognizer.call() returned — WebSocket session started");
        } catch (Throwable t) {
            // Catch Throwable (not Exception) so NoClassDefFoundError /
            // ExceptionInInitializerError from missing transitive deps
            // (OkHttp/Okio/RxJava) are logged instead of silently killing
            // the recognizer thread.
            LOGGER.error("recognizer.call() threw: {}: {}",
                    t.getClass().getSimpleName(), t.getMessage(), t);
            closeQuietly(recognizer);
            throw new AsrException(
                    "Failed to start DashScope recognition session: "
                            + t.getClass().getSimpleName() + ": " + t.getMessage(),
                    t instanceof Exception ? (Exception) t : new RuntimeException(t)
            );
        }

        // Stream PCM frames to the WebSocket
        byte[] pcm = request.pcmAudio();
        long sendStart = System.currentTimeMillis();
        int framesSent = 0;
        try {
            for (int offset = 0; offset < pcm.length; offset += CHUNK_BYTES) {
                int end = Math.min(offset + CHUNK_BYTES, pcm.length);
                ByteBuffer frame = ByteBuffer.wrap(pcm, offset, end - offset);
                recognizer.sendAudioFrame(frame);
                framesSent++;
                if (offset + CHUNK_BYTES < pcm.length) {
                    Thread.sleep(CHUNK_SLEEP_MS);
                }
            }
            LOGGER.info("All PCM frames sent ({} frames, {} bytes), calling stop()...",
                    framesSent, pcm.length);
            // stop() blocks until onComplete/onError fires
            recognizer.stop();
            LOGGER.info("recognizer.stop() returned");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            closeQuietly(recognizer);
            throw new AsrException(
                    "DashScope ASR interrupted while streaming audio",
                    ie
            );
        } catch (Exception e) {
            LOGGER.error("Audio streaming threw: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
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
            LOGGER.info("Waiting up to {}ms for completion (latch={})", remaining, done.getCount());
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
        LOGGER.info("transcribe() returning: text='{}' (length={})", text, text.length());
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
            LOGGER.debug("Error closing DashScope WebSocket (ignorable): {}", e.getMessage(), e);
        }
    }

    public void close() {
        closed = true;
    }
}
