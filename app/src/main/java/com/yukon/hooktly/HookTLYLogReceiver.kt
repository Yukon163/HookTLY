package com.yukon.hooktly

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HookTLYLogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val line = intent.getStringExtra(EXTRA_LINE) ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        AppLogBuffer.add("$ts  $line")
    }

    companion object {
        const val ACTION = "com.yukon.hooktly.LOG"
        const val EXTRA_LINE = "line"
    }
}
