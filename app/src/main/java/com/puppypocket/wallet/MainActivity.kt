package com.puppypocket.wallet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * MainActivity — Android Native Shell สำหรับ PuppyPocket
 *
 * หน้าที่หลัก:
 * 1. โหลดเว็บ PuppyPocket ผ่าน WebView (ใช้ HTML เดิมทั้งหมด ไม่ต้องเขียน UI ใหม่)
 * 2. ให้สิทธิ์กล้อง/ไมค์กับ WebView โดยตรง (แก้ปัญหาที่เว็บถูกบล็อกสิทธิ์ในเบราว์เซอร์แอปกระเป๋าเงินอื่นๆ
 *    เพราะตอนนี้เราเป็นแอปของเราเอง ควบคุมสิทธิ์เองได้เต็มที่)
 * 3. รับ Deep Link ตอนกระเป๋าเงิน (MetaMask/Trust/Bitget ฯลฯ) ส่งกลับมาหลังอนุมัติการเชื่อมต่อ
 *    (ตรงกับ metadata.redirect.native = "puppypocket://" ที่ตั้งไว้ในเว็บ)
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView

    // เปลี่ยนเป็น URL จริงของคุณได้ตลอด (ตอนนี้ตรงกับ metadata.url ในเว็บ)
    private val puppyPocketUrl = "file:///android_asset/index.html"

    // เก็บ callback ของ permission request จากหน้าเว็บ (กล้อง/ไมค์) ไว้ตอบกลับหลังผู้ใช้กด อนุญาต/ปฏิเสธ
    private var pendingWebPermissionRequest: PermissionRequest? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)
        setupWebView()

        webView.loadUrl(puppyPocketUrl)

        // เผื่อแอปถูกเปิดครั้งแรกจาก deep link (ไม่ใช่จากไอคอนแอปปกติ)
        handleIncomingDeepLink(intent)
    }

    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true          // จำเป็นสำหรับ localStorage ที่แอปใช้เก็บข้อมูลทั้งหมด
        settings.mediaPlaybackRequiresUserGesture = false // ให้ TTS/เสียงเล่นได้ทันทีตอนผู้ช่วย AI พูด
        settings.setSupportZoom(false)
        settings.allowFileAccess = true
        settings.allowContentAccess = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                // ลิงก์ deep link ไปแอปกระเป๋าเงิน (metamask://, trust://, wc:// ฯลฯ) ให้ระบบเปิดแอปนั้นแทน ไม่ใช่โหลดใน WebView
                return if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        true
                    } catch (e: Exception) {
                        false // ไม่มีแอปรองรับ URL นี้ในเครื่อง ปล่อยให้ WebView จัดการตามปกติ
                    }
                } else {
                    false // ลิงก์ http/https ปกติ ให้ WebView โหลดเองตามปกติ
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // เรียกทุกครั้งที่หน้าเว็บขอสิทธิ์กล้อง/ไมค์ (จาก getUserMedia ในผู้ช่วย AI)
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
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingDeepLink(intent)
    }

    /** จัดการตอนกระเป๋าเงินส่ง deep link กลับมาหลังผู้ใช้กดอนุมัติการเชื่อมต่อ (scheme: puppypocket://) */
    private fun handleIncomingDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "puppypocket") {
            // แค่ทำให้แอปกลับมาอยู่หน้าหน้าจอ (foreground) ก็พอ
            // ตัว WalletConnect SDK ในหน้าเว็บจะตรวจจับ session ที่เชื่อมสำเร็จเองอัตโนมัติ
            // (ผ่านกลไก relay ของ WalletConnect ไม่ต้องส่งอะไรเพิ่มจากฝั่ง native)
            webView.evaluateJavascript(
                "if (typeof toast === 'function') { toast('success', 'กลับมาที่ PuppyPocket แล้ว กำลังเช็คสถานะการเชื่อมต่อ...'); }",
                null
            )
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
