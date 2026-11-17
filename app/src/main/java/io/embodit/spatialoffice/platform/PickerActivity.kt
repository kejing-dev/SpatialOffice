package io.embodit.spatialoffice.platform

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import io.embodit.spatialoffice.R

/** Invisible hop to the system file picker ([Open file…]); hands the picked document to the app and finishes. */
class PickerActivity : ComponentActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val graph = Graph.from(this)
        if (uri != null) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            val name = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: uri.lastPathSegment ?: "file"
            Log.i(TAG, "PICKER picked $name")
            val forImage = intent.getStringExtra(EXTRA_IMAGE)
            if (forImage != null) graph.imageChosen(Win.valueOf(forImage), FileRef.Content(uri, name)) else graph.open(FileRef.Content(uri, name))
        }
        finish()
    }

    companion object {
        /** Set to a [Win] name to pick a picture for that editor instead of opening a file. */
        const val EXTRA_IMAGE = "image_for"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        runCatching { picker.launch(arrayOf(if (intent.getStringExtra(EXTRA_IMAGE) != null) "image/*" else "*/*")) }.onFailure {
            Log.w(TAG, "PICKER unavailable: $it")
            Graph.from(this).let { g -> g.say(Win.HOME, g.str(R.string.picker_none)) }
            finish()
        }
    }
}
