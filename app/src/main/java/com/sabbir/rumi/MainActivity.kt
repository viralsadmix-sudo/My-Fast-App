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
        val btnSaveKey: Button = findViewById(R.id.btnSaveKey)
        btnSpeak = findViewById(R.id.btnSpeak)
        webViewAvatar = findViewById(R.id.webViewAvatar)
        webViewAvatar.settings.javaScriptEnabled = true
        webViewAvatar.settings.allowFileAccess = true
        webViewAvatar.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        webViewAvatar.loadUrl("file:///android_asset/rumi_animation.html")

        val prefs = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("API_KEY", ""))
        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isBlank()) { etApiKey.error = "Gemini API Key দিন"; return@setOnClickListener }
            prefs.edit().putString("API_KEY", key).apply()
            Toast.makeText(this, "API Key সেভ হয়েছে", Toast.LENGTH_SHORT).show()
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

    private fun askRumiAI(question: String) {
        val key = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE).getString("API_KEY", "").orEmpty()
        if (key.isBlank()) { rumiSpeak("দয়া করে আগে Gemini API Key সেভ করুন।"); return }
        tvStatus.text = "রুমি উত্তর তৈরি করছে..."
        thread {
            var conn: HttpURLConnection? = null
            try {
                val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=" + java.net.URLEncoder.encode(key, "UTF-8"))
                conn = (endpoint.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connectTimeout = 20000; readTimeout = 20000; doOutput = true
                }
                val prompt = "তোমার নাম রুমি। তুমি বাংলা ভাষায় কথা বলা AI সহকারী। সহজ, স্বাভাবিক বাংলায় ১-৩ বাক্যে উত্তর দাও। ব্যবহারকারীর কথা: $question"
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code in 200..299) {
                    val json = JSONObject(response)
                    val candidates = json.optJSONArray("candidates")
                    val reply = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        ?.let { parts -> (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text") }.joinToString(" ") }
                        ?.replace(Regex("[*#]"), "")?.trim().orEmpty()
                    if (reply.isBlank()) rumiSpeak("দুঃখিত, এবার উত্তর তৈরি করতে পারিনি।") else rumiSpeak(reply)
                } else {
                    val message = try { JSONObject(response).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                    rumiSpeak("Gemini API সমস্যা ($code): " + (message?.take(180) ?: "API Key বা মডেল যাচাই করুন"))
                }
            } catch (e: Exception) {
                rumiSpeak("ইন্টারনেট সংযোগে সমস্যা হয়েছে।")
            } finally { conn?.disconnect() }
        }
    }

    override fun onDestroy() {
        isContinuousMode = false
        try { speechRecognizer?.destroy(); tts.stop(); tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}
