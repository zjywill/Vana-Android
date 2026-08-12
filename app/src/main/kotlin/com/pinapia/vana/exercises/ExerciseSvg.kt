package com.pinapia.vana.exercises

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
 * 动作示意图。`files` 里是 `ek-0001-tension.svg` 这种资产名，落在 `assets/exercises/`。
 *
 * 两张时交替显示——两态本来就是同一个动作的起止。素材是白底的，这一层永远垫白。
 */
@Composable
fun ExerciseFigure(
    move: ExerciseMove,
    modifier: Modifier = Modifier,
) {
    val names = move.imageNames
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(4.dp),
    ) {
        when {
            names.size > 1 -> AlternatingFigures(names = names)
            names.size == 1 -> SvgAsset(fileName = names[0], modifier = Modifier.fillMaxSize())
            else -> Box(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun AlternatingFigures(names: List<String>) {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(names) {
        while (isActive) {
            delay(1_300)
            step = (step + 1) % names.size
        }
    }
    SvgAsset(
        fileName = names[step % names.size],
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
    fun render(context: Context, fileName: String, sizePx: Int): Bitmap? {
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
