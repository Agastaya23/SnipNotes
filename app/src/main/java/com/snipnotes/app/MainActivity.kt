package com.snipnotes.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var notes: EditText
    private lateinit var status: TextView
    private lateinit var statusDetail: TextView
    private lateinit var enableBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var linkedLabel: TextView
    private var loaded = ""   // document as shown on resume; only write back if the user edited it

    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /** Create a brand-new file (Documents, Downloads, Drive…) that mirrors the notes. */
    private val createDoc = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> uri?.let { link(it, importExisting = false) } }

    /** Use an existing text file: its content is kept and new snippets are added below it. */
    private val openDoc = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { link(it, importExisting = true) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        notes = findViewById(R.id.notes)
        status = findViewById(R.id.status)
        statusDetail = findViewById(R.id.statusDetail)
        enableBtn = findViewById(R.id.enableBtn)
        batteryBtn = findViewById(R.id.batteryBtn)
        linkedLabel = findViewById(R.id.linkedLabel)

        enableBtn.setOnClickListener { showEnableHelp() }
        batteryBtn.setOnClickListener { requestKeepAlive() }

        bindSwitch(R.id.autoSwitch, Prefs.autoCapture(this)) { Prefs.setAutoCapture(this, it); refreshStatus() }
        bindSwitch(R.id.sourceSwitch, Prefs.addSource(this)) { Prefs.setAddSource(this, it) }
        bindSwitch(R.id.toastSwitch, Prefs.showToast(this)) { Prefs.setShowToast(this, it) }
        bindSwitch(R.id.editableSwitch, Prefs.skipEditable(this)) { Prefs.setSkipEditable(this, it) }

        findViewById<Button>(R.id.linkBtn).setOnClickListener { chooseLink() }
        findViewById<Button>(R.id.shareBtn).setOnClickListener { share() }
        findViewById<Button>(R.id.copyBtn).setOnClickListener { copyAll() }
        findViewById<Button>(R.id.clearBtn).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        loaded = NoteStore.read(this)
        notes.setText(loaded)
        notes.setSelection(notes.text.length)
        findViewById<MaterialSwitch>(R.id.autoSwitch).isChecked = Prefs.autoCapture(this)
        refreshStatus()
        refreshLinked()
    }

    override fun onPause() {
        val current = notes.text.toString()
        if (current != loaded) { NoteStore.save(this, current); loaded = current }
        super.onPause()
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
    }

    // ---- status -------------------------------------------------------------------------

    private fun serviceEnabled(): Boolean {
        val me = ComponentName(this, SnipService::class.java)
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun ignoringBattery(): Boolean =
        getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    private fun refreshStatus() {
        val enabled = serviceEnabled()
        val on = Prefs.autoCapture(this)
        when {
            !enabled -> {
                status.text = "● Background capture is off"
                statusDetail.text = "Turn it on once. After that, any text you select in other apps is saved here automatically."
            }
            !on -> {
                status.text = "● Running, but paused"
                statusDetail.text = "Flip “Auto-save selected text” (or use the Quick Settings tile) to resume."
            }
            else -> {
                status.text = "● Capturing in the background"
                statusDetail.text = "Select text anywhere and hold still for a second — it’s added here. " +
                    "If an app doesn’t trigger it, pick “Save to notes” from the selection menu."
            }
        }
        enableBtn.visibility = if (enabled) View.GONE else View.VISIBLE
        batteryBtn.visibility = if (ignoringBattery()) View.GONE else View.VISIBLE
    }

    private fun showEnableHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Turn on background capture")
            .setMessage(
                "On the next screen open “Installed apps” (or “Downloaded apps”) → SnipNotes auto-capture → On.\n\n" +
                "If the switch is greyed out (Android 13+ for apps not from the Play Store): go to " +
                "Settings → Apps → SnipNotes → ⋮ menu (top right) → “Allow restricted settings”, then try again."
            )
            .setPositiveButton("Open settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("App info") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .setNegativeButton("Later", null)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun requestKeepAlive() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- linked file --------------------------------------------------------------------

    private fun chooseLink() {
        val linked = Prefs.linkedUri(this)
        val items = mutableListOf("Create a new file", "Use an existing text file")
        if (linked != null) items += "Unlink current file"
        MaterialAlertDialogBuilder(this)
            .setTitle("Save notes into a file")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> createDoc.launch("SnipNotes.md")
                    1 -> openDoc.launch(arrayOf("text/*"))
                    2 -> {
                        linked?.let { runCatching { contentResolver.releasePersistableUriPermission(it, flags) } }
                        Prefs.setLinkedUri(this, null); refreshLinked()
                    }
                }
            }
            .show()
    }

    private fun link(uri: Uri, importExisting: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            Toast.makeText(this, "That location doesn’t allow ongoing access. Pick a file on the device.", Toast.LENGTH_LONG).show()
            return
        }
        val mine = notes.text.toString()
        val merged = if (importExisting) {
            val theirs = NoteStore.readUri(this, uri).trimEnd()
            when {
                theirs.isEmpty() -> mine
                mine.isBlank() -> theirs + "\n"
                else -> theirs + "\n\n" + mine
            }
        } else mine
        Prefs.setLinkedUri(this, uri)
        notes.setText(merged)
        NoteStore.save(this, merged)
        loaded = merged
        NoteStore.mirrorNow(this)
        refreshLinked()
        Toast.makeText(this, "Linked. New snippets are written to this file too.", Toast.LENGTH_SHORT).show()
    }

    private fun refreshLinked() {
        val uri = Prefs.linkedUri(this)
        if (uri == null) { linkedLabel.visibility = View.GONE; return }
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "linked file"
        linkedLabel.text = "Also saving to: $name"
        linkedLabel.visibility = View.VISIBLE
    }

    // ---- actions ------------------------------------------------------------------------

    private fun share() {
        val text = notes.text.toString()
        if (text.isBlank()) return
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "Send notes to…"
        ))
    }

    private fun copyAll() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("SnipNotes", notes.text))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Clear all notes?")
            .setMessage("This empties the document (and the linked file, if any).")
            .setPositiveButton("Clear") { _, _ -> notes.setText(""); NoteStore.save(this, ""); loaded = "" }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
