package com.gurbani.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Punjabi speech-to-text that runs entirely inside the app (no internet, no Google pack).
 * The model files live in assets/asr and are run with sherpa-onnx.
 *
 * The model transcribes whole clips, so the microphone audio is cut into phrases
 * (at pauses, or every few seconds of continuous recitation). While a phrase is still
 * being spoken it is re-transcribed about once a second to give running "interim" text.
 */
final class LocalAsr {

    interface Listener {
        void onReady();

        void onResult(String text, boolean isFinal);

        /** "model" = could not load the bundled model, "audio-capture" = microphone failed. */
        void onError(String code);

        void onEnd();
    }

    private static final String TAG = "GurbaniAsr";
    private static final String DIR = "asr/";
    private static final int RATE = 16000;
    private static final int CHUNK = 1600;            // 100 ms
    private static final int MAX_SEG = RATE * 6;      // cut continuous recitation every 6 s
    private static final int END_SILENCE_CHUNKS = 6;  // 600 ms pause ends a phrase
    private static final int MIN_SPEECH_CHUNKS = 3;   // ignore blips shorter than 300 ms
    private static final int PRE_ROLL = CHUNK * 3;    // keep 300 ms before speech starts

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final AtomicBoolean interimBusy = new AtomicBoolean();

    private OfflineRecognizer recognizer;   // touched only on the decoder thread
    private volatile boolean running;
    private volatile boolean hold;          // true = drop audio (voice is speaking / app in background)
    private volatile int session;

    LocalAsr(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    static boolean isBundled(Context ctx) {
        try (InputStream in = ctx.getAssets().open(DIR + "tokens.txt")) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    boolean isRunning() {
        return running;
    }

    void setHold(boolean h) {
        hold = h;
    }

    void start() {
        if (running) return;
        running = true;
        hold = false;
        final int sid = ++session;
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureModel();
                } catch (Throwable t) {
                    Log.e(TAG, "model load failed", t);
                    running = false;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onError("model");
                            listener.onEnd();
                        }
                    });
                    return;
                }
                if (!running || sid != session) return;
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onReady();
                    }
                });
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        capture(sid);
                    }
                }, "gurbani-mic");
                t.start();
            }
        });
    }

    /** Stops the microphone; anything already spoken is still transcribed, then onEnd fires. */
    void stop() {
        running = false;
    }

    void release() {
        running = false;
        session++;
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                if (recognizer != null) {
                    try {
                        recognizer.release();
                    } catch (Throwable ignored) {
                    }
                    recognizer = null;
                }
            }
        });
        decoder.shutdown();
    }

    /** Transcribes a 16 kHz mono 16-bit WAV from assets. Used by the automated build check. */
    void selfTest(final String assetWav) {
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    ensureModel();
                    long t1 = System.currentTimeMillis();
                    byte[] raw;
                    try (InputStream in = ctx.getAssets().open(assetWav)) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                        raw = bo.toByteArray();
                    }
                    int off = 44;
                    float[] s = new float[(raw.length - off) / 2];
                    for (int i = 0; i < s.length; i++) {
                        int lo = raw[off + 2 * i] & 0xff;
                        int hi = raw[off + 2 * i + 1];
                        s[i] = ((hi << 8) | lo) / 32768f;
                    }
                    String text = transcribe(s);
                    long t2 = System.currentTimeMillis();
                    Log.i("GurbaniSelfTest", "ASR_OK load=" + (t1 - t0) + "ms decode=" + (t2 - t1)
                            + "ms audio=" + (s.length * 1000L / RATE) + "ms text=" + text);
                } catch (Throwable t) {
                    Log.e("GurbaniSelfTest", "ASR_FAIL " + t, t);
                }
            }
        });
    }

    // ------------------------------------------------------------------

    private void post(Runnable r) {
        main.post(r);
    }

    private void ensureModel() {
        if (recognizer != null) return;
        OfflineMoonshineModelConfig moon = new OfflineMoonshineModelConfig();
        moon.setPreprocessor(DIR + "preprocess.onnx");
        moon.setEncoder(DIR + "encode.onnx");
        moon.setUncachedDecoder(DIR + "uncached_decode.onnx");
        moon.setCachedDecoder(DIR + "cached_decode.onnx");

        OfflineModelConfig model = new OfflineModelConfig();
        model.setMoonshine(moon);
        model.setTokens(DIR + "tokens.txt");
        int cores = Runtime.getRuntime().availableProcessors();
        model.setNumThreads(Math.max(1, Math.min(4, cores / 2)));
        model.setDebug(false);

        OfflineRecognizerConfig cfg = new OfflineRecognizerConfig();
        cfg.setModelConfig(model);

        recognizer = new OfflineRecognizer(ctx.getAssets(), cfg);
    }

    private String transcribe(float[] samples) {
        OfflineStream st = recognizer.createStream();
        try {
            st.acceptWaveform(samples, RATE);
            recognizer.decode(st);
            String t = recognizer.getResult(st).getText();
            return t == null ? "" : t.trim();
        } finally {
            st.release();
        }
    }

    private void submit(final float[] samples, final boolean isFinal, final int sid) {
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sid != session || recognizer == null) return;
                    final String text = transcribe(samples);
                    if (text.isEmpty() || sid != session) return;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (sid == session) listener.onResult(text, isFinal);
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "decode failed", t);
                } finally {
                    if (!isFinal) interimBusy.set(false);
                }
            }
        });
    }

    private void capture(final int sid) {
        AudioRecord ar = null;
        boolean micFailed = false;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, RATE * 2));
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("mic init");
            ar.startRecording();

            short[] buf = new short[CHUNK];
            float[] seg = new float[MAX_SEG + PRE_ROLL + CHUNK * 2];
            float[] pre = new float[PRE_ROLL];
            int segLen = 0, preLen = 0;
            boolean inSpeech = false;
            int silence = 0, speech = 0, sinceInterim = 0;
            float noise = 0.004f;

            while (running && sid == session) {
                int n = ar.read(buf, 0, buf.length);
                if (n < 0) throw new IllegalStateException("mic read " + n);
                if (n == 0) continue;
                if (hold) {
                    inSpeech = false;
                    segLen = 0;
                    preLen = 0;
                    continue;
                }
                double sum = 0;
                for (int i = 0; i < n; i++) {
                    float v = buf[i] / 32768f;
                    sum += v * v;
                }
                float rms = (float) Math.sqrt(sum / n);
                boolean voiced = rms > Math.max(0.008f, noise * 2.5f);

                if (!inSpeech) {
                    if (voiced) {
                        inSpeech = true;
                        System.arraycopy(pre, 0, seg, 0, preLen);
                        segLen = preLen;
                        preLen = 0;
                        silence = 0;
                        speech = 1;
                        sinceInterim = 0;
                        for (int i = 0; i < n; i++) seg[segLen++] = buf[i] / 32768f;
                    } else {
                        noise = noise * 0.95f + rms * 0.05f;
                        // slide the pre-roll window
                        if (preLen + n > PRE_ROLL) {
                            int drop = preLen + n - PRE_ROLL;
                            System.arraycopy(pre, drop, pre, 0, preLen - drop);
                            preLen -= drop;
                        }
                        for (int i = 0; i < n; i++) pre[preLen++] = buf[i] / 32768f;
                    }
                    continue;
                }

                for (int i = 0; i < n; i++) seg[segLen++] = buf[i] / 32768f;
                if (voiced) {
                    silence = 0;
                    speech++;
                } else {
                    silence++;
                }
                sinceInterim += n;

                boolean paused = silence >= END_SILENCE_CHUNKS;
                boolean full = segLen >= MAX_SEG;
                if (paused || full) {
                    if (speech >= MIN_SPEECH_CHUNKS) submit(java.util.Arrays.copyOf(seg, segLen), true, sid);
                    inSpeech = false;
                    segLen = 0;
                    preLen = 0;
                } else if (sinceInterim >= RATE && speech >= MIN_SPEECH_CHUNKS) {
                    sinceInterim = 0;
                    if (interimBusy.compareAndSet(false, true)) {
                        submit(java.util.Arrays.copyOf(seg, segLen), false, sid);
                    }
                }
            }
            // stopped mid-phrase: still transcribe what was said
            if (inSpeech && speech >= MIN_SPEECH_CHUNKS && sid == session) {
                submit(java.util.Arrays.copyOf(seg, segLen), true, sid);
            }
        } catch (Throwable t) {
            Log.e(TAG, "microphone failed", t);
            micFailed = true;
        } finally {
            if (ar != null) {
                try {
                    ar.stop();
                } catch (Throwable ignored) {
                }
                try {
                    ar.release();
                } catch (Throwable ignored) {
                }
            }
        }
        running = false;
        final boolean failed = micFailed;
        // queue the end notice behind any transcriptions still running
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (failed) listener.onError("audio-capture");
                        listener.onEnd();
                    }
                });
            }
        });
    }
}
