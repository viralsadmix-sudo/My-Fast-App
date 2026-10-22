package com.ruminai.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.webkit.JavascriptInterface
import android.webkit.*
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var pendingRequest: PermissionRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    inner class AndroidTTSBridge {
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                if (ttsReady) {
                    val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rumin-" + System.currentTimeMillis())
                    if (result == TextToSpeech.ERROR) Toast.makeText(this@MainActivity, "ভয়েস চালু হয়নি। ফোনের Text-to-Speech সেটিংস দেখুন।", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "ভয়েস প্রস্তুত হচ্ছে, একটু পরে আবার চাপুন।", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingRequest
        if (granted) request?.grant(request.resources)
        else {
            request?.deny()
            Toast.makeText(this, "Microphone permission দরকার", Toast.LENGTH_LONG).show()
        }
        pendingRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val bangla = tts?.setLanguage(Locale("bn", "BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                ttsReady = bangla != TextToSpeech.LANG_MISSING_DATA && bangla != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    val fallback = tts?.setLanguage(Locale("bn", "IN")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ttsReady = fallback != TextToSpeech.LANG_MISSING_DATA && fallback != TextToSpeech.LANG_NOT_SUPPORTED
                }
                if (!ttsReady) runOnUiThread {
                    Toast.makeText(this, "বাংলা ভয়েস ডেটা নেই। Google Speech Services-এ Bengali voice ইনস্টল করুন।", Toast.LENGTH_LONG).show()
                }
            }
        }
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(AndroidTTSBridge(), "AndroidTTS")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingRequest = request
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else request.grant(request.resources)
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}