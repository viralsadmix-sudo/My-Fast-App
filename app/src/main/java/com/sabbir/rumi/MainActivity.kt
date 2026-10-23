package com.sabbir.rumi

import android.Manifest
import android.app.SearchManager
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvUserCommand: TextView
    private lateinit var tvRumiReply: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etApiKey: EditText
    private lateinit var btnSpeak: Button
    private lateinit var imgRumiAvatar: ImageView
    private var mediaPlayer: MediaPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isContinuousMode = false
    private var isRumiSpeaking = false

    private var pendingAction: (() -> Unit)? = null
    private var awaitingConfirmation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvUserCommand = findViewById(R.id.tvUserCommand)
        tvRumiReply = findViewById(R.id.tvRumiReply)
        tvStatus = findViewById(R.id.tvStatus)
        etApiKey = findViewById(R.id.etApiKey)

        val btnSaveKey: Button = findViewById(R.id.btnSaveKey)
        btnSpeak = findViewById(R.id.btnSpeak)
        imgRumiAvatar = findViewById(R.id.imgRumiAvatar)
        setupRumiAvatar()
        val btnVoiceSettings: Button = findViewById(R.id.btnVoiceSettings)

        // API Key পুরোনো ও নতুন preference দুটো থেকেই পড়া; নতুন জায়গায় সংরক্ষণ
        val prefs = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE)
        val oldPrefs = getSharedPreferences("rumi_settings", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("API_KEY", null)
            ?: oldPrefs.getString("gemini_api_key", "")
        etApiKey.setText(savedKey)

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "আগে Gemini API Key লিখুন", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putString("API_KEY", key).apply()
                tvStatus.text = "API Key সেভ হয়েছে"
                Toast.makeText(this, "API Key এই ফোনে সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                rumiSpeak("ধন্যবাদ! API Key সেভ হয়েছে। এখন আমাকে প্রশ্ন করতে পারেন।")
            }
        }

        btnVoiceSettings.setOnClickListener {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "ফোনের Settings থেকে Text-to-speech খুঁজুন", Toast.LENGTH_LONG).show()
                }
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        tts = TextToSpeech(this, this, "com.google.android.tts")

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = "রুমি শুনছে..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvStatus.text = "রুমি চিন্তা করছে..."
            }
            override fun onError(error: Int) {
                tvStatus.text = "আবার বলুন"
                if (isContinuousMode && !isRumiSpeaking) {
                    mainHandler.postDelayed({
                        if (isContinuousMode && !isRumiSpeaking) startListening()
                    }, 650)
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val spokenText = matches[0]
                    tvUserCommand.text = "আপনি: $spokenText"
                    processCommand(spokenText.lowercase(Locale.ROOT))
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        btnSpeak.text = "📞 রুমির সাথে লাইভ কথা শুরু করুন"
        btnSpeak.setOnClickListener {
            if (isContinuousMode) {
                stopContinuousMode()
            } else {
                isContinuousMode = true
                btnSpeak.text = "🛑 কথা বলা বন্ধ করুন (Stop)"
                btnSpeak.setBackgroundColor(0xFFD50000.toInt())
                startListening()
            }
        }
    }

    private fun setupRumiAvatar() {
        try {
            assets.open("rumi_avatar.jpg").use { input ->
                val bitmap = BitmapFactory.decodeStream(input)
                if (bitmap != null) imgRumiAvatar.setImageBitmap(bitmap)
            }
        } catch (_: Exception) {
            // rumi_avatar.jpg না থাকলে avatar container কালো থাকবে; অ্যাপ চালু থাকবে।
        }

        val pulseAnim = ScaleAnimation(
            1.0f, 1.05f, 1.0f, 1.05f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1500
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        imgRumiAvatar.startAnimation(pulseAnim)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val languageResult = tts.setLanguage(Locale("bn", "BD"))
            val bnVoices = tts.voices?.filter { it.locale.language == "bn" }.orEmpty()
            val preferredVoice = bnVoices.firstOrNull {
                it.name.contains("female", ignoreCase = true) ||
                    it.name.contains("sfg", ignoreCase = true) ||
                    it.name.contains("ban-local", ignoreCase = true)
            } ?: bnVoices.lastOrNull()
            if (preferredVoice != null) tts.voice = preferredVoice

            tts.setPitch(1.45f)
            tts.setSpeechRate(0.95f)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { isRumiSpeaking = true }
                override fun onDone(utteranceId: String?) {
                    isRumiSpeaking = false
                    resumeListeningIfNeeded()
                }
                override fun onError(utteranceId: String?) {
                    isRumiSpeaking = false
                    resumeListeningIfNeeded()
                }
            })

            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tvStatus.text = "ফোনে বাংলা Text-to-speech voice ইনস্টল করুন"
            }
            rumiSpeak("আসসালামু আলাইকুম! আমি রুমি। আপনার সাথে কথা বলতে প্রস্তুত।")
        } else {
            tvStatus.text = "Text-to-speech চালু করা যায়নি"
        }
    }

    private fun startListening() {
        if (!isContinuousMode) return
        if (isRumiSpeaking) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            rumiSpeak("এই ফোনে Speech Recognition পাওয়া যাচ্ছে না।")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        try { speechRecognizer.cancel() } catch (_: Exception) {}
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try {
            speechRecognizer.startListening(intent)
        } catch (_: Exception) {
            tvStatus.text = "মাইক্রোফোন চালু করা যায়নি"
        }
    }

    private fun rumiSpeak(text: String) {
        mainHandler.post {
            isRumiSpeaking = true
            try { speechRecognizer.cancel() } catch (_: Exception) {}
            tvStatus.text = "🗣️ রুমি কথা বলছে..."
            tvRumiReply.text = "রুমি: $text"
        }
        thread(name = "RumiVoiceStream") {
            try {
                val safeText = if (text.length > 190) text.substring(0, 190) else text
                val encoded = URLEncoder.encode(safeText, "UTF-8")
                val audioUrl = "https://translate.google.com/translate_tts?ie=UTF-8&q=$encoded&tl=bn&client=tw-ob"
                mainHandler.post {
                    try {
                        mediaPlayer?.release()
                        mediaPlayer = MediaPlayer().apply {
                            setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .build()
                            )
                            setDataSource(audioUrl)
                            setOnPreparedListener { it.start() }
                            setOnCompletionListener {
                                isRumiSpeaking = false
                                resumeListeningIfNeeded()
                            }
                            setOnErrorListener { _, _, _ ->
                                fallbackOfflineSpeak(text)
                                true
                            }
                            prepareAsync()
                        }
                    } catch (_: Exception) {
                        fallbackOfflineSpeak(text)
                    }
                }
            } catch (_: Exception) {
                fallbackOfflineSpeak(text)
            }
        }
    }

    private fun fallbackOfflineSpeak(text: String) {
        mainHandler.post {
            if (::tts.isInitialized) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RUMI_ID")
        }
    }

    private fun resumeListeningIfNeeded() {
        mainHandler.postDelayed({
            if (isContinuousMode && !isRumiSpeaking) startListening()
        }, 450)
    }

    private fun stopContinuousMode() {
        isContinuousMode = false
        isRumiSpeaking = false
        btnSpeak.text = "📞 রুমির সাথে লাইভ কথা শুরু করুন"
        btnSpeak.setBackgroundColor(0xFFFF2E93.toInt())
        tvStatus.text = "লাইভ কথা বন্ধ আছে"
        try { speechRecognizer.cancel() } catch (_: Exception) {}
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        if (::tts.isInitialized) tts.stop()
    }

    private fun processCommand(command: String) {
        if (command.contains("কথা বন্ধ কর") || command.contains("থামো") || command.contains("চুপ কর")) {
            stopContinuousMode()
            return
        }
        if (awaitingConfirmation) {
            if (command.contains("হ্যাঁ") || command.contains("হা") || command.contains("করো") || command.contains("ওকে") || command.contains("yes")) {
                awaitingConfirmation = false
                val action = pendingAction
                pendingAction = null
                rumiSpeak("ঠিক আছে, এখনই করছি।")
                action?.invoke()
            } else if (command.contains("না") || command.contains("থাক") || command.contains("no")) {
                awaitingConfirmation = false
                pendingAction = null
                rumiSpeak("ঠিক আছে, কাজটি বাতিল করলাম।")
            } else {
                rumiSpeak("অনুমতি দিতে হ্যাঁ বলুন, আর না চাইলে না বলুন।")
            }
            return
        }

        if (command.contains("ফেসবুক") && (command.contains("চালাও") || command.contains("ওপেন") || command.contains("খোলো"))) {
            askPermission("আমি কি ফেসবুক ওপেন করবো? অনুমতি দিলে হ্যাঁ বলুন।") {
                openApp("com.facebook.katana", "https://m.facebook.com")
            }
        } else if (command.contains("ইউটিউব") && (command.contains("চালাও") || command.contains("ওপেন") || command.contains("খোলো") || command.contains("সার্চ"))) {
            val query = command.replace("ইউটিউবে", "").replace("ইউটিউব", "")
                .replace("চালাও", "").replace("ওপেন করো", "")
                .replace("খোলো", "").replace("সার্চ করো", "").trim()
            if (query.isNotEmpty()) {
                askPermission("আমি কি ইউটিউবে '$query' লিখে সার্চ করবো?") {
                    val encodedQuery = URLEncoder.encode(query, "UTF-8")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encodedQuery")))
                }
            } else {
                askPermission("আমি কি ইউটিউব ওপেন করবো?") {
                    openApp("com.google.android.youtube", "https://m.youtube.com")
                }
            }
        } else if (command.contains("গুগলে") && command.contains("সার্চ")) {
            val query = command.replace("গুগলে", "").replace("সার্চ দাও", "").replace("সার্চ করো", "").trim()
            if (query.isBlank()) {
                rumiSpeak("কী সার্চ করতে হবে বলুন।")
            } else {
                askPermission("আমি কি গুগলে '$query' লিখে সার্চ করবো?") {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, query)
                    }
                    startActivity(intent)
                }
            }
        } else if (command.contains("স্ক্রল") || command.contains("নিচে যাও")) {
            askPermission("আমি কি স্ক্রিনটি নিচে স্ক্রল করবো?") {
                val service = RumiControlService.instance
                if (service != null) service.scrollDown()
                else rumiSpeak("স্ক্রল করার জন্য আগে সেটিংস থেকে রুমির অ্যাক্সেসিবিলিটি পারমিশন অন করুন।")
            }
        } else if (command.contains("উপরে যাও")) {
            askPermission("আমি কি স্ক্রিনটি উপরে তুলবো?") {
                val service = RumiControlService.instance
                if (service != null) service.scrollUp()
                else rumiSpeak("উপরে স্ক্রল করতে আগে রুমির অ্যাক্সেসিবিলিটি পারমিশন অন করুন।")
            }
        } else if (command.contains("হোম") || command.contains("কেটে দাও")) {
            askPermission("আমি কি হোম স্ক্রিনে ফিরে যাবো?") {
                val service = RumiControlService.instance
                if (service != null) service.goHome()
                else rumiSpeak("এই কাজের জন্য আগে রুমির অ্যাক্সেসিবিলিটি পারমিশন অন করুন।")
            }
        } else if (command.contains("কল দাও") || command.contains("ডায়াল")) {
            askPermission("আমি কি ফোনের ডায়ালপ্যাড ওপেন করবো?") {
                startActivity(Intent(Intent.ACTION_DIAL))
            }
        } else if (command.contains("অ্যালার্ম")) {
            askPermission("আমি কি অ্যালার্ম সেটিং ওপেন করবো?") {
                startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS))
            }
        } else if (command.contains("তোমার নাম কি") || command.contains("তুমি কে") || command.contains("তোমার নাম কী")) {
            rumiSpeak("আমার নাম রুমি। আমি আপনার পার্সোনাল ভয়েস অ্যাসিস্ট্যান্ট।")
        } else {
            askRumiAI(command)
        }
    }

    private fun askRumiAI(userQuestion: String) {
        val prefs = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE)
        val currentBoxKey = etApiKey.text.toString().replace("\\s".toRegex(), "").trim()
        val apiKey = if (currentBoxKey.isNotEmpty()) currentBoxKey
            else (prefs.getString("API_KEY", null)
                ?: getSharedPreferences("rumi_settings", Context.MODE_PRIVATE).getString("gemini_api_key", "")).orEmpty().trim()

        if (apiKey.isEmpty() || !apiKey.startsWith("AIza")) {
            rumiSpeak("সঠিক Gemini API Key পাওয়া যায়নি। উপরের বক্সে API Key দিয়ে Save চাপুন।")
            return
        }

        mainHandler.post { tvStatus.text = "রুমি চিন্তা করছে..." }
        thread(name = "RumiGeminiRequest") {
            val models = listOf("gemini-3.8-flash", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash")
            var lastCode = 0
            var success = false

            for (modelName in models) {
                var conn: HttpURLConnection? = null
                try {
                    val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent")
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                        setRequestProperty("x-goog-api-key", apiKey)
                        doOutput = true
                    }

                    val prompt = "তোমার নাম রুমি। তুমি একজন বুদ্ধিমতী, বন্ধুসুলভ বাংলা ভয়েস অ্যাসিস্ট্যান্ট। " +
                        "ব্যবহারকারী বাংলায় কথা বললে সহজ ও স্বাভাবিক বাংলায় ১ থেকে ৩ বাক্যে উত্তর দাও। " +
                        "অপ্রয়োজনীয় মার্কডাউন চিহ্ন ব্যবহার করবে না। প্রশ্ন: $userQuestion"
                    val requestBody = JSONObject().put(
                        "contents",
                        JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
                    )
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(requestBody.toString()) }

                    val responseCode = conn.responseCode
                    lastCode = responseCode
                    val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                    val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                    if (responseCode in 200..299) {
                        val json = JSONObject(responseText)
                        val parts = json.optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        val reply = parts?.optJSONObject(0)?.optString("text").orEmpty()
                            .replace("*", "").replace("#", "").replace("\\n", " ").trim()
                        if (reply.isNotBlank()) {
                            rumiSpeak(reply)
                            success = true
                            break
                        }
                    } else {
                        android.util.Log.w("RumiAI", "Model $modelName returned HTTP $responseCode")
                        if (responseCode == 400 || responseCode == 401 || responseCode == 403 || responseCode == 429) break
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RumiAI", "Model $modelName request failed", e)
                } finally {
                    conn?.disconnect()
                }
            }

            if (!success) {
                mainHandler.post {
                    tvRumiReply.text = "রুমি: Gemini থেকে উত্তর পাওয়া যায়নি (HTTP $lastCode)। API Key, ইন্টারনেট বা মডেল উপলভ্যতা পরীক্ষা করুন।"
                }
                rumiSpeak(when (lastCode) {
                    400, 401, 403 -> "Gemini API Key বা API সেটিংস পরীক্ষা করুন।"
                    429 -> "অনেক বেশি অনুরোধ হয়েছে। একটু পরে আবার চেষ্টা করুন।"
                    404 -> "আপনার API-তে সমর্থিত Gemini মডেল পাওয়া যায়নি।"
                    else -> "ইন্টারনেট বা Gemini সংযোগে সমস্যা হয়েছে। আবার চেষ্টা করুন।"
                })
            }
        }
    }

    private fun askPermission(question: String, action: () -> Unit) {
        awaitingConfirmation = true
        pendingAction = action
        rumiSpeak(question)
    }

    private fun openApp(packageName: String, fallbackUrl: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)))
        }
    }

    override fun onDestroy() {
        isContinuousMode = false
        mediaPlayer?.release()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.cancel()
            speechRecognizer.destroy()
        }
        super.onDestroy()
    }
}