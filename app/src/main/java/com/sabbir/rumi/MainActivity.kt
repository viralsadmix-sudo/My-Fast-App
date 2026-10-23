package com.sabbir.rumi

import android.Manifest
import android.app.SearchManager
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
import java.util.Locale

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

        val btnSpeak: Button = findViewById(R.id.btnSpeak)
        val btnSaveKey: Button = findViewById(R.id.btnSaveKey)
        val btnVoiceSettings: Button = findViewById(R.id.btnVoiceSettings)

        val preferences = getSharedPreferences("rumi_settings", MODE_PRIVATE)
        etApiKey.setText(preferences.getString("gemini_api_key", ""))

        btnSaveKey.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "আগে Gemini API Key লিখুন", Toast.LENGTH_SHORT).show()
            } else {
                preferences.edit().putString("gemini_api_key", key).apply()
                Toast.makeText(this, "API Key এই ফোনে সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                tvStatus.text = "API Key সেভ হয়েছে"
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        tts = TextToSpeech(this, this)

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                tvStatus.text = "রুমি শুনছে..."
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                tvStatus.text = "প্রসেসিং হচ্ছে..."
            }
            override fun onError(error: Int) {
                tvStatus.text = "আবার চেষ্টা করুন"
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
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val banglaLocale = Locale("bn", "BD")
            val languageResult = tts.setLanguage(banglaLocale)

            val voices = tts.voices
            if (voices != null) {
                val femaleBanglaVoice = voices.firstOrNull {
                    it.locale.language == "bn" && it.name.contains("female", ignoreCase = true)
                }
                if (femaleBanglaVoice != null) tts.voice = femaleBanglaVoice
            }

            tts.setPitch(1.15f)
            tts.setSpeechRate(0.95f)

            if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tvStatus.text = "ফোনে বাংলা Text-to-speech voice ইনস্টল করুন"
            }
            rumiSpeak("আসসালামু আলাইকুম, আমি রুমি। বলুন আপনার জন্য কী করতে পারি?")
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            rumiSpeak("এই ফোনে Speech Recognition পাওয়া যাচ্ছে না।")
            return
        }
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
        tvRumiReply.text = "রুমি: $text"
        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RUMI_ID")
        }
    }

    private fun processCommand(command: String) {
        if (awaitingConfirmation) {
            if (command.contains("হ্যাঁ") || command.contains("হা") || command.contains("করো") || command.contains("ওকে") || command.contains("yes")) {
                awaitingConfirmation = false
                rumiSpeak("ঠিক আছে, আপনার নির্দেশ পালন করছি।")
                pendingAction?.invoke()
                pendingAction = null
            } else if (command.contains("না") || command.contains("থাক") || command.contains("দরকার নেই") || command.contains("no")) {
                awaitingConfirmation = false
                pendingAction = null
                rumiSpeak("ঠিক আছে, আমি কাজটি বাতিল করেছি।")
            } else {
                rumiSpeak("দয়া করে হ্যাঁ অথবা না বলুন।")
            }
            return
        }

        if (command.contains("ফেসবুক") && (command.contains("চালাও") || command.contains("ওপেন") || command.contains("খোলো"))) {
            askPermission("আমি কি আপনার ফোনে ফেসবুক ওপেন করবো? অনুমতি দিলে হ্যাঁ বলুন।") {
                openApp("com.facebook.katana", "https://www.facebook.com")
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
        } else if (command.contains("গুগলে") || command.contains("সার্চ")) {
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
        } else if (command.contains("ইউটিউব")) {
            askPermission("আমি কি ইউটিউব ওপেন করবো?") {
                openApp("com.google.android.youtube", "https://www.youtube.com")
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
            rumiSpeak("আমি আপনার কথা শুনতে পেয়েছি। আপাতত আমাকে ফেসবুক চালাতে, স্ক্রল করতে বা গুগলে কিছু সার্চ করতে বলতে পারেন।")
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
        if (::speechRecognizer.isInitialized) speechRecognizer.destroy()
        super.onDestroy()
    }
}