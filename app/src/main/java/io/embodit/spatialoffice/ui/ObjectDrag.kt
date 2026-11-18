package io.embodit.spatialoffice.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Icon
import io.embodit.spatialoffice.R
import io.embodit.spatialoffice.core.layout.Corner
import io.embodit.spatialoffice.core.layout.DragTotal
import io.embodit.spatialoffice.platform.TAG

/**
 * Moving and resizing what was inserted (v0.2.5, headset 2026-11-17: "PPT 插入的东西移动不了"). These are objects on a
 * page inside a Planar window; the hand, the controller ray and gaze + pinch all reach the window as one pointer, so
 * one drag detector serves the three (profile D53). The window itself is still moved by the system's own bar.
 *
 * Every event's `dragAmount` is one step; [DragTotal] adds the steps up to the travel since the press, and the
 * callbacks get that total (a handler that took one step for the total left the object pinned to where it started).
 */
fun Modifier.dragTotal(
    key: Any?, name: String,
    onStart: () -> Boolean,
    onTotal: (xPx: Float, yPx: Float) -> Unit,
    onEnd: () -> Boolean,
    onCancel: () -> Unit,
): Modifier = pointerInput(key) {
    val total = DragTotal()
    var on = false
    // gate:allow compose-drag an object on the page inside a Planar window (2D content); the window is moved by the system bar
    detectDragGestures(
        onDragStart = { total.reset(); on = onStart(); DragProbe.dragging = on; Log.i(TAG, "OBJDRAG start $name ok=$on") },
        onDrag = { change, dragAmount ->
            if (on) {
                change.consume()
                total.add(dragAmount.x, dragAmount.y)
                onTotal(total.x, total.y)
            }
        },
        onDragEnd = { if (on) { val changed = onEnd(); Log.i(TAG, "OBJDRAG end $name events=${total.events} total=${total.x.toInt()},${total.y.toInt()} px changed=$changed") }; on = false; DragProbe.dragging = false },
        onDragCancel = { if (on) onCancel(); on = false; DragProbe.dragging = false; Log.i(TAG, "OBJDRAG cancel $name") },
    )
}

/**
 * Where the things one drags are, in window pixels (log `DRAGAT <name> x,y`), so a real pointer drag can be aimed at
 * them from outside (`adb shell input swipe`, QA). Logged when a target appears or has moved, never during a drag.
 */
object DragProbe {
    @Volatile var dragging = false
    private val last = HashMap<String, Pair<Int, Int>>()
    fun at(name: String, x: Float, y: Float) {
        if (dragging) return
        val p = x.toInt() to y.toInt()
        if (last.put(name, p) != p) Log.i(TAG, "DRAGAT $name ${p.first},${p.second}")
    }
}

fun Modifier.probe(name: String?): Modifier = if (name == null) this else onGloballyPositioned { c ->
    val p = c.positionInWindow()
    DragProbe.at(name, p.x + c.size.width / 2f, p.y + c.size.height / 2f)
}

/** Hit size of a corner dot: 48 dp, less only on objects too small to hold two of them side by side. */
fun cornerHit(w: Dp, h: Dp): Dp = minOf(48.dp, maxOf(28.dp, minOf(w, h) * 0.6f))

/**
 * One corner dot of a picked object, centred on the corner at ([x], [y]) of the parent: a white dot with a blue ring
 * in a [hit] wide target. [drag] carries the resize.
 */
@Composable
fun CornerDot(corner: Corner, x: Dp, y: Dp, hit: Dp, label: String, drag: Modifier, probe: String? = null) {
    Box(
        Modifier.offset(x - hit / 2, y - hit / 2).size(hit).probe(probe?.let { "$it-${corner.name.lowercase()}" }).then(drag).semantics { contentDescription = "$label ${corner.name.lowercase()}" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White).border(3.dp, Ink.Select, CircleShape))
    }
}

/** The round tab a picked object is moved by (what is left to hold while its text is being typed): 48 dp, centred at ([x], [y]). */
@Composable
fun MoveTab(x: Dp, y: Dp, label: String, drag: Modifier, probe: String? = null) {
    Box(
        Modifier.offset(x - 24.dp, y - 24.dp).size(48.dp).probe(probe?.let { "$it-move" }).clip(CircleShape).background(Ink.Select).then(drag).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(R.drawable.ic_move), contentDescription = null, modifier = Modifier.size(26.dp), tint = Color.White)
    }
}
