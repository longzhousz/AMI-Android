package com.longzhousz.ami

import android.os.Bundle
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.longzhousz.ami.core.neteaseLoginUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Official login in AMI's own WebView; no external browser/app credentials are read. */
class NeteaseLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val status = TextView(this).apply { text = "在网易云官方页面登录，完成后点右上角。"; setPadding(24, 12, 24, 12) }
        val bar = LinearLayout(this)
        bar.addView(Button(this).apply { text = "取消"; setOnClickListener { finish() } }, LinearLayout.LayoutParams(0, -2, 1f))
        val confirm = Button(this).apply { text = "完成登录" }
        bar.addView(confirm, LinearLayout.LayoutParams(0, -2, 1f)); root.addView(bar); root.addView(status)
        web = WebView(this)
        @Suppress("SetJavaScriptEnabled")
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.isForMainFrame && !neteaseLoginUrl(request.url.toString())
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true); cookies.setAcceptThirdPartyCookies(web, false)
        cookies.removeAllCookies { if (!isFinishing && !isDestroyed) web.loadUrl("https://music.163.com/m/login") }
        confirm.setOnClickListener { lifecycleScope.launch {
            confirm.isEnabled = false
            try {
                val ownSession = cookies.getCookie("https://music.163.com/").orEmpty()
                (application as AmiApplication).repository.cloud.login(ownSession)
                setResult(RESULT_OK); finish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status.text = error.message?.take(120) ?: "请完成登录后重试" }
            finally { confirm.isEnabled = true }
        } }
    }
    override fun onDestroy() {
        if (::web.isInitialized) { web.stopLoading(); web.destroy() }
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }
}
