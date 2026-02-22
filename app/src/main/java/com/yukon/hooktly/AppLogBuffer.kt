package com.yukon.hooktly

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList

object AppLogBuffer {
    val lines: SnapshotStateList<String> = mutableStateListOf()

    fun add(line: String) {
        val v = line.trim()
        if (v.isEmpty()) return
        lines.add(v)
        while (lines.size > 300) {
            lines.removeAt(0)
        }
    }

    fun clear() {
        lines.clear()
    }
}
