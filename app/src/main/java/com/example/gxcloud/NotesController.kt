package com.example.gxcloud

import android.app.Activity
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.AtomicFile
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import android.view.ViewStub
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import android.os.Handler
import android.os.Looper

class NotesController(
    private val activity: Activity,
    private val stub: ViewStub
) {
    val isVisible: Boolean get() = visible

    private data class Note(val id: String, var title: String, var body: String, var updatedAt: Long)

    private var container: FrameLayout? = null
    private var visible = false
    private val autoSaveHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null
    private val notesFile by lazy { AtomicFile(File(activity.filesDir, "gxcloud_notes.json")) }
    private val notes = mutableListOf<Note>()
    private var selectedNoteId: String? = null
    private var loaded = false
    private var loadingEditor = false
    private var dirty = false

    fun show() {
        if (container == null) inflate()
        if (!loaded) {
            notes.clear()
            notes.addAll(load())
            if (notes.isEmpty()) notes.add(Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis()))
            loaded = true
        }
        val root = container!!
        val list = root.findViewById<LinearLayout>(R.id.notesListContainer)
        val title = root.findViewById<EditText>(R.id.notesTitleEdit)
        val body = root.findViewById<EditText>(R.id.notesBodyEdit)
        rebuildList(list, title, body)
        if (selectedNoteId == null || notes.none { it.id == selectedNoteId }) selectedNoteId = notes[0].id
        loadIntoEditor(selectedNoteId!!, title, body)
        root.visibility = View.VISIBLE
        visible = true
    }

    fun hide() {
        forceSave()
        container?.visibility = View.GONE
        visible = false
    }

    fun forceSave() {
        saveRunnable?.let { autoSaveHandler.removeCallbacks(it) }
        saveRunnable = null
        save()
    }

    fun destroy() {
        forceSave()
        autoSaveHandler.removeCallbacksAndMessages(null)
        saveRunnable = null
    }

    private fun inflate(): FrameLayout {
        val root = stub.inflate() as FrameLayout
        container = root
        val list = root.findViewById<LinearLayout>(R.id.notesListContainer)
        val title = root.findViewById<EditText>(R.id.notesTitleEdit)
        val body = root.findViewById<EditText>(R.id.notesBodyEdit)
        root.findViewById<Button>(R.id.notesCloseBtn).setOnClickListener { hide() }
        root.findViewById<Button>(R.id.notesNewBtn).setOnClickListener {
            forceSave()
            val note = Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis())
            notes.add(0, note)
            dirty = true
            save()
            select(note.id, list, title, body)
        }
        root.findViewById<Button>(R.id.notesDeleteBtn).setOnClickListener {
            val id = selectedNoteId ?: return@setOnClickListener
            AlertDialog.Builder(activity)
                .setMessage("Delete this note?")
                .setPositiveButton("Delete") { _, _ ->
                    notes.removeAll { it.id == id }
                    if (notes.isEmpty()) notes.add(Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis()))
                    dirty = true
                    save()
                    select(notes[0].id, list, title, body)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        val autoSave = {
            val id = selectedNoteId
            if (id != null && !loadingEditor) {
                notes.find { it.id == id }?.let {
                    it.title = title.text.toString()
                    it.body = body.text.toString()
                    it.updatedAt = System.currentTimeMillis()
                    dirty = true
                }
                scheduleSave()
            }
        }
        title.addTextChangedListener(watcher(autoSave))
        body.addTextChangedListener(watcher(autoSave))
        return root
    }

    private fun watcher(after: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) = after()
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    private fun select(id: String, list: LinearLayout, title: EditText, body: EditText) {
        selectedNoteId = id
        loadIntoEditor(id, title, body)
        rebuildList(list, title, body)
    }

    private fun loadIntoEditor(id: String, title: EditText, body: EditText) {
        val note = notes.find { it.id == id } ?: return
        loadingEditor = true
        try {
            title.setText(note.title)
            body.setText(note.body)
            title.setSelection(note.title.length)
        } finally { loadingEditor = false }
    }

    private fun rebuildList(list: LinearLayout, title: EditText, body: EditText) {
        list.removeAllViews()
        notes.forEach { note ->
            val row = TextView(activity).apply {
                text = note.title.ifEmpty { "Untitled" }
                setTextColor(if (note.id == selectedNoteId) 0xFFFFFFFF.toInt() else 0xFF999999.toInt())
                setBackgroundColor(if (note.id == selectedNoteId) 0xFF2A2A2A.toInt() else 0x00000000)
                setPadding(40, 28, 40, 28)
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { forceSave(); select(note.id, list, title, body) }
            }
            list.addView(row)
        }
    }

    private fun scheduleSave() {
        saveRunnable?.let { autoSaveHandler.removeCallbacks(it) }
        val runnable = Runnable { saveRunnable = null; save() }
        saveRunnable = runnable
        autoSaveHandler.postDelayed(runnable, 600)
    }

    private fun load(): List<Note> = try {
        val arr = JSONArray(String(notesFile.readFully()))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Note(o.getString("id"), o.getString("title"), o.getString("body"), o.getLong("updatedAt"))
        }
    } catch (_: Exception) { emptyList() }

    private fun save() {
        if (!loaded || !dirty) return
        try {
            val arr = JSONArray()
            notes.forEach { note -> arr.put(JSONObject().apply {
                put("id", note.id); put("title", note.title); put("body", note.body); put("updatedAt", note.updatedAt)
            }) }
            val stream = notesFile.startWrite()
            try {
                stream.write(arr.toString().toByteArray())
                notesFile.finishWrite(stream)
                dirty = false
            } catch (e: Exception) {
                notesFile.failWrite(stream)
                throw e
            }
        } catch (_: Exception) {}
    }
}
