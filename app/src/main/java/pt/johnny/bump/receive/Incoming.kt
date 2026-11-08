package pt.johnny.bump.receive

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The item currently arriving, shared between the receiver service and the in-app viewer.
 * The viewer opens on the preview the instant the item leaves the notch and swaps in the
 * full file as soon as it is saved.
 */
object Incoming {
    data class Item(
        val id: Long,
        val name: String,
        val mime: String,
        val peer: String,
        val preview: Bitmap? = null,
        val progress: Float = -1f,
        val uri: Uri? = null,
        val error: String? = null,
    )

    private val _item = MutableStateFlow<Item?>(null)
    val item: StateFlow<Item?> = _item.asStateFlow()

    fun start(item: Item) { _item.value = item }
    fun update(id: Long, change: (Item) -> Item) = _item.update { if (it?.id == id) change(it) else it }
}
