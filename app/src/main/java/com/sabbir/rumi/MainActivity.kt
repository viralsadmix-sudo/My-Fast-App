package com.sabbir.rumi

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
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
        val btnSpeak: Button = findViewById(R.id.btnSpeak)
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

        btnSpeak.setOnClickListener { startListening() }
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

            tts.setPitch(1.35f)
            tts.setSpeechRate(0.95f)

            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tvStatus.text = "ফোনে বাংলা Text-to-speech voice ইনস্টল করুন"
            }
            rumiSpeak("আসসালামু আলাইকুম! আমি রুমি। আমাকে যেকোনো প্রশ্ন করতে পারেন।")
        } else {
            tvStatus.text = "Text-to-speech চালু করা যায়নি"
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            rumiSpeak("এই ফোনে Speech Recognition পাওয়া যাচ্ছে না।")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        if (::tts.isInitialized) tts.stop()
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
        runOnUiThread {
            tvStatus.text = "আপনার পার্সোনাল ভয়েস অ্যাসিস্ট্যান্ট"
            tvRumiReply.text = "রুমি: $text"
            if (::tts.isInitialized) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RUMI_ID")
            }
        }
    }

    private fun processCommand(command: String) {
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
        } else if (command.contains("তোমার নাম কি") || command.contains("তুমি কে") || command.contains("তোমার নাম কী")) {
            rumiSpeak("আমার নাম রুমি। আমি আপনার পার্সোনাল ভয়েস অ্যাসিস্ট্যান্ট।")
        } else {
            askRumiAI(command)
        }
    }

    private fun askRumiAI(userQuestion: String) {
        val prefs = getSharedPreferences("RumiPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("API_KEY", "")?.trim().orEmpty()

        if (apiKey.isEmpty()) {
            rumiSpeak("আমার AI উত্তর চালু করতে উপরের বক্সে Gemini API Key পেস্ট করে Save বাটনে চাপ দিন।")
            return
        }

        runOnUiThread { tvStatus.text = "রুমি চিন্তা করছে..." }

        thread(name = "RumiGeminiRequest") {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
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
                    "প্রয়োজনে প্রশ্নটি বুঝে সহায়ক উত্তর দাও। অপ্রয়োজনীয় মার্কডাউন চিহ্ন ব্যবহার করবে না।\nপ্রশ্ন: $userQuestion"

                val requestBody = JSONObject()
                    .put("contents", JSONArray().put(
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    ))

                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(requestBody.toString())
                }

                val responseCode = conn.responseCode
                val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
                val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

                if (responseCode in 200..299) {
                    val json = JSONObject(responseText)
                    val candidates = json.optJSONArray("candidates")
                    val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val reply = parts?.optJSONObject(0)?.optString("text").orEmpty().trim()
                    if (reply.isNotBlank()) {
                        rumiSpeak(reply.replace("*", ""))
                    } else {
                        rumiSpeak("এবার উত্তর পাওয়া যায়নি। আরেকবার প্রশ্ন করুন।")
                    }
                } else {
                    val apiMessage = try {
                        JSONObject(responseText).optJSONObject("error")?.optString("message").orEmpty()
                    } catch (_: Exception) { "" }
                    android.util.Log.e("RumiAI", "Gemini API error $responseCode: $apiMessage")
                    when (responseCode) {
                        400, 401, 403 -> rumiSpeak("Gemini API Key বা API সেটিংস পরীক্ষা করুন।")
                        404 -> rumiSpeak("এই Gemini মডেলটি পাওয়া যাচ্ছে না। মডেল নাম পরীক্ষা করতে হবে।")
                        429 -> rumiSpeak("এখন অনেক বেশি অনুরোধ হয়েছে। একটু পরে আবার চেষ্টা করুন।")
                        else -> rumiSpeak("Gemini থেকে উত্তর পাওয়া যায়নি। ইন্টারনেট সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("RumiAI", "Gemini request failed", e)
                rumiSpeak("দুঃখিত, সংযোগে সমস্যা হয়েছে। ইন্টারনেট পরীক্ষা করে আবার বলুন।")
            } finally {
                conn?.disconnect()
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