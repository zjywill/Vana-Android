package com.pinapia.vana.exercises

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 动作示意图。`files` 里是 `wg-plank-1.svg` 这种资产名，落在 `assets/exercises/`。
 *
 * 多于一张时交替显示——那几帧本来就是同一个动作的起止。素材是白底的，这一层永远垫白。
 *
 * **关掉动效时并排显示，不是退回一张静图。** 那时候「会动」本来就不是可用的信息通道，
 * 但并排仍然说得清先后；退回一张的话，这张卡就又变回了「一张静图说不出方向」的样子，
 * 而那正是这个库当初把单帧动作全部换掉的理由。三帧的只并排头尾两张——一格里挤三张，
 * 每张只剩三分之一宽，谁都看不清。
 */
@Composable
fun ExerciseFigure(
    move: ExerciseMove,
    modifier: Modifier = Modifier,
) {
    val names = move.imageNames
    val reduceMotion = rememberReduceMotion()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(4.dp),
    ) {
        when {
            names.size > 1 && !reduceMotion -> AlternatingFigures(names = names)
            names.size > 1 -> StillFigures(names = names)
            names.size == 1 -> SvgAsset(fileName = names[0], modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

/**
 * 系统里「移除动画」开着没有。
 *
 * Android 这一侧对应 iOS 的 `accessibilityReduceMotion` 的是
 * `Settings.Global.ANIMATOR_DURATION_SCALE`：用户在开发者选项或无障碍设置里把动画关掉时它是 0。
 * 读不到就按「没关」走——猜错的代价是图照常在动，比一张不动的静图轻。
 */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** 关掉动效时的那一版：并排显示头尾，也就是这个动作的起止。 */
@Composable
private fun StillFigures(names: List<String>) {
    val shown = remember(names) { if (names.size > 2) listOf(names.first(), names.last()) else names }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        shown.forEach { name ->
            SvgAsset(fileName = name, modifier = Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun AlternatingFigures(names: List<String>) {
    // **三帧要乒乓着走**（起→中→止→中），不是一路循环：一路循环的话末尾那一下是从「止」
    // 直接跳回「起」，读起来是抽搐而不是一个动作。两张时首尾往返本来就等于原样循环，
    // 所以这一段对 `ek` 那几条是恒等的。
    val frames = remember(names) {
        if (names.size > 2) names + names.drop(1).dropLast(1).reversed() else names
    }
    // 三帧比两态多一档，单帧停留短一点，整个动作走一圈的时长才不会翻倍。
    val interval = if (names.size > 2) 900L else 1_300L
    var step by remember(frames) { mutableIntStateOf(0) }
    LaunchedEffect(frames) {
        while (isActive) {
            delay(interval)
            step = (step + 1) % frames.size
        }
    }
    SvgAsset(
        fileName = frames[step % frames.size],
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SvgAsset(
    fileName: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(fileName) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(fileName) {
        bitmap = withContext(Dispatchers.Default) {
            ExerciseSvg.render(context, fileName, sizePx = 256)
        }
    }
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier,
        )
    }
}

/** `files` 存的是带 `.svg` 后缀的文件名。 */
val ExerciseMove.imageNames: List<String>
    get() = files.map { name ->
        if (name.endsWith(".svg", ignoreCase = true)) name else "$name.svg"
    }

object ExerciseSvg {
    /**
     * 已经画好的那几张。
     *
     * **帧是循环的，所以不缓存等于每一圈都重解一遍 SVG。** 一张图三帧、一屏三张卡，按乒乓的
     * 节奏就是每秒钟解一次；而这几张 SVG 是重描出来的单路径，解析并不便宜。缓存的键是文件名，
     * 内容打在包里不会变。
     *
     * 上限按「一屏撑死几张卡 × 每张几帧」定，超了丢最早的——不设上限的话，用户翻着聊天记录
     * 往回滚，几百张 256×256 的 bitmap 会一直攒着。
     */
    private const val CACHE_LIMIT = 24
    private val cache = object : LinkedHashMap<String, Bitmap>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
            size > CACHE_LIMIT
    }

    fun render(context: Context, fileName: String, sizePx: Int): Bitmap? {
        val key = "$fileName@$sizePx"
        synchronized(cache) { cache[key] }?.let { return it }
        return renderUncached(context, fileName, sizePx)?.also {
            synchronized(cache) { cache[key] = it }
        }
    }

    private fun renderUncached(context: Context, fileName: String, sizePx: Int): Bitmap? {
        return runCatching {
            val svg = context.assets.open("exercises/$fileName").use { SVG.getFromInputStream(it) }
            val picture = svg.renderToPicture()
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val scale = minOf(
                sizePx / picture.width.toFloat().coerceAtLeast(1f),
                sizePx / picture.height.toFloat().coerceAtLeast(1f),
            )
            canvas.translate(
                (sizePx - picture.width * scale) / 2f,
                (sizePx - picture.height * scale) / 2f,
            )
            canvas.scale(scale, scale)
            canvas.drawPicture(picture)
            bitmap
        }.getOrNull()
    }
}
