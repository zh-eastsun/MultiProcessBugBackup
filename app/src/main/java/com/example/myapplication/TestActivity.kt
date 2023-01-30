package com.example.myapplication

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

class TestActivity : AppCompatActivity() {

    private val TAG = "zdy debug"//TestActivity::class.java.name
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test)

        webView = findViewById(R.id.web_view)
        webView?.apply {
            settings.javaScriptEnabled = true
            webViewClient = object : WebViewClient() {
                @RequiresApi(Build.VERSION_CODES.P)
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    view?.loadUrl(request?.url.toString())
                    Log.e(TAG, "web view load url: ${request?.url.toString()}")
                    Log.e(TAG, "current process name: ${Application.getProcessName()}")
                    Log.e(TAG, "current process id: ${android.os.Process.myPid()}")
                    return false
                }
            }
            loadUrl("https://wanandroid.com/")
        }
    }
}