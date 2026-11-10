package io.appium.espressoserver.test.ultron

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch

class UltronWebActivity : Activity() {
    lateinit var webView: WebView
    val pageLoaded = CountDownLatch(1)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded.countDown()
                }
            }
        }
        setContentView(webView)
        webView.loadDataWithBaseURL(
            "https://ultron.test/",
            """
            <html><body>
                <input id="input" />
                <button id="submit" onclick="
                    document.getElementById('clicks').textContent =
                        Number(document.getElementById('clicks').textContent) + 1;
                    document.getElementById('status').textContent = document.getElementById('input').value;
                ">Submit</button>
                <button id="delayed" onclick="
                    document.getElementById('clicks').textContent =
                        Number(document.getElementById('clicks').textContent) + 1;
                    setTimeout(function() {
                        var element = document.createElement('div');
                        element.id = 'late';
                        element.textContent = 'Loaded';
                        document.body.appendChild(element);
                    }, 1000);
                ">Load asynchronously</button>
                <div id="status">Ready</div>
                <div id="clicks">0</div>
            </body></html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null,
        )
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
