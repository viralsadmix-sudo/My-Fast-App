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
        val savedModel = prefs.getString("MODEL_NAME", "gemini-3.8-flash") ?: "gemini-3.8-flash"
        val initialModel = if (savedModel == "gemini-2.5-flash" || savedModel == "models/gemini-2.5-flash") "gemini-3.8-flash" else savedModel.removePrefix("models/")
        if (initialModel != savedModel) prefs.edit().putString("MODEL_NAME", initialModel).apply()
        etModelName.setText(initialModel)
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
        val openWords = listOf("চালাও", "ওপেন", "খোলো", "খুলে দাও", "open")
        if (command.contains("কথা বন্ধ কর") || command.contains("থামো")) { stopContinuousMode(); return }

        if (command.contains("ব্যাটারি") || command.contains("চার্জ কত")) {
            val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = battery?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL
            if (percent >= 0) rumiSpeak("আপনার ফোনে $percent শতাংশ চার্জ আছে। " + if (charging) "ফোন চার্জ হচ্ছে।" else "ফোন এখন চার্জ হচ্ছে না.")
            else rumiSpeak("দুঃখিত, ব্যাটারির তথ্য পড়তে পারিনি।")
            return
        }
        if (command.contains("অ্যালার্ম") || command.contains("এলার্ম")) {
            rumiSpeak("অ্যালার্ম সেট করার স্ক্রিন খুলছি।")
            mainHandler.postDelayed({
                try { startActivity(Intent(android.provider.AlarmClock.ACTION_SET_ALARM).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Rumi AI")) }
                catch (_: Exception) { rumiSpeak("এই ফোনে অ্যালার্ম অ্যাপ পাওয়া যায়নি।") }
            }, 650)
            return
        }
        if ((command.contains("টর্চ") || command.contains("ফ্ল্যাশলাইট")) && listOf("চালু", "জ্বালাও", "অন").any { command.contains(it) }) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 102)
                rumiSpeak("টর্চ চালাতে ক্যামেরার অনুমতি দিন, তারপর আবার বলুন।")
            } else toggleTorch(true)
            return
        }
        if ((command.contains("টর্চ") || command.contains("ফ্ল্যাশলাইট")) && listOf("বন্ধ", "নিভাও", "অফ").any { command.contains(it) }) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { rumiSpeak("টর্চ এখন বন্ধ আছে।"); return }
            toggleTorch(false); return
        }
        if (command.contains("হোয়াটসঅ্যাপ") || command.contains("হোয়াটসঅ্যাপ") || command.contains("whatsapp")) {
            openApp("com.whatsapp", "https://www.whatsapp.com/download/", "আমি হোয়াটসঅ্যাপ খুলছি।"); return
        }
        if (command.contains("ফেসবুক") && openWords.any { command.contains(it) }) {
            openApp("com.facebook.katana", "https://m.facebook.com", "আমি ফেসবুক খুলছি।"); return
        }
        if (command.contains("ইউটিউব") && openWords.any { command.contains(it) }) {
            openApp("com.google.android.youtube", "https://m.youtube.com", "আমি ইউটিউব খুলছি।"); return
        }
        if (command.contains("গুগল") && openWords.any { command.contains(it) }) {
            openApp(null, "https://www.google.com", "আমি গুগল খুলছি।"); return
        }
        if (command.contains("হোম স্ক্রিন") || command.contains("হোমে যাও")) {
            val service = RumiControlService.instance
            if (service != null) { service.goHome(); rumiSpeak("হোম স্ক্রিনে যাচ্ছি।") }
            else rumiSpeak("ফোনের সেটিংসে গিয়ে Rumi Control Accessibility চালু করলে হোম স্ক্রিন নিয়ন্ত্রণ করতে পারব।")
            return
        }
        askRumiAI(raw)
    }

    private fun openApp(packageName: String?, fallbackUrl: String, spoken: String) {
        rumiSpeak(spoken)
        mainHandler.postDelayed({
            try {
                val launch = packageName?.let { packageManager.getLaunchIntentForPackage(it) }
                startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
            } catch (_: Exception) { runOnUiThread { tvStatus.text = "অ্যাপটি খোলা যায়নি" } }
        }, 650)
    }

    private fun toggleTorch(enabled: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId == null) { rumiSpeak("এই ফোনে টর্চ পাওয়া যায়নি।"); return }
            cameraManager.setTorchMode(cameraId, enabled)
            rumiSpeak(if (enabled) "টর্চ চালু করেছি।" else "টর্চ বন্ধ করেছি।")
        } catch (_: Exception) { rumiSpeak("টর্চ চালু করা যায়নি। ক্যামেরার অনুমতি এবং অন্য অ্যাপের ক্যামেরা ব্যবহার পরীক্ষা করুন।") }
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
            var requestStage = "HTTPS সংযোগ তৈরি"
            try {
                // মডেল আইডি সেটিংস থেকে আবার পড়ি, যাতে request scope-এ নিশ্চিতভাবে থাকে
                val selectedModel = (prefs.getString("MODEL_NAME", "gemini-3.8-flash") ?: "gemini-3.8-flash").trim().removePrefix("models/")
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${selectedModel}:generateContent")
                conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Connection", "close")
                    setRequestProperty("x-goog-api-key", apiKey)
                    connectTimeout = 45000
                    readTimeout = 45000
                    useCaches = false
                    doInput = true
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

                requestStage = "Gemini-তে অনুরোধ পাঠানো"
                conn.outputStream.use { output ->
                    output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                    output.flush()
                }

                requestStage = "Gemini-এর উত্তর অপেক্ষা"
                val code = conn.responseCode
                requestStage = "Gemini-এর উত্তর পড়া"
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
                    val detail = "Gemini API Error ($code): ${safeMessage ?: "API Key বা মডেল যাচাই করুন"}"
                    rumiSpeak(
                        when (code) {
                            400 -> "অনুরোধ বা মডেলের নাম ঠিক নেই। স্ক্রিনে দেখানো ত্রুটি দেখুন।"
                            401, 403 -> "জেমিনি API Key বা অনুমতির সমস্যা। স্ক্রিনে বিস্তারিত দেখুন।"
                            404 -> "এই Gemini মডেলটি পাওয়া যায়নি। মডেলের নাম যাচাই করুন।"
                            429 -> "API ব্যবহারের সীমা শেষ হয়েছে। কিছুক্ষণ পরে চেষ্টা করুন।"
                            else -> "Gemini API অনুরোধ ব্যর্থ হয়েছে। স্ক্রিনে বিস্তারিত দেখুন।"
                        }
                    )
                    runOnUiThread { tvRumiReply.text = detail }
                }
            } catch (e: Exception) {
                val reason = (e.message ?: e.javaClass.simpleName).take(220)
                runOnUiThread {
                    tvRumiReply.text = "সংযোগের ত্রুটি [${requestStage}]: ${e.javaClass.simpleName}: $reason"
                    tvStatus.text = "অনুরোধ ব্যর্থ—উপরের ত্রুটিটি দেখুন"
                }
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