package app.postmortem.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.LocalActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.postmortem.BuildConfig
import app.postmortem.R
import app.postmortem.UiTags
import app.postmortem.ui.theme.Spacing
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val SCENE_ASSET = "splash-scene.svg"

/**
 * The night scene shown on a cold start. The system start-up screen before it is only the background colour,
 * so this is the first picture. [onFinished] is called after `splash_min_millis`.
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val background = colorResource(R.color.splash_background)
    val text = colorResource(R.color.splash_text)
    val minMillis = integerResource(R.integer.splash_min_millis)
    // The scene is dark in light mode too, so the status and navigation bar icons must be light until it is gone.
    val activity = LocalActivity.current as? ComponentActivity
    DisposableEffect(Unit) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        onDispose { activity?.enableEdgeToEdge() }
    }
    LaunchedEffect(Unit) {
        delay(minMillis.toLong())
        onFinished()
    }
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { testTagsAsResourceId = true }
                .testTag(UiTags.SPLASH_SCREEN),
    ) {
        val context = LocalContext.current
        val widthPx = constraints.maxWidth
        val scene by produceState<ImageBitmap?>(null, widthPx) {
            value = withContext(Dispatchers.Default) { renderScene(context, widthPx) }
        }
        Box(Modifier.fillMaxSize().background(background)) {
            scene?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().align(Alignment.Center).fadeEdges(Spacing.splashFade.value),
                )
            }
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(Spacing.screen),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelMedium,
                    color = text.copy(alpha = VERSION_ALPHA),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.gap),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = Spacing.gap),
                ) {
                    CircularProgressIndicator(Modifier.size(Spacing.splashSpinner), color = text, strokeWidth = 2.dp)
                    Text(stringResource(R.string.splash_loading), style = MaterialTheme.typography.bodySmall, color = text)
                }
            }
        }
    }
}

private const val VERSION_ALPHA = 0.7f

/** Fades the top and bottom [edge] dp of the picture to transparent, so the scene melts into the background at any height. */
private fun Modifier.fadeEdges(edge: Float): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fraction = (edge * density / size.height).coerceIn(0f, 0.5f)
        drawRect(
            brush =
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    fraction to Color.Black,
                    1f - fraction to Color.Black,
                    1f to Color.Transparent,
                ),
            blendMode = BlendMode.DstIn,
        )
    }

private fun renderScene(
    context: Context,
    widthPx: Int,
): ImageBitmap {
    val svg = context.assets.open(SCENE_ASSET).use(SVG::getFromInputStream)
    val scale = widthPx / svg.documentWidth
    val heightPx = (svg.documentHeight * scale).toInt()
    svg.setDocumentWidth(widthPx.toFloat())
    svg.setDocumentHeight(heightPx.toFloat())
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    svg.renderToCanvas(Canvas(bitmap))
    return bitmap.asImageBitmap()
}
