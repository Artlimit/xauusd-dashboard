package com.puppypocket.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var tts: TextToSpeech

    private val puppyPocketUrl = "file:///android_asset/index.html"

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

    // ---- ตัวช่วย debug: ให้ JS ฝั่งเว็บส่ง error กลับมาแสดงเป็น popup ในแอปได้ ----
    
inner class AndroidSpeakBridge {
    @android.webkit.JavascriptInterface
    fun speak(text:String){
        runOnUiThread{
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AI")
        }
    }
}

inner class AndroidDebugBridge {
        @JavascriptInterface
        fun logError(msg: String) {
            runOnUiThread {
                showDebugDialog("JS Error", msg)
            }
        }
    }

    private fun showDebugDialog(title: String, message: String) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(android.graphics.Color.parseColor("#1E1530"))
            }
        }
        val titleView = android.widget.TextView(this).apply {
            text = "🐾 $title"
            setTextColor(android.graphics.Color.parseColor("#F5F0FF"))
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        }
        val messageView = android.widget.TextView(this).apply {
            text = message
            setTextColor(android.graphics.Color.parseColor("#C9BFE0"))
            textSize = 13.5f
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(titleView)
        container.addView(messageView)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(container)
            .setPositiveButton("ปิด", null)
            .create()
        dialog.setOnShowListener {
            val btn = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            btn?.setTextColor(android.graphics.Color.parseColor("#FF7EB9"))
            btn?.setTypeface(btn.typeface, android.graphics.Typeface.BOLD)
        }
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.show()
    }

    private val requestAndroidPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        val request = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (request == null) return@registerForActivityResult

        if (allGranted) {
            request.grant(request.resources)
        } else {
            request.deny()
            Toast.makeText(this, "ต้องอนุญาตกล้อง/ไมค์ก่อนถึงจะใช้ผู้ช่วย AI ได้", Toast.LENGTH_SHORT).show()
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        if (callback == null) return@registerForActivityResult

        val data = result.data
        val uris: Array<Uri>? = when {
            result.resultCode != RESULT_OK || data == null -> null
            data.clipData != null -> {
                val clipData = data.clipData!!
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        callback.onReceiveValue(uris ?: arrayOf())
    }

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants.values.any { it }
        val callback = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        if (callback != null && origin != null) {
            callback.invoke(origin, granted, false)
        }
        if (!granted) {
            Toast.makeText(this, "ต้องอนุญาตตำแหน่ง (GPS) ก่อนถึงจะใช้งานฟีเจอร์นี้ได้", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(true)

        webView = WebView(this)
        setContentView(webView)

        tts = TextToSpeech(this){ if(it==TextToSpeech.SUCCESS){ tts.language = Locale("th","TH") } }
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                // พูดจบจริงแล้ว (สัญญาณจริงจากระบบ TTS ไม่ใช่การเดาเวลา) แจ้งฝั่งเว็บให้เปิดไมค์/เปลี่ยนสถานะปุ่มต่อได้เลย
                runOnUiThread {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("if(window.onNativeTTSDone) window.onNativeTTSDone();", null)
                    }
                }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript("if(window.onNativeTTSDone) window.onNativeTTSDone();", null)
                    }
                }
            }
        })
        setupWebView()

        webView.loadUrl(puppyPocketUrl)

        handleIncomingDeepLink(intent)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(false)
        settings.allowFileAccess = true
        settings.allowContentAccess = false
        settings.setGeolocationEnabled(true)

        webView.addJavascriptInterface(AndroidDebugBridge(), "AndroidDebug")
        webView.addJavascriptInterface(AndroidSpeakBridge(), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                return if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    } catch (e: Exception) {
                        false
                    }
                } else {
                    false
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    runOnUiThread {
                        showDebugDialog(
                            "โหลดหน้าเว็บไม่สำเร็จ",
                            "URL: ${request.url}\nรหัส: ${error.errorCode}\nรายละเอียด: ${error.description}"
                        )
                    }
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // ดักจับ JavaScript error ที่เกิดขึ้นบนหน้าเว็บ แล้วส่งกลับมาที่แอปให้เห็น (เฉพาะตอนมี error จริงเท่านั้น)
                view.evaluateJavascript(
                    """
                    (function() {
                        window.onerror = function(msg, src, line, col, err) {
                            if (window.AndroidDebug) {
                                AndroidDebug.logError('พบข้อผิดพลาด: ' + msg + '\\nไฟล์: ' + src + '\\nบรรทัด: ' + line + ':' + col);
                            }
                            return false;
                        };
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                // เปลี่ยนจากเด้ง popup ทุก console.error (ซึ่งส่วนใหญ่เป็นแค่ warning ไม่ร้ายแรงจาก SDK ภายนอก
                // เช่น Coinbase wallet-sdk เช็ค CORS แล้วไม่ผ่านเฉยๆ ไม่กระทบการทำงานจริง)
                // เป็นการ log ไปที่ Logcat แทน ใครอยากดู error จริงๆ เปิด chrome://inspect ตรวจสอบได้
                // (WebView.setWebContentsDebuggingEnabled(true) เปิดไว้อยู่แล้วด้านบน)
                android.util.Log.e(
                    "PuppyPocketWebView",
                    "${consoleMessage.message()} (line ${consoleMessage.lineNumber()} @ ${consoleMessage.sourceId()})"
                )
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val needed = mutableListOf<String>()
                    if (request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        needed.add(Manifest.permission.CAMERA)
                    }
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        needed.add(Manifest.permission.RECORD_AUDIO)
                    }

                    val allAlreadyGranted = needed.all {
                        ContextCompat.checkSelfPermission(this@MainActivity, it) == PackageManager.PERMISSION_GRANTED
                    }

                    if (allAlreadyGranted) {
                        request.grant(request.resources)
                    } else {
                        pendingWebPermissionRequest = request
                        requestAndroidPermissions.launch(needed.toTypedArray())
                    }
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(arrayOf())
                fileChooserCallback = filePathCallback

                val intent = fileChooserParams.createIntent().apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    fileChooserCallback = null
                    Toast.makeText(this@MainActivity, "ไม่พบแอปสำหรับเลือกไฟล์", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                val hasPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasPermission) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeoCallback = callback
                    pendingGeoOrigin = origin
                    requestLocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.evaluateJavascript(
                "if (window._wcReconnect) { window._wcReconnect(); }",
                null
            )
        }
    }

    private fun handleIncomingDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "puppypocket") {
            webView.evaluateJavascript(
                "if (window._wcReconnect) { window._wcReconnect(); } " +
                "if (typeof toast === 'function') { toast('success', 'กลับมาที่ PuppyPocket แล้ว กำลังเช็คสถานะการเชื่อมต่อ...'); }",
                null
            )
        }
    }

    override fun onDestroy(){
        if(::tts.isInitialized){ tts.stop(); tts.shutdown() }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
