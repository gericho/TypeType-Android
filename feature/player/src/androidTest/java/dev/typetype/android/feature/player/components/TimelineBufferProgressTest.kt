package dev.typetype.android.feature.player.components

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.test.platform.app.InstrumentationRegistry
import dev.typetype.android.domain.stream.SponsorAction
import dev.typetype.android.domain.stream.SponsorBlockSegment
import dev.typetype.android.domain.stream.SponsorCategory
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class TimelineBufferProgressTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()
    private val model = mutableStateOf(BufferTrackModel())

    @Test
    fun playedBufferedAndRemainingSectionsHaveDistinctColorsInEveryLayout() {
        showTracks()
        for (index in 0..2) {
            assertTrackColor(index, 0.1f, Color.Red)
            assertTrackColor(index, 0.4f, bufferedColor(index))
            assertTrackColor(index, 0.9f, remainingColor(index))
            assertTrackColor(index, 0.75f, Color(0xFF00D400))
        }
        val screenshot = rule.onNodeWithTag("buffer-preview").captureToImage()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.externalCacheDir, "timeline-buffer-progress.png").outputStream().use {
            screenshot.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun invalidAndOversizedBufferPositionsStayInsideTheTrack() {
        showTracks()
        rule.runOnIdle { model.value = BufferTrackModel(bufferedMs = -1L) }
        assertTrackColor(0, 0.4f, Color.Black)
        rule.runOnIdle { model.value = BufferTrackModel(durationMs = 0L, bufferedMs = Long.MAX_VALUE) }
        assertTrackColor(0, 0.4f, Color.Black)
        rule.runOnIdle { model.value = BufferTrackModel(bufferedMs = Long.MAX_VALUE) }
        assertTrackColor(0, 0.9f, bufferedColor(0))
        assertTrackColor(0, 0.75f, Color(0xFF00D400))
    }

    @Test
    fun bufferedSectionUpdatesWhilePlaybackIsPaused() {
        val buffered = AtomicLong(3_000L)
        val observed = AtomicLong(0L)
        val player = pausedBufferPlayer(buffered, observed)
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red)) {
                PlayerTimeBar(
                    player = player,
                    modifier = Modifier.size(320.dp, 40.dp).background(Color.Black),
                )
            }
        }
        rule.waitUntil(3_000L) { observed.get() == 3_000L }
        val track = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        var pixels = track.captureToImage().toPixelMap()
        assertColor(Color.Black, pixels[(pixels.width * 0.6f).toInt(), pixels.height / 2])
        rule.runOnIdle { buffered.set(8_000L) }
        rule.waitUntil(4_000L) { observed.get() == 8_000L }
        pixels = track.captureToImage().toPixelMap()
        assertColor(bufferedColor(0), pixels[(pixels.width * 0.6f).toInt(), pixels.height / 2])
    }

    private fun showTracks() {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.Red)) {
                Column(Modifier.background(Color.Black).padding(16.dp).testTag("buffer-preview")) {
                    listOf("Standard", "Compact", "Expanded").forEachIndexed { index, title ->
                        Text("$title: played 20%, buffered 60%", color = Color.White)
                        TimelineTrack(
                            positionMs = 2_000L,
                            durationMs = model.value.durationMs,
                            bufferedPositionMs = model.value.bufferedMs,
                            segments = listOf(SponsorBlockSegment(
                                7_000L, 8_000L, SponsorCategory.Sponsor, SponsorAction.Skip,
                            )),
                            compact = index == 1,
                            expanded = index == 2,
                            onScrub = {},
                            onScrubFinished = {},
                            onScrubCancelled = {},
                            accessibilityLabel = "Playback position",
                            accessibilityStateDescription = "2 of 10 seconds",
                            modifier = Modifier.size(280.dp, 40.dp).testTag("buffer-track-$index"),
                        )
                    }
                }
            }
        }
    }

    private fun assertTrackColor(index: Int, fraction: Float, expected: Color) {
        val pixels = rule.onNodeWithTag("buffer-track-$index").captureToImage().toPixelMap()
        assertColor(expected, pixels[(pixels.width * fraction).toInt(), pixels.height / 2])
    }

    private fun assertColor(expected: Color, actual: Color) {
        assertEquals("red", expected.red, actual.red, 0.025f)
        assertEquals("green", expected.green, actual.green, 0.025f)
        assertEquals("blue", expected.blue, actual.blue, 0.025f)
    }

    private fun bufferedColor(index: Int) = if (index == 2) Color(0xFFA6A6A6) else Color(0xFF808080)
    private fun remainingColor(index: Int) = if (index == 2) Color(0xFF4D4D4D) else Color.Black
}

private data class BufferTrackModel(val durationMs: Long = 10_000L, val bufferedMs: Long = 6_000L)

private fun pausedBufferPlayer(buffered: AtomicLong, observed: AtomicLong): Player =
    Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "PausedBufferPlayer"
            "getApplicationLooper" -> Looper.getMainLooper()
            "getAvailableCommands" -> Player.Commands.Builder().addAll(
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_TIMELINE,
            ).build()
            "isCommandAvailable" -> true
            "getPlaybackState" -> Player.STATE_READY
            "getDuration" -> 10_000L
            "getCurrentPosition" -> 2_000L
            "getBufferedPosition" -> buffered.get().also(observed::set)
            "getCurrentTimeline" -> Timeline.EMPTY
            "getPlaybackParameters" -> PlaybackParameters.DEFAULT
            else -> when (method.returnType) {
                Boolean::class.javaPrimitiveType -> false
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        }
    } as Player
