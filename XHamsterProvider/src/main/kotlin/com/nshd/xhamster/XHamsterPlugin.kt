package com.nshd.xhamster

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XHamsterPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XHamsterProvider())
    }
}
