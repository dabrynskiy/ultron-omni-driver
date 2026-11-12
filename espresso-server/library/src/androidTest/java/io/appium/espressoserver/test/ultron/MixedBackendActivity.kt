package io.appium.espressoserver.test.ultron

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import java.util.concurrent.CountDownLatch

class MixedBackendActivity : Activity() {
    var clicks = 0
    lateinit var button: Button
    lateinit var webView: WebView
    val loaded = CountDownLatch(1)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        button = Button(this).apply { id = BUTTON; text = "Native"; isAllCaps = false; setOnClickListener { clicks++ } }
        layout.addView(button)
        webView = WebView(this).apply {
            id = WEB
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) { loaded.countDown() }
            }
        }
        layout.addView(webView, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        webView.loadDataWithBaseURL("https://mixed.test/", """
            <html><body>
              <input id="input">
              <button id="submit" onclick="document.getElementById('count').textContent=String(Number(document.getElementById('count').textContent)+1);document.getElementById('status').textContent=document.getElementById('input').value">Submit</button>
              <div id="status">Ready</div><div id="count">0</div>
              <iframe id="inner" srcdoc="<html><body><div id='inside'>Frame ready</div></body></html>"></iframe>
              <button class="duplicate">One</button><button class="duplicate">Two</button>
            </body></html>
        """.trimIndent(), "text/html", "UTF-8", null)
    }

    override fun onDestroy() { webView.destroy(); super.onDestroy() }

    companion object { const val BUTTON = 0x7f050101; const val WEB = 0x7f050102 }
}
