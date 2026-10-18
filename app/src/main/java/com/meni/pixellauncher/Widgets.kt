package com.meni.pixellauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil

// ---------- המארח של הווידג'טים ----------

class LauncherWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?
    ): AppWidgetHostView = LauncherWidgetHostView(context)
}

/** תצוגת ווידג'ט שמזהה לחיצה ארוכה גם כשהתוכן שלו לוחץ בעצמו */
class LauncherWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onLongPress: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var cancelSent = false

    private val longPressRunnable = Runnable {
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    private fun stopLongPressTimer() = handler.removeCallbacks(longPressRunnable)

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                cancelSent = false
                downX = ev.x
                downY = ev.y
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) stopLongPressTimer()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopLongPressTimer()
        }

        if (longPressed) {
            // אחרי לחיצה ארוכה: מבטלים את המגע אצל התוכן של הווידג'ט
            if (!cancelSent) {
                val cancel = MotionEvent.obtain(ev)
                cancel.action = MotionEvent.ACTION_CANCEL
                super.dispatchTouchEvent(cancel)
                cancel.recycle()
                cancelSent = true
            }
            return true
        }
        super.dispatchTouchEvent(ev)
        return true
    }
}

// ---------- גודל ווידג'ט בתאים ----------

fun widgetSpan(context: Context, info: AppWidgetProviderInfo): Pair<Int, Int> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        info.targetCellWidth > 0 && info.targetCellHeight > 0
    ) {
        return info.targetCellWidth.coerceIn(1, MAX_GRID_COLUMNS) to info.targetCellHeight.coerceIn(1, MAX_GRID_ROWS)
    }
    val density = context.resources.displayMetrics.density
    val w = ceil((info.minWidth / density + 30f) / 70f).toInt()
    val h = ceil((info.minHeight / density + 30f) / 70f).toInt()
    return w.coerceIn(1, MAX_GRID_COLUMNS) to h.coerceIn(1, MAX_GRID_ROWS)
}

// ---------- רשימת הווידג'טים הזמינים ----------

class WidgetOption(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val appIcon: ImageBitmap?,
    val label: String,
    val preview: ImageBitmap?,
    val span: Pair<Int, Int>
)

fun loadWidgetOptions(context: Context): List<WidgetOption> {
    val manager = AppWidgetManager.getInstance(context)
    val pm = context.packageManager
    val appIcons = mutableMapOf<String, ImageBitmap?>()
    return manager.installedProviders.map { info ->
        val packageName = info.provider.packageName
        val appLabel = try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            packageName
        }
        val appIcon = appIcons.getOrPut(packageName) {
            try {
                IconRenderer.render(pm.getApplicationIcon(packageName), null, 96).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
        val drawable = try {
            info.loadPreviewImage(context, 0) ?: info.loadIcon(context, 0)
        } catch (_: Exception) {
            null
        }
        WidgetOption(
            info = info,
            appLabel = appLabel,
            appIcon = appIcon,
            label = info.loadLabel(pm) ?: appLabel,
            preview = drawable?.let { toPreviewBitmap(it) },
            span = widgetSpan(context, info)
        )
    }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
}

private fun toPreviewBitmap(drawable: Drawable): ImageBitmap? = try {
    val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 240
    val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 240
    val scale = minOf(1f, 480f / maxOf(w, h))
    drawable.toBitmap(
        (w * scale).toInt().coerceAtLeast(1),
        (h * scale).toInt().coerceAtLeast(1)
    ).asImageBitmap()
} catch (_: Exception) {
    null
}

// ---------- UI ----------

@Composable
fun WidgetCell(
    host: LauncherHost,
    item: HomeItem,
    width: Dp,
    height: Dp,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val longPress by rememberUpdatedState(onLongPress)
    AndroidView(
        factory = { ctx ->
            val view: View = host.widgetView(item.widgetId) ?: FrameLayout(ctx)
            (view.parent as? ViewGroup)?.removeView(view)
            view
        },
        update = { view ->
            (view as? LauncherWidgetHostView)?.onLongPress = { longPress() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && view is AppWidgetHostView) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(width.value, height.value)))
            }
        },
        modifier = modifier.padding(4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetPicker(onPick: (WidgetOption) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var options by remember { mutableStateOf<List<WidgetOption>?>(null) }
    LaunchedEffect(Unit) {
        options = withContext(Dispatchers.IO) { loadWidgetOptions(context) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Text(
            "ווידג'טים",
            fontSize = 24.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
        )

        val list = options
        if (list == null) {
            Box(
                Modifier.fillMaxWidth().height(240.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            val groups = remember(list) { list.groupBy { it.info.provider.packageName }.values.toList() }
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f)
            ) {
                items(groups, key = { it.first().info.provider.packageName }) { group ->
                    WidgetGroup(group, onPick)
                }
            }
        }
    }
}

@Composable
private fun WidgetGroup(group: List<WidgetOption>, onPick: (WidgetOption) -> Unit) {
    val first = group.first()
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
        ) {
            first.appIcon?.let {
                Image(bitmap = it, contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                first.appLabel,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${group.size}",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(group, key = { it.info.provider.flattenToString() }) { option ->
                Column(Modifier.width(168.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onPick(option) }
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        option.preview?.let {
                            Image(
                                bitmap = it,
                                contentDescription = option.label,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        option.label,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${option.span.first} × ${option.span.second}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
