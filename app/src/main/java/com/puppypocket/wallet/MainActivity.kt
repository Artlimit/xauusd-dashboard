package com.puppypocket.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    private val puppyPocketUrl = "file:///android_asset/index.html"

    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingGeoOrigin: String? = null

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

        webView = WebView(this)
        setContentView(webView)
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
        }

        webView.webChromeClient = object : WebChromeClient() {
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
