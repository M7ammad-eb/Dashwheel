package com.openauto.dash

import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** Room kept between a menu and the edges of the dashboard's window. */
private val MENU_MARGIN = 8.dp

/** Frames a menu watches where it landed (see [WindowBoundPosition.settle]). */
private const val SETTLE_FRAMES = 60
/** Frames a menu must hold still before its place is judged. */
private const val STILL_FRAMES = 3
/** Frames given to a corrected menu to move before it is judged again. */
private const val MOVE_FRAMES = 8

/**
 * A menu that stays inside the dashboard's own window. Material's DropdownMenu
 * places itself against the whole display, so on head units whose firmware
 * keeps bars of its own over the screen (an upright unit with a climate strip
 * at the bottom and a shortcut bar at the top) it opened under those bars and
 * its last rows, Settings among them, could not be reached. The dashboard
 * already ends where those bars start, so its own area is the limit here.
 */
@Composable
internal fun WindowMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    containerColor: Color,
    border: BorderStroke? = null,
    /** How far the menu stands off what is under it. */
    shadow: androidx.compose.ui.unit.Dp = 3.dp,
    content: @Composable () -> Unit
) {
    if (!expanded) return
    val view = LocalView.current
    val density = LocalDensity.current
    val marginPx = with(density) { MENU_MARGIN.roundToPx() }
    val position = remember(view, marginPx) { WindowBoundPosition(view, marginPx) }
    val maxHeight = with(density) { (view.height - 2 * marginPx).coerceAtLeast(0).toDp() }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true)
    ) {
        val popupView = LocalView.current
        LaunchedEffect(position) {
            // The window moves some frames after a change: judge a place only once it holds still,
            // and after a correction give the move time to happen before judging again.
            var last: IntOffset? = null
            var still = 0
            repeat(SETTLE_FRAMES) {
                withFrameNanos { }
                val now = IntArray(2).also { popupView.getLocationOnScreen(it) }.let { IntOffset(it[0], it[1]) }
                still = if (now == last) still + 1 else 0
                last = now
                if (still >= STILL_FRAMES) {
                    if (position.settle(popupView)) return@LaunchedEffect
                    still = -MOVE_FRAMES
                }
            }
        }
        Surface(
            modifier = modifier.graphicsLayer { alpha = if (position.measuring) 0f else 1f },
            shape = shape,
            color = containerColor,
            border = border,
            shadowElevation = shadow,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .heightIn(max = maxHeight)
                    .width(IntrinsicSize.Max)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * Below the anchor, else above it, else against the bottom of the dashboard;
 * lined up with the anchor's start, else its end. The bounds are the
 * dashboard view's own place on the screen, never the display-wide
 * [windowSize] the pop-up machinery passes in. The menu is worked out in
 * screen coordinates, then handed over from the corner the pop-up window is
 * placed from. Android says that corner is the dashboard window's, but head
 * units with bars of their own disagree (an upright unit opened menus a bar's
 * height too low, their last rows under its climate strip). So the first menu
 * opens unseen at that corner, [settle] notes where it landed, and every menu
 * after is placed from there. Measuring a menu in its real place would not
 * do: Android pushes a window that runs off the screen back up, which hides
 * most of the error.
 */
internal class WindowBoundPosition(private val view: View, private val margin: Int) : PopupPositionProvider {
    /** Read while placing, so a measurement moves the open menu at once. */
    private var shift by mutableStateOf(learnedShift)
    /** Where the last placement meant the menu to be on screen. */
    private var intended: IntOffset? = null
    private var corrections = 0

    /** True while the menu sits unseen at the pop-up corner to measure it. */
    val measuring: Boolean get() = shift == null

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val origin = shift
        if (view.width <= 0 || view.height <= 0) {
            intended = null
            return menuOffset(anchorBounds, IntRect(0, 0, windowSize.width, windowSize.height), layoutDirection, popupContentSize, margin)
        }
        if (origin == null) {
            intended = null
            return IntOffset.Zero
        }
        val window = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
        val dash = IntArray(2).also { view.getLocationOnScreen(it) }
        val anchor = anchorBounds.translate(window[0], window[1])
        val bounds = IntRect(dash[0], dash[1], dash[0] + view.width, dash[1] + view.height)
        val onScreen = menuOffset(anchor, bounds, layoutDirection, popupContentSize, margin)
        intended = onScreen
        return popupParams(onScreen, IntOffset(window[0], window[1]), origin)
    }

    /**
     * Called once the menu holds still. While measuring, keeps where the
     * pop-up corner landed and moves the menu into place. Otherwise compares
     * the menu's real place with the intended one and moves it when they
     * differ. True once it sits right, or after a few tries.
     */
    fun settle(popupView: View): Boolean {
        if (!popupView.isAttachedToWindow || popupView.width <= 0) return false
        val at = IntArray(2).also { popupView.getLocationOnScreen(it) }
        val current = shift
        if (current == null) {
            val window = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
            shift = IntOffset(at[0] - window[0], at[1] - window[1])
            learnedShift = shift
            return false
        }
        val want = intended ?: return false
        val off = IntOffset(at[0] - want.x, at[1] - want.y)
        if (off == IntOffset.Zero || corrections >= MAX_CORRECTIONS) return true
        corrections++
        shift = current + off
        learnedShift = shift
        return false
    }

    companion object {
        private const val MAX_CORRECTIONS = 2
        /** Pop-up corner minus the dashboard window's corner, once measured. */
        private var learnedShift: IntOffset? = null
    }
}

/** The pop-up window's position for a menu meant at [onScreen]. */
internal fun popupParams(onScreen: IntOffset, window: IntOffset, shift: IntOffset): IntOffset =
    onScreen - window - shift

/** [WindowBoundPosition]'s placement, apart from the view so it can be tested. */
internal fun menuOffset(anchor: IntRect, bounds: IntRect, dir: LayoutDirection, menu: IntSize, margin: Int): IntOffset {
    val startX = if (dir == LayoutDirection.Ltr) anchor.left else anchor.right - menu.width
    val endX = if (dir == LayoutDirection.Ltr) anchor.right - menu.width else anchor.left
    val minX = bounds.left + margin
    val maxX = (bounds.right - margin - menu.width).coerceAtLeast(minX)
    val x = when {
        startX in minX..maxX -> startX
        endX in minX..maxX -> endX
        else -> startX.coerceIn(minX, maxX)
    }
    val minY = bounds.top + margin
    val maxY = (bounds.bottom - margin - menu.height).coerceAtLeast(minY)
    val below = anchor.bottom
    val above = anchor.top - menu.height
    val y = when {
        below in minY..maxY -> below
        above in minY..maxY -> above
        else -> maxY
    }
    return IntOffset(x, y)
}
