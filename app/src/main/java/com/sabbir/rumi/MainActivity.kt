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
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tvUserCommand: TextView
    private lateinit var tvRumiReply: TextView
    private lateinit var tvStatus: TextView

    // অনুমতি ট্র্যাক করার ভ্যারিয়েবল
    private var pendingAction: (() -> Unit)? = null
    private var awaitingConfirmation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvUserCommand = findViewById(R.id.tvUserCommand)
        tvRumiReply = findViewById(R.id.tvRumiReply)
        tvStatus = findViewById(R.id.tvStatus)
        val btnSpeak: Button = findViewById(R.id.btnSpeak)
        val btnPermission: Button = findViewById(R.id.btnPermission)

        // মাইক্রোফোন পারমিশন চাওয়া
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        // TextToSpeech (মেয়ে কণ্ঠ) সেটআপ
        tts = TextToSpeech(this, this)

        // SpeechRecognizer সেটআপ
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
                    processCommand(spokenText.lowercase())
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        btnSpeak.setOnClickListener {
            startListening()
        }

        btnPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val banglaLocale = Locale("bn", "BD")
            tts.language = banglaLocale

            // মেয়ে কণ্ঠ সিলেক্ট করার লজিক (Female Voice Selection & Pitch tuning)
            val voices = tts.voices
            if (voices != null) {
                for (voice in voices) {
                    if (voice.locale.language == "bn" && voice.name.lowercase().contains("female")) {
                        tts.voice = voice
                        break
                    }
                }
            }
            tts.setPitch(1.25f) // মিষ্টি মেয়েলি কণ্ঠের জন্য পিচ টিউনিং
            tts.setSpeechRate(0.95f)

            rumiSpeak("আসসালামু আলাইকুম, আমি রুমি। বলুন আপনার জন্য কী করতে পারি?")
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
        }
        speechRecognizer.startListening(intent)
    }

    private fun rumiSpeak(text: String) {
        tvRumiReply.text = "রুমি: $text"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "RUMI_ID")
    }

    // কমান্ড এবং অনুমতি যাচাই করার মূল ব্রেইন
    private fun processCommand(command: String) {
        // যদি রুমি কোনো কাজের অনুমতির অপেক্ষায় থাকে
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

        // ১. ফেসবুক চালানোর কমান্ড
        if (command.contains("ফেসবুক") && (command.contains("চালাও") || command.contains("ওপেন") || command.contains("খোলো"))) {
            askPermission("আমি কি আপনার ফোনে ফেসবুক ওপেন করবো? অনুমতি দিলে হ্যাঁ বলুন।") {
                openApp("com.facebook.katana", "https://www.facebook.com")
            }
        }
        // ২. স্ক্রল করার কমান্ড
        else if (command.contains("স্ক্রল") || command.contains("নিচে যাও")) {
            askPermission("আমি কি স্ক্রিনটি নিচে স্ক্রল করবো?") {
                val service = RumiControlService.instance
                if (service != null) {
                    service.scrollDown()
                } else {
                    rumiSpeak("স্ক্রল করার জন্য আগে সেটিংস থেকে রুমির অ্যাক্সেসিবিলিটি পারমিশন অন করুন।")
                }
            }
        }
        // ৩. উপরে যাওয়ার কমান্ড
        else if (command.contains("উপরে যাও")) {
            askPermission("আমি কি স্ক্রিনটি উপরে তুলবো?") {
                RumiControlService.instance?.scrollUp()
            }
        }
        // ৪. গুগলে সার্চ করার কমান্ড
        else if (command.contains("গুগলে") || command.contains("সার্চ")) {
            val query = command.replace("গুগলে", "").replace("সার্চ দাও", "").replace("সার্চ করো", "").trim()
            askPermission("আমি কি গুগলে '$query' লিখে সার্চ করবো?") {
                val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                }
                startActivity(intent)
            }
        }
        // ৫. ইউটিউব ওপেন করার কমান্ড
        else if (command.contains("ইউটিউব")) {
            askPermission("আমি কি ইউটিউব ওপেন করবো?") {
                openApp("com.google.android.youtube", "https://www.youtube.com")
            }
        }
        // ৬. হোম স্ক্রিনে যাওয়া
        else if (command.contains("হোম") || command.contains("কেটে দাও")) {
            askPermission("আমি কি হোম স্ক্রিনে ফিরে যাবো?") {
                RumiControlService.instance?.goHome()
            }
        }
        // ৭. সাধারণ কথোপকথন
        else if (command.contains("তোমার নাম কি") || command.contains("তুমি কে")) {
            rumiSpeak("আমার নাম রুমি। আমি আপনার পার্সোনাল ভয়েস অ্যাসিস্ট্যান্ট।")
        } else {
            rumiSpeak("আমি আপনার কথা শুনতে পেয়েছি। আপনি চাইলে আমাকে ফেসবুক চালাতে, স্ক্রল করতে বা গুগলে কিছু সার্চ করতে বলতে পারেন।")
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
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl))
            startActivity(browserIntent)
        }
    }

    override fun onDestroy() {
        tts.stop()
        tts.shutdown()
        speechRecognizer.destroy()
        super.onDestroy()
    }
}