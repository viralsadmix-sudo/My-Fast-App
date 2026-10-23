package com.sabbir.rumi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private var continuousVoice = false
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        // Allow the bundled HTML UI to call the HTTPS AI APIs from its local asset origin.
        @Suppress("DEPRECATION")
        webView.settings.allowUniversalAccessFromFileURLs = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.setBackgroundColor(android.graphics.Color.rgb(11, 7, 22))
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url.isNullOrBlank()) return false
                if (url.startsWith("file:///android_asset/")) return false
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    true
                } catch (_: Exception) { false }
            }
        }
        webView.addJavascriptInterface(AndroidTtsBridge(), "AndroidTTS")
        webView.addJavascriptInterface(AndroidVoiceBridge(), "AndroidVoice")
        webView.addJavascriptInterface(AndroidActionsBridge(), "AndroidActions")
        webView.loadUrl("file:///android_asset/index.html")
        tts = TextToSpeech(this, this, "com.google.android.tts")
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            val languageResult = tts.setLanguage(Locale("bn", "BD"))
            tts.setPitch(1.18f)
            tts.setSpeechRate(0.94f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.onAndroidSpeechDone && window.onAndroidSpeechDone()", null)
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        webView.evaluateJavascript("window.onAndroidSpeechDone && window.onAndroidSpeechDone()", null)
                    }
                }
            })
            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Toast.makeText(this, "বাংলা TTS voice ইনস্টল করুন", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startNativeListening() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            sendSpeechError("এই ফোনে Speech Recognition পাওয়া যায়নি")
            return
        }
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = setStatus("শুনছি… বলুন")
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() = setStatus("উত্তর তৈরি হচ্ছে…")
                    override fun onError(error: Int) {
                        if (continuousVoice) {
                            sendSpeechError("আবার বলুন")
                            webView.postDelayed({ if (continuousVoice) startNativeListening() }, 700)
                        } else sendSpeechError("ভয়েস শোনা যায়নি, আবার চেষ্টা করুন")
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            val quoted = JSONObject.quote(text)
                            webView.evaluateJavascript("window.onAndroidSpeechResult && window.onAndroidSpeechResult($quoted)", null)
                        } else if (continuousVoice) {
                            webView.postDelayed({ startNativeListening() }, 400)
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) {
            sendSpeechError("মাইক্রোফোন চালু করা যায়নি")
        }
    }

    private fun setStatus(message: String) {
        val quoted = JSONObject.quote(message)
        runOnUiThread { webView.evaluateJavascript("window.setRumiStatus && window.setRumiStatus($quoted)", null) }
    }

    private fun sendSpeechError(message: String) {
        val quoted = JSONObject.quote(message)
        runOnUiThread { webView.evaluateJavascript("window.onAndroidSpeechError && window.onAndroidSpeechError($quoted)", null) }
    }

    inner class AndroidTtsBridge {
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                if (ttsReady) tts.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "RUMI_WEB_TTS")
                else sendSpeechError("Text-to-Speech প্রস্তুত নয়")
            }
        }
    }

    inner class AndroidVoiceBridge {
        @JavascriptInterface
        fun listen() {
            runOnUiThread {
                continuousVoice = true
                startNativeListening()
            }
        }
        @JavascriptInterface
        fun stop() {
            runOnUiThread {
                continuousVoice = false
                try { speechRecognizer?.cancel() } catch (_: Exception) {}
                setStatus("ভয়েস বন্ধ আছে")
            }
        }
    }

    inner class AndroidActionsBridge {
        @JavascriptInterface
        fun open(action: String) {
            runOnUiThread {
                try {
                    when (action.lowercase(Locale.ROOT)) {
                        "facebook" -> openApp("com.facebook.katana", "https://m.facebook.com")
                        "youtube" -> openApp("com.google.android.youtube", "https://m.youtube.com")
                        "google" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")))
                        "chatgpt" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com")))
                        "dial" -> startActivity(Intent(Intent.ACTION_DIAL))
                        "alarm" -> startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
                        else -> Toast.makeText(this@MainActivity, "এই অপশনটি পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(this@MainActivity, "অপশনটি চালু করা যায়নি", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openApp(packageName: String, fallbackUrl: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) startActivity(intent)
        else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        continuousVoice = false
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("AndroidTTS")
            webView.removeJavascriptInterface("AndroidVoice")
            webView.removeJavascriptInterface("AndroidActions")
            webView.destroy()
        }
        super.onDestroy()
    }
}
