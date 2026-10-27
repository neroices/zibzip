package dev.neroices.zibzip.ui.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun Modifier.mouseDragScroll(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
): Modifier {
    val coroutineScope = rememberCoroutineScope()
    return this.pointerInput(state, orientation, flingBehavior) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse) return@awaitEachGesture

            val velocityTracker = VelocityTracker()
            velocityTracker.addPointerInputChange(down)

            var isDragging = false
            var lastPosition = down.position
            var accumulatedDistance = 0f

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break

                if (!change.pressed || !event.buttons.isPrimaryPressed) {
                    if (isDragging) {
                        change.consume()
                        val velocity = velocityTracker.calculateVelocity()
                        val v = if (orientation == Orientation.Vertical) velocity.y else velocity.x
                        if (abs(v) > 50f) {
                            coroutineScope.launch {
                                state.scroll {
                                    with(flingBehavior) {
                                        performFling(-v)
                                    }
                                }
                            }
                        }
                    }
                    break
                }

                velocityTracker.addPointerInputChange(change)
                val delta = change.position - lastPosition
                val mainDelta = if (orientation == Orientation.Vertical) delta.y else delta.x

                if (!isDragging) {
                    accumulatedDistance += abs(mainDelta)
                    if (accumulatedDistance > viewConfiguration.touchSlop) {
                        isDragging = true
                        lastPosition = change.position
                    }
                }

                if (isDragging) {
                    if (mainDelta != 0f) {
                        state.dispatchRawDelta(-mainDelta)
                        change.consume()
                    }
                    lastPosition = change.position
                }
            }
        }
    }
}
