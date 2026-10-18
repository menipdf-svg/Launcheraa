package com.meni.pixellauncher

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.*

// ================= נתונים =================

data class NextEvent(val id: Long, val title: String, val begin: Long)

object GlanceData {

    fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** תאריך עברי, למשל "כ״ז בתשרי תשפ״ז". אחרי השקיעה עובר ליום הבא */
    fun hebrewDate(afterSunset: Boolean): String {
        val cal = Calendar.getInstance()
        if (afterSunset) cal.add(Calendar.DAY_OF_YEAR, 1)
        return runCatching {
            android.icu.text.DateFormat
                .getDateInstance(android.icu.text.DateFormat.LONG, android.icu.util.ULocale("he_IL@calendar=hebrew"))
                .format(cal.time)
        }.getOrDefault("")
    }

    /** המיקום האחרון שהמכשיר מכיר (נשמר, למקרה שאין מיקום חדש) */
    @android.annotation.SuppressLint("MissingPermission")
    fun location(context: Context): Pair<Double, Double>? {
        val prefs = context.getSharedPreferences("glance", Context.MODE_PRIVATE)
        if (hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            val lm = context.getSystemService(LocationManager::class.java)
            val best = runCatching {
                lm?.getProviders(true).orEmpty()
                    .mapNotNull { lm?.getLastKnownLocation(it) }
                    .maxByOrNull { it.time }
            }.getOrNull()
            if (best != null) {
                prefs.edit().putFloat("lat", best.latitude.toFloat()).putFloat("lon", best.longitude.toFloat()).apply()
                return best.latitude to best.longitude
            }
        }
        if (!prefs.contains("lat")) return null
        return prefs.getFloat("lat", 0f).toDouble() to prefs.getFloat("lon", 0f).toDouble()
    }

    /** שעת השקיעה היום (אלגוריתם NOAA המקוצר, דיוק של דקה-שתיים) */
    fun sunset(lat: Double, lon: Double, day: Calendar = Calendar.getInstance()): Calendar? {
        val zenith = 90.833
        val n = day.get(Calendar.DAY_OF_YEAR)
        val lngHour = lon / 15.0
        val t = n + ((18 - lngHour) / 24.0)
        val m = 0.9856 * t - 3.289
        var l = m + 1.916 * sin(Math.toRadians(m)) + 0.020 * sin(Math.toRadians(2 * m)) + 282.634
        l = (l % 360 + 360) % 360
        var ra = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(l))))
        ra = (ra % 360 + 360) % 360
        ra += floor(l / 90) * 90 - floor(ra / 90) * 90
        ra /= 15.0
        val sinDec = 0.39782 * sin(Math.toRadians(l))
        val cosDec = cos(asin(sinDec))
        val cosH = (cos(Math.toRadians(zenith)) - sinDec * sin(Math.toRadians(lat))) /
            (cosDec * cos(Math.toRadians(lat)))
        if (cosH > 1 || cosH < -1) return null
        val h = Math.toDegrees(acos(cosH)) / 15.0
        val ut = ((h + ra - 0.06571 * t - 6.622 - lngHour) % 24 + 24) % 24

        val result = day.clone() as Calendar
        val offsetHours = result.timeZone.getOffset(result.timeInMillis) / 3_600_000.0
        val local = ((ut + offsetHours) % 24 + 24) % 24
        result.set(Calendar.HOUR_OF_DAY, local.toInt())
        result.set(Calendar.MINUTE, ((local - local.toInt()) * 60).roundToInt().coerceAtMost(59))
        result.set(Calendar.SECOND, 0)
        return result
    }

    /** האירוע הבא ביומן ביומיים הקרובים */
    fun nextEvent(context: Context): NextEvent? {
        if (!hasPermission(context, Manifest.permission.READ_CALENDAR)) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + 2 * 86_400_000L)
        }.build()
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN
                ),
                "${CalendarContract.Instances.ALL_DAY} = 0 AND ${CalendarContract.Instances.END} > ?",
                arrayOf(now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                if (c.moveToFirst()) NextEvent(c.getLong(0), c.getString(1) ?: "", c.getLong(2)) else null
            }
        }.getOrNull()
    }
}

// ================= תצוגה =================

private val glanceShadow = Shadow(color = Color.Black.copy(alpha = 0.35f), blurRadius = 6f)

/** השורות מתחת לתאריך: תאריך עברי, שקיעה, האירוע הבא ומה מתנגן */
@Composable
fun GlanceExtras(store: LauncherStore, refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            tick++
        }
    }

    var sunset by remember { mutableStateOf<Calendar?>(null) }
    var event by remember { mutableStateOf<NextEvent?>(null) }
    val wantSunset = store.on(Feature.GLANCE_SUNSET)
    val wantCalendar = store.on(Feature.GLANCE_CALENDAR)
    LaunchedEffect(tick, refreshKey, wantSunset, wantCalendar) {
        val (newSunset, newEvent) = withContext(Dispatchers.IO) {
            val s = if (wantSunset) GlanceData.location(context)?.let { (lat, lon) -> GlanceData.sunset(lat, lon) } else null
            val e = if (wantCalendar) GlanceData.nextEvent(context) else null
            s to e
        }
        sunset = newSunset
        event = newEvent
    }

    val now = remember(tick) { Calendar.getInstance() }
    val afterSunset = sunset?.let { now.after(it) } == true
    val timeFormat = remember { java.text.SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val style = TextStyle(fontSize = 15.sp, color = Color.White.copy(alpha = 0.92f), shadow = glanceShadow)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // תאריך עברי · שקיעה
        val hebrew = remember(tick, afterSunset) { GlanceData.hebrewDate(afterSunset) }
        val parts = buildList {
            if (store.on(Feature.GLANCE_HEBREW_DATE) && hebrew.isNotBlank()) add(hebrew)
            sunset?.let { add("שקיעה ${timeFormat.format(it.time)}") }
        }
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = style)

        // האירוע הבא
        event?.let { e ->
            val cal = Calendar.getInstance().apply { timeInMillis = e.begin }
            val day = when (cal.get(Calendar.DAY_OF_YEAR) - now.get(Calendar.DAY_OF_YEAR)) {
                0 -> "היום"
                1 -> "מחר"
                else -> java.text.SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(e.begin))
            }
            Text(
                "📅  $day ${timeFormat.format(Date(e.begin))} · ${e.title}",
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            )
        }

        // מתנגן עכשיו
        val playing = NotifService.nowPlaying.value
        if (store.on(Feature.GLANCE_MEDIA) && playing != null) {
            Spacer(Modifier.height(4.dp))
            NowPlayingCard(playing)
        }
    }
}

@Composable
private fun NowPlayingCard(playing: NowPlaying) {
    val context = LocalContext.current
    val controls = playing.controller.transportControls

    // המיקום בשיר, מתעדכן בזמן נגינה
    var position by remember(playing.title) { mutableLongStateOf(playing.positionNow()) }
    var dragFraction by remember(playing.title) { mutableFloatStateOf(-1f) }
    LaunchedEffect(playing.title, playing.isPlaying) {
        while (true) {
            position = playing.positionNow()
            delay(500)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.packageManager.getLaunchIntentForPackage(playing.packageName)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val art = remember(playing.art) { playing.art?.asImageBitmap() }
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        playing.title,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (playing.artist.isNotBlank()) {
                        Text(
                            playing.artist,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // כפתורי המדיה תמיד באותו סדר: קודם משמאל, הבא מימין
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MediaButton(MediaGlyph.PREVIOUS) { controls.skipToPrevious() }
                        MediaButton(if (playing.isPlaying) MediaGlyph.PAUSE else MediaGlyph.PLAY, large = true) {
                            if (playing.isPlaying) controls.pause() else controls.play()
                        }
                        MediaButton(MediaGlyph.NEXT) { controls.skipToNext() }
                    }
                }
            }

            // סרגל התקדמות שאפשר לגרור בו
            val duration = playing.duration
            if (duration > 0) {
                val shown = if (dragFraction >= 0f) (dragFraction * duration).toLong() else position
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Column(Modifier.padding(top = 2.dp)) {
                        SeekBar(
                            fraction = (shown.toFloat() / duration).coerceIn(0f, 1f),
                            onDrag = { dragFraction = it },
                            onCommit = {
                                controls.seekTo((it * duration).toLong())
                                position = (it * duration).toLong()
                                dragFraction = -1f
                            }
                        )
                        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
                            Text(
                                formatTime(shown),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "-" + formatTime(duration - shown),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * סרגל בסגנון אפל מיוזיק: קפסולה דקה בלי כפתור עגול,
 * שמתעבה כשנוגעים בה. לחיצה קופצת למקום, וגרירה מזיזה.
 */
@Composable
private fun SeekBar(fraction: Float, onDrag: (Float) -> Unit, onCommit: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val barHeight by animateDpAsState(if (dragging) 11.dp else 6.dp, label = "seekHeight")
    val trackAlpha by animateFloatAsState(if (dragging) 0.35f else 0.22f, label = "seekTrack")
    val fillAlpha by animateFloatAsState(if (dragging) 1f else 0.85f, label = "seekFill")

    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onCommit((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragged = (offset.x / size.width).coerceIn(0f, 1f)
                        onDrag(dragged)
                    },
                    onDragEnd = {
                        dragging = false
                        onCommit(dragged)
                    },
                    onDragCancel = {
                        dragging = false
                        onCommit(dragged)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragged = (change.position.x / size.width).coerceIn(0f, 1f)
                        onDrag(dragged)
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = trackAlpha))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0.001f, 1f))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = fillAlpha))
            )
        }
    }
}

/** 03:45 או 1:03:45 */
private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private enum class MediaGlyph { PLAY, PAUSE, NEXT, PREVIOUS }

/** כפתורי מדיה מצוירים (אין אותם בחבילת האייקונים הבסיסית) */
@Composable
private fun MediaButton(glyph: MediaGlyph, large: Boolean = false, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    val background = if (large) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Box(
        Modifier
            .size(if (large) 42.dp else 36.dp)
            .clip(CircleShape)
            .then(Modifier.clickable(onClick = onClick))
            .then(Modifier),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .size(if (large) 42.dp else 36.dp)
        ) {
            if (large) drawCircle(background)
            val s = size.minDimension * 0.34f
            val c = center
            fun triangle(left: Float, pointRight: Boolean) = Path().apply {
                if (pointRight) {
                    moveTo(left, c.y - s / 2); lineTo(left + s * 0.8f, c.y); lineTo(left, c.y + s / 2)
                } else {
                    moveTo(left + s * 0.8f, c.y - s / 2); lineTo(left, c.y); lineTo(left + s * 0.8f, c.y + s / 2)
                }
                close()
            }
            when (glyph) {
                MediaGlyph.PLAY -> drawPath(triangle(c.x - s * 0.35f, true), color)
                MediaGlyph.PAUSE -> {
                    val w = s * 0.28f
                    drawRect(color, Offset(c.x - s * 0.4f, c.y - s / 2), androidx.compose.ui.geometry.Size(w, s))
                    drawRect(color, Offset(c.x + s * 0.12f, c.y - s / 2), androidx.compose.ui.geometry.Size(w, s))
                }
                MediaGlyph.NEXT -> {
                    drawPath(triangle(c.x - s * 0.55f, true), color)
                    drawRect(color, Offset(c.x + s * 0.3f, c.y - s / 2), androidx.compose.ui.geometry.Size(s * 0.16f, s))
                }
                MediaGlyph.PREVIOUS -> {
                    drawRect(color, Offset(c.x - s * 0.46f, c.y - s / 2), androidx.compose.ui.geometry.Size(s * 0.16f, s))
                    drawPath(triangle(c.x - s * 0.25f, false), color)
                }
            }
        }
    }
}
