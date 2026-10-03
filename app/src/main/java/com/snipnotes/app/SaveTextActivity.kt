package com.snipnotes.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Handles "Save to notes" in the text-selection menu (PROCESS_TEXT) and the Share sheet.
 * One tap, no pasting — the fallback for apps that don't report selections to the
 * background service. Shows nothing; saves and closes immediately.
 */
class SaveTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString()?.trim()

        if (!text.isNullOrEmpty()) {
            val adjusting = NoteStore.isAdjustment(text)
            val saved = NoteStore.addSnippet(this, text, referrer?.host, replaceLast = adjusting)
            if (Prefs.showToast(this)) {
                val words = text.split(Regex("\\s+")).size
                val msg = when {
                    !saved -> "Already in notes"
                    adjusting -> "Snippet updated ($words words)"
                    else -> "Saved to notes ($words words)"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }
}
