package com.securebrowse.app

import android.app.Application
import android.content.Context
import android.webkit.WebView
import com.securebrowse.app.browser.ServiceWorkerGuard
import com.securebrowse.app.data.AppGraph

class SecureBrowseApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        WebView.setWebContentsDebuggingEnabled(false)
        graph = AppGraph(this)
        ServiceWorkerGuard.install { graph.currentPolicy() }
    }

    companion object {
        fun graph(context: Context): AppGraph {
            return (context.applicationContext as SecureBrowseApp).graph
        }
    }
}
