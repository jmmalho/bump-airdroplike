package pt.johnny.bump.receive

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is arriving right now, shared between the receiver service and the in-app viewer.
 * The viewer opens on the previews the instant the items leave the notch and swaps in each
 * full file as soon as it is saved.
 */
object Incoming {
    data class Entry(val name: String, val mime: String, val preview: Bitmap? = null, val uri: Uri? = null)

    data class Item(
        val id: Long,
        val peer: String,
        val entries: List<Entry>,
        val progress: Float = -1f,
        val error: String? = null,
    )

    private val _item = MutableStateFlow<Item?>(null)
    val item: StateFlow<Item?> = _item.asStateFlow()

    fun start(item: Item) { _item.value = item }
    fun update(id: Long, change: (Item) -> Item) = _item.update { if (it?.id == id) change(it) else it }

    fun updateEntry(id: Long, index: Int, change: (Entry) -> Entry) = update(id) { item ->
        if (index !in item.entries.indices) item
        else item.copy(entries = item.entries.toMutableList().also { it[index] = change(it[index]) })
    }
}
