package dev.neroices.zibzip.ui.components

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun Modifier.mouseDragScroll(
    state: ScrollableState,
    orientation: Orientation = Orientation.Vertical,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    overscrollEffect: OverscrollEffect? = rememberOverscrollEffect(),
): Modifier {
    val coroutineScope = rememberCoroutineScope()
    val scrollModifier = this.pointerInput(state, orientation, flingBehavior, overscrollEffect) {
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
                        val velocityOffset = if (orientation == Orientation.Vertical) {
                            Velocity(0f, v)
                        } else {
                            Velocity(v, 0f)
                        }
                        coroutineScope.launch {
                            if (overscrollEffect != null) {
                                overscrollEffect.applyToFling(velocityOffset) { remainingVelocity ->
                                    val flingV = if (orientation == Orientation.Vertical) {
                                        remainingVelocity.y
                                    } else {
                                        remainingVelocity.x
                                    }
                                    if (abs(flingV) > 50f) {
                                        var leftover = 0f
                                        state.scroll {
                                            with(flingBehavior) {
                                                leftover = -performFling(-flingV)
                                            }
                                        }
                                        if (orientation == Orientation.Vertical) {
                                            Velocity(0f, leftover)
                                        } else {
                                            Velocity(leftover, 0f)
                                        }
                                    } else {
                                        remainingVelocity
                                    }
                                }
                            } else if (abs(v) > 50f) {
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
                        if (overscrollEffect != null) {
                            val scrollDelta = if (orientation == Orientation.Vertical) {
                                Offset(0f, mainDelta)
                            } else {
                                Offset(mainDelta, 0f)
                            }
                            overscrollEffect.applyToScroll(
                                delta = scrollDelta,
                                source = NestedScrollSource.UserInput,
                            ) { remainingOffset ->
                                val remainingDelta = if (orientation == Orientation.Vertical) {
                                    remainingOffset.y
                                } else {
                                    remainingOffset.x
                                }
                                val consumed = -state.dispatchRawDelta(-remainingDelta)
                                if (orientation == Orientation.Vertical) {
                                    Offset(0f, consumed)
                                } else {
                                    Offset(consumed, 0f)
                                }
                            }
                        } else {
                            state.dispatchRawDelta(-mainDelta)
                        }
                        change.consume()
                    }
                    lastPosition = change.position
                }
            }
        }
    }
    return if (overscrollEffect != null) {
        scrollModifier.overscroll(overscrollEffect)
    } else {
        scrollModifier
    }
}
