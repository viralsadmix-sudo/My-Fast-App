package com.sabbir.rumi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var tvUserCommand: TextView
    private lateinit var tvRumiReply: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var etModelName: EditText
    private lateinit var btnSpeak: Button
    private lateinit var webViewAvatar: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isContinuousMode = false
    @Volatile private var isRumiSpeaking = false
    @Volatile private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvUserCommand = findViewById(R.id.tvUserCommand)
        tvRumiReply = findViewById(R.id.tvRumiReply)
        tvStatus = findViewById(R.id.tvStatus)
        etApiKey = findViewById(R.id.etApiKey)
        etModelName = findViewById(R.id.etModelName)
        val btnSaveKey: Button = findViewById(R.id.btnSaveKey)
        btnSpeak = findViewById(R.id.btnSpeak)
        webViewAvatar = findViewById(R.id.webViewAvatar)
        webViewAvatar.settings.javaScriptEnabled = true
        webViewAvatar.settings.allowFileAccess = true
        webViewAvatar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webViewAvatar.loadUrl("file:///android_asset/rumi_animation.html")

        val prefs = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))
        etModelName.setText(prefs.getString("MODEL_NAME", "gemini-2.5-flash"))
        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().replace("\\s".toRegex(), "").trim()
            val model = etModelName.text.toString().trim().removePrefix("models/")
            if (key.isBlank()) { etApiKey.error = "Gemini API Key দিন"; return@setOnClickListener }
            if (model.isBlank() || model.contains(" ")) { etModelName.error = "সঠিক মডেল আইডি দিন"; return@setOnClickListener }
            prefs.edit().putString("API_KEY", key).putString("MODEL_NAME", model).apply()
            Toast.makeText(this, "API Key ও মডেল সেভ হয়েছে", Toast.LENGTH_SHORT).show()
            rumiSpeak("ধন্যবাদ! API Key সেভ হয়েছে।")
        }
        tts = TextToSpeech(this, this)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }
        setupSpeechRecognizer()
        btnSpeak.setOnClickListener {
            if (isContinuousMode) stopContinuousMode() else {
                isContinuousMode = true
                btnSpeak.text = "🛑 কথা বলা বন্ধ করুন"
                btnSpeak.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(255, 23, 68))
                startListening()
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (!ttsReady) { tvStatus.text = "Text-to-Speech চালু হয়নি"; return }
        val lang = tts.setLanguage(Locale("bn", "BD"))
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale("bn", "IN"))
            tvStatus.text = "বাংলা ভয়েসের জন্য Google Speech Services-এ Bengali voice ইনস্টল করুন"
        }
        tts.setPitch(1.22f)
        tts.setSpeechRate(0.94f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isRumiSpeaking = true }
            override fun onDone(utteranceId: String?) { isRumiSpeaking = false; resumeListening() }
            override fun onError(utteranceId: String?) { isRumiSpeaking = false; resumeListening() }
        })
        rumiSpeak("আসসালামু আলাইকুম! আমি রুমি। বলুন, আমি শুনছি।")
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { tvStatus.text = "এই ফোনে Speech Recognition পাওয়া যায়নি"; return }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (isContinuousMode) tvStatus.text = "🟢 রুমি শুনছে... বলুন" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (isContinuousMode) tvStatus.text = "রুমি চিন্তা করছে..." }
            override fun onError(error: Int) {
                if (isContinuousMode && !isRumiSpeaking) mainHandler.postDelayed({ startListening() }, 700)
                else if (!isContinuousMode) tvStatus.text = "ভয়েস শোনা যায়নি, আবার চেষ্টা করুন"
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) {
                    tvUserCommand.text = "আপনি: $text"
                    processCommand(text)
                } else if (isContinuousMode) resumeListening()
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (!isContinuousMode || isRumiSpeaking) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100); return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            speechRecognizer?.startListening(intent)
        } catch (_: Exception) { tvStatus.text = "মাইক্রোফোন চালু করা যায়নি" }
    }

    private fun resumeListening() {
        mainHandler.postDelayed({ if (isContinuousMode && !isRumiSpeaking) startListening() }, 500)
    }

    private fun stopContinuousMode() {
        isContinuousMode = false
        isRumiSpeaking = false
        try { speechRecognizer?.cancel(); tts.stop() } catch (_: Exception) {}
        btnSpeak.text = "📞 রুমির সাথে লাইভ কথা শুরু করুন"
        btnSpeak.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.rgb(255, 46, 147))
        tvStatus.text = "লাইভ কথা বন্ধ আছে"
    }

    private fun rumiSpeak(text: String) {
        runOnUiThread {
            tvRumiReply.text = "রুমি: $text"
            tvStatus.text = "🗣️ রুমি কথা বলছে..."
            if (!ttsReady) { tvStatus.text = "ভয়েস ইঞ্জিন প্রস্তুত হচ্ছে"; return@runOnUiThread }
            isRumiSpeaking = true
            try { speechRecognizer?.cancel() } catch (_: Exception) {}
            tts.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "RUMI_SPEAK_ID")
        }
    }

    private fun processCommand(raw: String) {
        val command = raw.lowercase(Locale("bn", "BD"))
        if (command.contains("কথা বন্ধ কর") || command.contains("থামো")) { stopContinuousMode(); return }
        if (command.contains("ফেসবুক") && listOf("চালাও", "ওপেন", "খোলো").any { command.contains(it) }) {
            rumiSpeak("আমি ফেসবুক খুলছি।")
            mainHandler.postDelayed({
                val launch = packageManager.getLaunchIntentForPackage("com.facebook.katana")
                try { if (launch != null) startActivity(launch) else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.facebook.com"))) } catch (_: Exception) {}
            }, 900); return
        }
        if (command.contains("ইউটিউব") && listOf("চালাও", "ওপেন", "খোলো").any { command.contains(it) }) {
            rumiSpeak("আমি ইউটিউব খুলছি।")
            mainHandler.postDelayed({
                val launch = packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                try { if (launch != null) startActivity(launch) else startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com"))) } catch (_: Exception) {}
            }, 900); return
        }
        askRumiAI(raw)
    }

    private fun askRumiAI(userQuestion: String) {
        val prefs = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("API_KEY", "") ?: ""
        val currentBoxKey = etApiKey.text.toString().replace("\\s".toRegex(), "").trim()
        val apiKey = if (currentBoxKey.isNotEmpty()) currentBoxKey else savedKey

        if (apiKey.isBlank()) {
            rumiSpeak("দয়া করে আপনার জেমিনি এপিআই কি দিন।")
            return
        }

        tvStatus.text = "রুমি উত্তর তৈরি করছে..."
        thread {
            var conn: HttpURLConnection? = null
            try {
                // বর্তমান সচল Gemini Flash মডেল
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${modelName}:generateContent")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    // Google AI Studio-এর AQ. authorization API key API-key header দিয়ে পাঠাতে হয়
                    setRequestProperty("x-goog-api-key", apiKey)
                    connectTimeout = 20000
                    readTimeout = 30000
                    doOutput = true
                }

                val prompt = "তোমার নাম রুমি। তুমি এই অ্যাপের অ্যানিমেটেড এভাটার এবং মিষ্টি মেয়ে ভয়েস অ্যাসিস্ট্যান্ট। নিচের কথার উত্তর খুব সহজ ও সুন্দর বাংলায় ১ থেকে ২ বাক্যে দাও। কোনো স্টার চিহ্ন দিবে না। প্রশ্ন: $userQuestion"
                val requestBody = JSONObject().put(
                    "contents",
                    JSONArray().put(
                        JSONObject().put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt))
                        )
                    )
                )

                conn.outputStream.use { output ->
                    output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (code in 200..299) {
                    val jsonResponse = JSONObject(responseText)
                    val candidates = jsonResponse.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val aiReply = parts?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.optString("text")?.takeIf { it.isNotBlank() }
                        }.joinToString(" ")
                    }?.replace("*", "")?.replace("#", "")?.replace("\\n", " ")?.trim().orEmpty()

                    if (aiReply.isNotBlank()) {
                        rumiSpeak(aiReply)
                    } else {
                        rumiSpeak("দুঃখিত, এবার উত্তর তৈরি করতে পারিনি। আবার বলুন।")
                    }
                } else {
                    val message = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) { null }
                    val safeMessage = message?.takeIf { it.isNotBlank() }?.take(180)
                    runOnUiThread {
                        tvRumiReply.text = "Gemini API Error ($code): ${safeMessage ?: "API Key বা মডেল যাচাই করুন"}"
                    }
                    rumiSpeak(
                        if (code == 401 || code == 403) "জেমিনি এপিআই কি সঠিক নয় অথবা অনুমতি পাওয়া যায়নি।"
                        else "জেমিনি সার্ভারে সমস্যা হয়েছে। একটু পরে আবার চেষ্টা করুন।"
                    )
                }
            } catch (_: Exception) {
                rumiSpeak("ইন্টারনেট সংযোগে সমস্যা হয়েছে।")
            } finally {
                conn?.disconnect()
            }
        }
    }

    override fun onDestroy() {
        isContinuousMode = false
        try { speechRecognizer?.destroy(); tts.stop(); tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
