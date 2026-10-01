package com.snipnotes.app

import android.content.Context
import android.net.Uri

/** Small wrapper around SharedPreferences for the app's switches. */
object Prefs {
    private const val NAME = "snipnotes"
    private const val AUTO = "auto_capture"
    private const val SOURCE = "add_source"
    private const val TOAST = "show_toast"
    private const val SKIP_EDIT = "skip_editable"
    private const val LINKED = "linked_uri"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun autoCapture(c: Context) = sp(c).getBoolean(AUTO, true)
    fun setAutoCapture(c: Context, v: Boolean) = sp(c).edit().putBoolean(AUTO, v).apply()

    fun addSource(c: Context) = sp(c).getBoolean(SOURCE, true)
    fun setAddSource(c: Context, v: Boolean) = sp(c).edit().putBoolean(SOURCE, v).apply()

    fun showToast(c: Context) = sp(c).getBoolean(TOAST, true)
    fun setShowToast(c: Context, v: Boolean) = sp(c).edit().putBoolean(TOAST, v).apply()

    /** Ignore selections inside text boxes you are typing in (your own drafts, search bars). */
    fun skipEditable(c: Context) = sp(c).getBoolean(SKIP_EDIT, true)
    fun setSkipEditable(c: Context, v: Boolean) = sp(c).edit().putBoolean(SKIP_EDIT, v).apply()

    fun linkedUri(c: Context): Uri? = sp(c).getString(LINKED, null)?.let(Uri::parse)
    fun setLinkedUri(c: Context, u: Uri?) = sp(c).edit().putString(LINKED, u?.toString()).apply()
}
