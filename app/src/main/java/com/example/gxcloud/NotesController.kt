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
import java.lang.ref.WeakReference
import java.util.UUID
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class NotesController(
    private val activity: Activity,
    private val stub: ViewStub
) {
    // Back/focus handling must also respect an open request waiting for disk I/O.
    val isVisible: Boolean get() = visible || showRequested

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
    private val saves = SaveRevisionTracker()
    private var loading = false
    private var showRequested = false
    private var destroyed = false
    private val rows = mutableMapOf<String, TextView>()
    private var styledSelection: String? = null

    private fun markDirty() {
        saves.changed()
    }

    fun show() {
        if (destroyed) return
        showRequested = true
        if (container == null) inflate()
        if (!loaded) {
            if (loading) return
            loading = true
            // One process-wide queue orders old-Activity saves before replacement loads.
            val file = notesFile
            val owner = WeakReference(this)
            val handler = autoSaveHandler
            io.execute {
                val result = load(file)
                handler.post {
                    val controller = owner.get() ?: return@post
                    if (controller.destroyed) return@post
                    controller.loading = false
                    controller.notes.clear()
                    controller.notes.addAll(result)
                    if (controller.notes.isEmpty()) controller.notes.add(Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis()))
                    controller.loaded = true
                    if (controller.showRequested) controller.showLoaded()
                }
            }
            return
        }
        showLoaded()
    }

    private fun showLoaded() {
        val root = container!!
        val list = root.findViewById<LinearLayout>(R.id.notesListContainer)
        val title = root.findViewById<EditText>(R.id.notesTitleEdit)
        val body = root.findViewById<EditText>(R.id.notesBodyEdit)
        if (selectedNoteId == null || notes.none { it.id == selectedNoteId }) selectedNoteId = notes[0].id
        rebuildList(list, title, body)
        loadIntoEditor(selectedNoteId!!, title, body)
        root.visibility = View.VISIBLE
        visible = true
    }

    fun hide() {
        showRequested = false
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
        destroyed = true
        showRequested = false
        autoSaveHandler.removeCallbacksAndMessages(null)
        saveRunnable = null
    }

    private fun inflate(): FrameLayout {
        val root = stub.inflate() as FrameLayout
        // ViewStub inflation defaults to visible; don't expose an empty editor while loading.
        root.visibility = View.GONE
        container = root
        val list = root.findViewById<LinearLayout>(R.id.notesListContainer)
        val title = root.findViewById<EditText>(R.id.notesTitleEdit)
        val body = root.findViewById<EditText>(R.id.notesBodyEdit)
        root.findViewById<Button>(R.id.notesCloseBtn).setOnClickListener { hide() }
        root.findViewById<Button>(R.id.notesNewBtn).setOnClickListener {
            forceSave()
            val note = Note(UUID.randomUUID().toString(), "", "", System.currentTimeMillis())
            notes.add(0, note)
            markDirty()
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
                    markDirty()
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
                    markDirty()
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
        val liveIds = notes.mapTo(HashSet()) { it.id }
        val iterator = rows.iterator()
        while (iterator.hasNext()) {
            val (id, row) = iterator.next()
            if (id !in liveIds) {
                list.removeView(row)
                iterator.remove()
            }
        }
        notes.forEachIndexed { index, note ->
            val row = rows.getOrPut(note.id) {
                TextView(activity).apply {
                    setTextColor(0xFF999999.toInt())
                    setBackgroundColor(0x00000000)
                    setPadding(40, 28, 40, 28)
                    textSize = 14f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener { forceSave(); select(note.id, list, title, body) }
                }
            }
            val label = note.title.ifEmpty { "Untitled" }
            if (row.text.toString() != label) row.text = label
            if (list.getChildAt(index) !== row) {
                list.removeView(row)
                list.addView(row, index)
            }
        }
        if (styledSelection != selectedNoteId) {
            rows[styledSelection]?.apply {
                setTextColor(0xFF999999.toInt())
                setBackgroundColor(0x00000000)
            }
            rows[selectedNoteId]?.apply {
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xFF2A2A2A.toInt())
            }
            styledSelection = selectedNoteId
        }
    }

    private fun scheduleSave() {
        saveRunnable?.let { autoSaveHandler.removeCallbacks(it) }
        val runnable = Runnable { saveRunnable = null; save() }
        saveRunnable = runnable
        autoSaveHandler.postDelayed(runnable, 600)
    }

    private fun save() {
        if (!loaded) return
        val version = saves.begin() ?: return
        val snapshot = notes.map { it.copy() }
        val file = notesFile
        val owner = WeakReference(this)
        val handler = autoSaveHandler
        io.execute {
            val success = write(file, snapshot)
            handler.post {
                val controller = owner.get() ?: return@post
                if (controller.destroyed) return@post
                // An older completion must never clear newer unsaved edits.
                controller.saves.complete(version, success)
            }
        }
    }

    private companion object {
        private fun load(file: AtomicFile): List<Note> = try {
            val arr = JSONArray(String(file.readFully()))
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Note(o.getString("id"), o.getString("title"), o.getString("body"), o.getLong("updatedAt"))
            }
        } catch (_: Exception) { emptyList() }

        private fun write(file: AtomicFile, snapshot: List<Note>): Boolean = try {
            val arr = JSONArray()
            snapshot.forEach { note -> arr.put(JSONObject().apply {
                put("id", note.id); put("title", note.title); put("body", note.body); put("updatedAt", note.updatedAt)
            }) }
            val stream = file.startWrite()
            try {
                stream.write(arr.toString().toByteArray())
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            true
        } catch (_: Exception) { false }


        // Idle threads expire; queued writes are never cancelled on Activity destruction.
        val io = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, LinkedBlockingQueue()).apply {
            allowCoreThreadTimeOut(true)
        }
    }
}
