package com.gurbani.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fully self-contained Gurbani app: the page and the whole scripture live inside the APK
 * (src/main/assets). Nothing is loaded from guruvani.com.
 */
public class MainActivity extends Activity {

    // Bundled files are served on this private in-app https address (needed so the
    // page gets a secure origin for IndexedDB etc.). It never touches the internet.
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/index.html";

    private static final int REQ_MIC = 1;

    private WebView web;
    private View rootView;
    private WebViewAssetLoader assetLoader;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private PermissionRequest pendingWebRequest;
    private boolean pendingSpeech;

    // speech recognition state (main thread only)
    private SpeechRecognizer recognizer;
    private boolean usingOnDevice;
    private boolean preferOffline;
    private String lang = "";
    private boolean continuous;
    private boolean interim;
    private boolean sessionActive;
    private boolean stopRequested;
    private boolean startedEmitted;
    private int busyRetries;

    // text to speech
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean ttsInitDone;
    private int setupTries;
    private volatile boolean ttsSpeaking;

    // recognizer start/stop beep suppression
    private AudioManager audio;
    private final int[] MUTE_STREAMS = {
            AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM
    };
    private final boolean[] mutedByUs = new boolean[3];
    private boolean paused;
    private final Runnable unmuteTask = new Runnable() {
        @Override
        public void run() {
            unmute();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        // Newer Android draws apps behind the status and navigation bars. Keep the page clear of
        // them by padding a maroon frame around the web view.
        final FrameLayout root = new FrameLayout(this);
        rootView = root;
        root.setBackgroundColor(0xFF4B1515);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                } else {
                    v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
                }
                return insets;
            }
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);

        web.addJavascriptInterface(new SpeechBridge(), "AndroidSpeech");
        web.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
        web.addJavascriptInterface(new SetupBridge(), "AndroidSetup");

        boolean injectedAtStart = false;
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(web, POLYFILL, Collections.singleton("*"));
                injectedAtStart = true;
            }
        } catch (Throwable ignored) {
        }
        final boolean needLateInject = !injectedAtStart;

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (APP_HOST.equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (needLateInject) view.evaluateJavascript(POLYFILL, null);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                boolean wantsAudio = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
                }
                if (!wantsAudio) {
                    request.deny();
                    return;
                }
                if (hasMic()) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    if (pendingWebRequest != null) pendingWebRequest.deny();
                    pendingWebRequest = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            }
        });

        try {
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    ttsInitDone = true;
                    if (ttsReady) tts.setOnUtteranceProgressListener(ttsListener);
                }
            });
        } catch (Exception ignored) {
            ttsInitDone = true;
        }

        web.loadUrl(START_URL);

        // Automated build check (never used in normal runs): report page state to the log.
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) runSelfTest();
    }

    private void runSelfTest() {
        Log.i("GurbaniSelfTest", "APP_STARTED");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(
                        "(function(){try{return 'offline='+OFFLINE+' lines='+(CORPUS?CORPUS.length:0)"
                                + "+' sr='+(typeof webkitSpeechRecognition)+' tts='+(typeof speechSynthesis)"
                                + "+' fontsLoaded='+Array.from(document.fonts).filter(function(f){return f.status==='loaded';}).length"
                                + "+'/'+document.fonts.size;}"
                                + "catch(e){return 'ERR '+e;}})()",
                        new android.webkit.ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String v) {
                                Log.i("GurbaniSelfTest", "PAGE " + v);
                                if (rootView != null) {
                                    Log.i("GurbaniSelfTest", "INSETS top=" + rootView.getPaddingTop()
                                            + " bottom=" + rootView.getPaddingBottom()
                                            + " rootH=" + rootView.getHeight() + " webH=" + web.getHeight());
                                }
                            }
                        });
            }
        }, 30000);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;

        if (pendingWebRequest != null) {
            if (granted) {
                pendingWebRequest.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingWebRequest.deny();
            }
            pendingWebRequest = null;
        }
        if (pendingSpeech) {
            pendingSpeech = false;
            if (granted) {
                beginSpeech();
            } else {
                speechError("not-allowed");
                endSpeech();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        // never leave the phone muted or the mic running while the app is in the background
        if (sessionActive && recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }
        }
        handler.removeCallbacks(unmuteTask);
        unmute();
    }

    @Override
    protected void onResume() {
        super.onResume();
        paused = false;
        if (sessionActive && !stopRequested && !ttsSpeaking && recognizer != null) listen();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unmute();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {
            }
            tts = null;
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            return c != null
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    private void evalJs(final String code) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    // ---------------------------------------------------------------
    // Beep suppression: the phone's recognizer chimes on every start,
    // and continuous listening restarts it after each pause.
    // ---------------------------------------------------------------

    /**
     * Silences the recognizer's chimes. They play on start, on every result and on
     * every "no match", so the streams stay silenced for the whole listening session.
     */
    private void muteNow() {
        if (audio == null) return;
        handler.removeCallbacks(unmuteTask);
        for (int n = 0; n < MUTE_STREAMS.length; n++) {
            if (mutedByUs[n]) continue;
            try {
                if (audio.isStreamMute(MUTE_STREAMS[n])) continue; // user already had it silenced
                audio.adjustStreamVolume(MUTE_STREAMS[n], AudioManager.ADJUST_MUTE, 0);
                mutedByUs[n] = true;
            } catch (Exception ignored) {
                // e.g. notification stream is locked while Do Not Disturb is on
            }
        }
    }

    private void unmuteSoon(long ms) {
        handler.removeCallbacks(unmuteTask);
        handler.postDelayed(unmuteTask, ms);
    }

    private void unmute() {
        if (audio == null) return;
        for (int n = 0; n < MUTE_STREAMS.length; n++) {
            if (!mutedByUs[n]) continue;
            try {
                audio.adjustStreamVolume(MUTE_STREAMS[n], AudioManager.ADJUST_UNMUTE, 0);
            } catch (Exception ignored) {
            }
            mutedByUs[n] = false;
        }
    }

    // ---------------------------------------------------------------
    // Speech recognition bridge (page's webkitSpeechRecognition -> Android)
    // ---------------------------------------------------------------

    private class SpeechBridge {
        @JavascriptInterface
        public void start(final String language, final boolean isContinuous, final boolean wantInterim) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    lang = language == null ? "" : language;
                    continuous = isContinuous;
                    interim = wantInterim;
                    sessionActive = true;
                    stopRequested = false;
                    startedEmitted = false;
                    busyRetries = 0;
                    if (hasMic()) {
                        beginSpeech();
                    } else {
                        pendingSpeech = true;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!sessionActive) return;
                    stopRequested = true;
                    if (recognizer != null) {
                        try {
                            recognizer.stopListening();
                        } catch (Exception ignored) {
                        }
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (sessionActive && stopRequested) endSpeech();
                            }
                        }, 3000);
                    } else {
                        endSpeech();
                    }
                }
            });
        }

        @JavascriptInterface
        public void cancel() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (!sessionActive) return;
                    stopRequested = true;
                    if (recognizer != null) {
                        try {
                            recognizer.cancel();
                        } catch (Exception ignored) {
                        }
                    }
                    endSpeech();
                }
            });
        }
    }

    private void beginSpeech() {
        boolean online = isOnline();
        preferOffline = !online;

        // With no internet, use the phone's on-device recognizer where it exists (Android 12+).
        boolean wantOnDevice = false;
        if (!online && Build.VERSION.SDK_INT >= 31) {
            try {
                wantOnDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
            } catch (Exception ignored) {
            }
        }
        if (!wantOnDevice && !SpeechRecognizer.isRecognitionAvailable(this)) {
            speechError("service-not-allowed");
            endSpeech();
            return;
        }
        if (recognizer == null || wantOnDevice != usingOnDevice) {
            if (recognizer != null) {
                try {
                    recognizer.destroy();
                } catch (Exception ignored) {
                }
                recognizer = null;
            }
            try {
                if (wantOnDevice && Build.VERSION.SDK_INT >= 31) {
                    recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this);
                } else {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                }
            } catch (Exception e) {
                wantOnDevice = false;
                try {
                    recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                } catch (Exception e2) {
                    recognizer = null;
                }
            }
            if (recognizer == null) {
                speechError("service-not-allowed");
                endSpeech();
                return;
            }
            usingOnDevice = wantOnDevice;
            recognizer.setRecognitionListener(listener);
        }
        listen();
    }

    private void listen() {
        if (recognizer == null || paused || ttsSpeaking) return;
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        if (!lang.isEmpty()) {
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        }
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        if (preferOffline) i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        muteNow();
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            unmute();
            speechError("aborted");
            endSpeech();
        }
    }

    private void restartSoon() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sessionActive && !stopRequested && recognizer != null) listen();
            }
        }, 250);
    }

    private void endSpeech() {
        unmuteSoon(900); // after the recognizer's closing chime
        if (!sessionActive) return;
        sessionActive = false;
        startedEmitted = false;
        emitSpeech("end", null);
    }

    private void speechError(String code) {
        try {
            JSONObject o = new JSONObject();
            o.put("error", code);
            emitSpeech("error", o);
        } catch (Exception ignored) {
        }
    }

    private void emitResult(Bundle b, boolean isFinal) {
        if (b == null) return;
        ArrayList<String> texts = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (texts == null || texts.isEmpty()) return;
        if (texts.get(0) == null || texts.get(0).isEmpty()) return;
        float[] conf = b.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES);
        try {
            JSONArray alts = new JSONArray();
            for (int n = 0; n < texts.size(); n++) {
                JSONObject a = new JSONObject();
                a.put("t", texts.get(n));
                double c = (conf != null && n < conf.length && conf[n] >= 0) ? conf[n] : (isFinal ? 0.9 : 0.5);
                a.put("c", c);
                alts.put(a);
            }
            JSONObject o = new JSONObject();
            o.put("final", isFinal);
            o.put("alts", alts);
            emitSpeech("result", o);
        } catch (Exception ignored) {
        }
    }

    private void emitSpeech(String type, JSONObject data) {
        evalJs("window.__gvSpeech&&window.__gvSpeech('" + type + "',"
                + (data == null ? "null" : data.toString()) + ")");
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            if (sessionActive && !startedEmitted) {
                startedEmitted = true;
                emitSpeech("start", null);
            }
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (sessionActive && interim) emitResult(partialResults, false);
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        @Override
        public void onResults(Bundle results) {
            if (!sessionActive) return;
            busyRetries = 0;
            emitResult(results, true);
            if (continuous && !stopRequested) {
                restartSoon();
            } else {
                endSpeech();
            }
        }

        @Override
        public void onError(int error) {
            if (!sessionActive) return;
            if (stopRequested) {
                endSpeech();
                return;
            }
            boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
            if (continuous && silence) {
                restartSoon();
                return;
            }
            if (continuous && error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && busyRetries < 5) {
                busyRetries++;
                restartSoon();
                return;
            }
            String code;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    code = "no-speech";
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    code = "audio-capture";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    code = "not-allowed";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                    code = "network";
                    break;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
                case 13: // ERROR_LANGUAGE_UNAVAILABLE
                    code = "language-not-supported";
                    break;
                default:
                    code = preferOffline ? "network" : "aborted";
            }
            speechError(code);
            endSpeech();
        }
    };

    // ---------------------------------------------------------------
    // Text-to-speech bridge (page's speechSynthesis -> Android voices,
    // which work offline when the voice is installed on the phone)
    // ---------------------------------------------------------------

    private class TtsBridge {
        @JavascriptInterface
        public void speak(final String text, final String language, final double rate, final String id) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (tts == null || !ttsReady || text == null || text.isEmpty()) {
                        emitTts("error", id);
                        return;
                    }
                    try {
                        if (language != null && !language.isEmpty()) {
                            int r = tts.setLanguage(Locale.forLanguageTag(language));
                            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                                emitTts("error", id);
                                return;
                            }
                        }
                        tts.setSpeechRate(rate > 0 ? (float) rate : 1f);
                        ttsSpeaking = true;
                        if (sessionActive && recognizer != null) {
                            try {
                                recognizer.cancel(); // mic off so it can't hear (or chime over) the voice
                            } catch (Exception ignored) {
                            }
                        }
                        handler.removeCallbacks(unmuteTask);
                        unmute();
                        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                            ttsFinished();
                            emitTts("error", id);
                        }
                    } catch (Exception e) {
                        ttsFinished();
                        emitTts("error", id);
                    }
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    if (tts != null && ttsReady) {
                        try {
                            tts.stop();
                        } catch (Exception ignored) {
                        }
                    }
                    ttsFinished();
                }
            });
        }
    }

    /** Voice finished (any thread): go back to listening if a session is still on. */
    private void ttsFinished() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!ttsSpeaking) return;
                ttsSpeaking = false;
                if (sessionActive && !stopRequested && recognizer != null) listen();
            }
        });
    }

    private void emitTts(String type, String id) {
        evalJs("window.__gvTTS&&window.__gvTTS('" + type + "'," + JSONObject.quote(id == null ? "" : id) + ")");
    }

    private final UtteranceProgressListener ttsListener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            emitTts("start", utteranceId);
        }

        @Override
        public void onDone(String utteranceId) {
            ttsFinished();
            emitTts("end", utteranceId);
        }

        @Override
        public void onError(String utteranceId) {
            ttsFinished();
            emitTts("error", utteranceId);
        }

        @Override
        public void onStop(String utteranceId, boolean interrupted) {
            ttsFinished();
        }
    };

    // ---------------------------------------------------------------
    // Offline pack prompts: tells the page which of the phone's offline
    // packs are present (Punjabi listening, Punjabi + English voices)
    // and opens the right download screen for the missing ones.
    // ---------------------------------------------------------------

    private static final String SPEECH_LANG = "pa-IN";

    private class SetupBridge {
        @JavascriptInterface
        public void check() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    setupTries = 0;
                    runSetupCheck();
                }
            });
        }

        @JavascriptInterface
        public void get(final String what) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    runSetupGet(what);
                }
            });
        }
    }

    private Intent speechIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, SPEECH_LANG);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        return i;
    }

    private boolean onDeviceSpeech() {
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            return SpeechRecognizer.isOnDeviceRecognitionAvailable(this);
        } catch (Throwable t) {
            return false;
        }
    }

    /** "ready" | "missing" | "unsupported" for an offline voice in the given language. */
    private String voiceStatus(String language) {
        if (tts == null || !ttsReady) return "unsupported";
        try {
            int a = tts.isLanguageAvailable(new Locale(language, "IN"));
            if (a == TextToSpeech.LANG_NOT_SUPPORTED) return "unsupported";
            if (a == TextToSpeech.LANG_MISSING_DATA) return "missing";
            Set<Voice> voices = tts.getVoices();
            if (voices == null || voices.isEmpty()) return "ready";
            for (Voice v : voices) {
                if (v == null || v.getLocale() == null) continue;
                if (!language.equals(v.getLocale().getLanguage())) continue;
                if (v.isNetworkConnectionRequired()) continue;
                Set<String> f = v.getFeatures();
                if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                return "ready";
            }
            return "missing";
        } catch (Throwable t) {
            return "missing";
        }
    }

    private void runSetupCheck() {
        // wait briefly for the voice engine to finish starting
        if (!ttsInitDone && setupTries < 10) {
            setupTries++;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    runSetupCheck();
                }
            }, 500);
            return;
        }
        final JSONObject st = new JSONObject();
        try {
            st.put("pa", voiceStatus("pa"));
            st.put("en", voiceStatus("en"));
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= 33 && onDeviceSpeech()) {
            try {
                Api33.check(this, speechIntent(), new Api33.Result() {
                    private boolean sent;

                    @Override
                    public void done(String status) {
                        if (sent) return;
                        sent = true;
                        sendSetup(st, status);
                    }
                }, handler);
                return;
            } catch (Throwable ignored) {
            }
        }
        sendSetup(st, "unknown");
    }

    private void sendSetup(JSONObject st, String speech) {
        try {
            st.put("sr", speech);
        } catch (Exception ignored) {
        }
        evalJs("window.__gvSetup&&window.__gvSetup(" + st.toString() + ")");
    }

    private void runSetupGet(String what) {
        if ("engine".equals(what)) {
            // No speech recognizer on the phone at all: send the user to the Google app in the Play Store.
            Toast.makeText(this, "Install or update the Google app, then come back", Toast.LENGTH_LONG).show();
            if (!tryStart(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.googlequicksearchbox")))) {
                tryStart(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox")));
            }
            return;
        }
        if ("sr".equals(what)) {
            if (Build.VERSION.SDK_INT >= 33 && onDeviceSpeech()) {
                try {
                    Api33.download(this, speechIntent(), handler);
                    Toast.makeText(this, "Punjabi speech pack download requested", Toast.LENGTH_LONG).show();
                    return;
                } catch (Throwable ignored) {
                }
            }
            Toast.makeText(this,
                    "Google app \u2192 Settings \u2192 Voice \u2192 Offline speech recognition \u2192 add Punjabi",
                    Toast.LENGTH_LONG).show();
            if (!tryStart(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) {
                tryStart(new Intent(Settings.ACTION_SETTINGS));
            }
            return;
        }
        // Punjabi / English voice data
        Toast.makeText(this,
                "pa".equals(what) ? "Download the Punjabi (India) voice" : "Download the English (India) voice",
                Toast.LENGTH_LONG).show();
        if (!tryStart(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))) {
            if (!tryStart(new Intent("com.android.settings.TTS_SETTINGS"))) {
                tryStart(new Intent(Settings.ACTION_SETTINGS));
            }
        }
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Android 13+ only calls, kept in their own class so older phones never load them. */
    private static class Api33 {
        interface Result {
            void done(String status);
        }

        private static boolean hasPunjabi(List<String> tags) {
            if (tags == null) return false;
            for (String t : tags) {
                if (t != null && t.toLowerCase(Locale.ROOT).startsWith("pa")) return true;
            }
            return false;
        }

        static void check(Context ctx, Intent intent, final Result result, Handler handler) {
            final SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
            final Runnable timeout = new Runnable() {
                @Override
                public void run() {
                    result.done("unknown");
                    try {
                        r.destroy();
                    } catch (Exception ignored) {
                    }
                }
            };
            handler.postDelayed(timeout, 4000);
            r.checkRecognitionSupport(intent, ctx.getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport s) {
                    String status;
                    if (hasPunjabi(s.getInstalledOnDeviceLanguages())) status = "ready";
                    else if (hasPunjabi(s.getSupportedOnDeviceLanguages())
                            || hasPunjabi(s.getPendingOnDeviceLanguages())) status = "missing";
                    else status = "unsupported";
                    finish(status);
                }

                @Override
                public void onError(int error) {
                    finish("unknown");
                }

                private void finish(String status) {
                    handler.removeCallbacks(timeout);
                    result.done(status);
                    try {
                        r.destroy();
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        static void download(Context ctx, Intent intent, Handler handler) {
            final SpeechRecognizer r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
            r.triggerModelDownload(intent);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        r.destroy();
                    } catch (Exception ignored) {
                    }
                }
            }, 120000);
        }
    }

    // Injected before the page's own script: Web Speech API (recognition + synthesis)
    // replacements backed by the two bridges above.
    private static final String POLYFILL =
            "(function(){"
                    + "if(window.__gvSpeech)return;"
                    // ---- recognition ----
                    + "if(window.AndroidSpeech){"
                    + "var active=null;"
                    + "var EV=['start','end','result','error','audiostart','audioend','speechstart','speechend','nomatch','soundstart','soundend'];"
                    + "function SR(){this.lang='';this.continuous=false;this.interimResults=false;this.maxAlternatives=1;"
                    + "this._l={};this._running=false;this._finals=[];"
                    + "for(var i=0;i<EV.length;i++)this['on'+EV[i]]=null;}"
                    + "SR.prototype.addEventListener=function(t,f){(this._l[t]=this._l[t]||[]).push(f);};"
                    + "SR.prototype.removeEventListener=function(t,f){var a=this._l[t]||[];var i=a.indexOf(f);if(i>=0)a.splice(i,1);};"
                    + "SR.prototype.dispatchEvent=function(){return true;};"
                    + "SR.prototype._emit=function(t,p){var e={type:t,target:this,currentTarget:this,timeStamp:Date.now()};"
                    + "if(p)for(var k in p)e[k]=p[k];"
                    + "var h=this['on'+t];if(typeof h==='function'){try{h.call(this,e);}catch(x){console.error(x);}}"
                    + "var a=(this._l[t]||[]).slice();for(var i=0;i<a.length;i++){try{a[i].call(this,e);}catch(x){console.error(x);}}};"
                    + "SR.prototype.start=function(){"
                    + "if(this._running)throw new DOMException('recognition has already started','InvalidStateError');"
                    + "if(active&&active!==this&&active._running){try{AndroidSpeech.cancel();}catch(x){}active._running=false;}"
                    + "active=this;this._running=true;this._finals=[];"
                    + "AndroidSpeech.start(this.lang||document.documentElement.lang||navigator.language||'',!!this.continuous,!!this.interimResults);};"
                    + "SR.prototype.stop=function(){if(this._running&&active===this)AndroidSpeech.stop();};"
                    + "SR.prototype.abort=function(){if(this._running&&active===this)AndroidSpeech.cancel();};"
                    + "function item(i){return this[i];}"
                    + "window.__gvSpeech=function(type,d){var r=active;if(!r)return;"
                    + "if(type==='start'){r._emit('start');r._emit('audiostart');return;}"
                    + "if(type==='result'){if(!r._running)return;var res=[];"
                    + "for(var i=0;i<d.alts.length;i++)res.push({transcript:d.alts[i].t,confidence:d.alts[i].c});"
                    + "res.isFinal=!!d.final;res.item=item;"
                    + "if(r._finals.length>40)r._finals=r._finals.slice(-10);"
                    + "var list=r._finals.concat([res]);list.item=item;var idx=r._finals.length;"
                    + "if(d.final)r._finals.push(res);"
                    + "r._emit('result',{results:list,resultIndex:idx});return;}"
                    + "if(type==='error'){r._emit('error',{error:d.error,message:d.error});return;}"
                    + "if(type==='end'){if(!r._running)return;r._running=false;r._emit('audioend');r._emit('end');}};"
                    + "window.SpeechRecognition=SR;window.webkitSpeechRecognition=SR;"
                    + "}else{window.__gvSpeech=function(){};}"
                    // ---- synthesis ----
                    + "if(window.AndroidTTS){"
                    + "var cur=null,seq=0;"
                    + "function U(t){this.text=t||'';this.lang='';this.rate=1;this.pitch=1;this.volume=1;"
                    + "this.onstart=null;this.onend=null;this.onerror=null;}"
                    + "var synth={speaking:false,pending:false,paused:false,"
                    + "speak:function(u){cur=u;u._id='u'+(++seq);synth.speaking=true;"
                    + "AndroidTTS.speak(String(u.text||''),String(u.lang||''),+u.rate||1,u._id);},"
                    + "cancel:function(){cur=null;synth.speaking=false;AndroidTTS.stop();},"
                    + "pause:function(){},resume:function(){},getVoices:function(){return [];},"
                    + "addEventListener:function(){},removeEventListener:function(){}};"
                    + "window.__gvTTS=function(type,id){var u=cur;if(!u||u._id!==id)return;"
                    + "if(type==='start'){if(typeof u.onstart==='function')u.onstart({type:'start',utterance:u});return;}"
                    + "cur=null;synth.speaking=false;"
                    + "var h=type==='error'?u.onerror:u.onend;"
                    + "if(typeof h==='function')h({type:type,utterance:u,error:'synthesis-failed'});};"
                    + "try{Object.defineProperty(window,'speechSynthesis',{value:synth,configurable:true,writable:true});}"
                    + "catch(e){try{window.speechSynthesis=synth;}catch(e2){}}"
                    + "window.SpeechSynthesisUtterance=U;"
                    + "}"
                    + "})();";
}
