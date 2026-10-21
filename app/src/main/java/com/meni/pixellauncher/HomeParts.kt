package com.meni.pixellauncher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.ceil
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "במבט חטוף" כמו בפיקסל: רק התאריך, נקי. לחיצה פותחת את היומן */
@Composable
fun AtAGlance(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    weather: Weather? = null,
    onWeatherClick: () -> Unit = {}
) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(30_000)
        }
    }
    val date = remember(now) { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now) }
    val style = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Normal,
        color = Color.White,
        shadow = Shadow(color = Color.Black.copy(alpha = 0.3f), blurRadius = 6f)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 28.dp, bottom = 12.dp)
    ) {
        Text(
            text = date,
            style = style,
            // בגופן מוגדל התאריך מתקצר במקום לדחוק את מזג האוויר מחוץ למסך
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                )
        )
        if (weather != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onWeatherClick
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${weather.label}, ${weather.temp} מעלות"
                    }
            ) {
                Text("  ·  ", style = style, maxLines = 1)
                // סמל מצויר במקום אימוג'י, כדי שייראה זהה בכל מכשיר
                WeatherGlyph(weather.code, Modifier.size(24.dp))
                Spacer(Modifier.width(6.dp))
                Text("${weather.temp}°", style = style, maxLines = 1)
            }
        }
    }
}

/** שורת החיפוש מתחת ל-Dock */
@Composable
fun SearchPill(onClick: () -> Unit, modifier: Modifier = Modifier, opacity: Float = 0.92f) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = opacity),
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp)
        ) {
            Icon(
                Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "חיפוש באפליקציות",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 15.sp
            )
        }
    }
}

/** שורה ברשימת המגירה: כותרת קטגוריה או אפליקציה */
private sealed interface DrawerEntry {
    data class Header(val category: DrawerGroup) : DrawerEntry
    data class App(val app: AppInfo) : DrawerEntry
    data class Folder(val category: DrawerGroup, val apps: List<AppInfo>) : DrawerEntry
}

private const val FOLDER_COLUMNS = 4
private const val FOLDER_ROWS = 3


@Composable
fun AppDrawer(
    apps: List<AppInfo>,
    autoFocusSearch: Boolean,
    categories: Map<String, DrawerGroup>?,
    folderMode: Boolean,
    columns: Int,
    backgroundOpacity: Float,
    iconSize: Dp,
    iconShape: IconShape,
    categoryProgress: Pair<Int, Int>?,
    onClose: () -> Unit,
    onSubmit: (AppInfo) -> Unit,
    onRenameFolder: (DrawerGroup) -> Unit,
    suggestions: List<AppInfo> = emptyList(),
    searchExtras: @Composable (String) -> Unit = {},
    suggestionContent: @Composable (AppInfo) -> Unit = {},
    onDeleteFolder: (DrawerGroup) -> Unit,
    dragActive: Boolean = false,
    onFolderDrag: (DrawerGroup, List<AppInfo>, Offset) -> Unit = { _, _, _ -> },
    onFolderToHome: (DrawerGroup, List<AppInfo>) -> Unit = { _, _ -> },
    onItemMove: (Offset) -> Unit = {},
    onItemRelease: () -> Unit = {},
    appContent: @Composable (AppInfo) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var openCategory by remember { mutableStateOf<DrawerGroup?>(null) }
    BackHandler(enabled = openCategory != null) { openCategory = null }
    val filtered = remember(apps, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) apps
        else {
            // דירוג: שם שמתחיל במה שהוקלד קודם, אחר כך מילה שמתחילה בו, ואחר כך כל התאמה
            fun rank(name: String): Int {
                val n = name.lowercase()
                return when {
                    n.startsWith(q) -> 0
                    n.split(' ', '-', '.', '_').any { it.startsWith(q) } -> 1
                    n.contains(q) -> 2
                    else -> 3
                }
            }
            apps.asSequence()
                .map { it to minOf(rank(it.label), rank(it.originalLabel)) }
                .filter { it.second < 3 }
                .sortedBy { it.second } // יציב: בתוך כל דרגה נשמר הסדר לפי א-ב
                .map { it.first }
                .toList()
        }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val close by rememberUpdatedState(onClose)

    LaunchedEffect(Unit) {
        if (autoFocusSearch) {
            // פריים אחד מספיק כדי שהשדה יהיה מוכן; המקלדת עולה מיד עם פתיחת המגירה
            withFrameNanos { }
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { keyboard?.hide() }
    }

    // משיכה למטה כשהרשימה כבר בראש סוגרת את המגירה
    val pullToClose = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled > 180f) {
                        pulled = 0f
                        close()
                    }
                } else if (consumed.y != 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }

    // ---------- קטגוריות ----------
    val sections = remember(filtered, categories, query) {
        if (categories == null || query.isNotBlank()) {
            null
        } else {
            filtered
                .groupBy { categories[it.packageName] ?: DrawerGroup.OTHER }
                .toSortedMap(compareBy<DrawerGroup, String>(AlphabeticalOrder.collator) { it.label }.thenBy { it.id })
        }
    }
    val entries: List<DrawerEntry> = remember(filtered, sections, folderMode) {
        if (sections == null) {
            filtered.map<AppInfo, DrawerEntry> { DrawerEntry.App(it) }
        } else if (folderMode) {
            // תיקייה שנוצרה על ידך מוצגת תמיד, גם עם אפליקציה אחת
            val isFolder = { group: DrawerGroup, size: Int -> size >= 2 || group.isCustom || group.isBrand }
            // תיקיות ואפליקציות בודדות ממוינות יחד לפי א-ב
            val folders = sections.filter { isFolder(it.key, it.value.size) }
                .map { (category, list) -> category.label to DrawerEntry.Folder(category, list) }
            val singles = sections.filter { !isFolder(it.key, it.value.size) }
                .flatMap { it.value }
                .map { it.label to DrawerEntry.App(it) }
            (folders + singles)
                .sortedWith(compareBy<Pair<String, DrawerEntry>, String>(AlphabeticalOrder.collator) { it.first })
                .map<Pair<String, DrawerEntry>, DrawerEntry> { it.second }
        } else {
            buildList<DrawerEntry> {
                sections.forEach { (category, list) ->
                    add(DrawerEntry.Header(category))
                    list.forEach { add(DrawerEntry.App(it)) }
                }
            }
        }
    }
    // סרגל א-ב: אות ← מקום ראשון ברשימה. רק כשהרשימה ממוינת לפי א-ב
    val letterIndex = remember(entries, folderMode, sections) {
        if (sections != null && !folderMode) emptyList() else {
            val seen = LinkedHashMap<String, Int>()
            entries.forEachIndexed { index, entry ->
                val label = when (entry) {
                    is DrawerEntry.App -> entry.app.label
                    is DrawerEntry.Folder -> entry.category.label
                    is DrawerEntry.Header -> null
                }
                val first = label?.trimStart()?.firstOrNull() ?: return@forEachIndexed
                val letter = if (first.isLetter()) first.uppercaseChar().toString() else "#"
                seen.putIfAbsent(letter, index)
            }
            seen.toList()
        }
    }
    val headerIndex = remember(entries) {
        val map = LinkedHashMap<DrawerGroup, Int>()
        entries.forEachIndexed { index, entry ->
            if (entry is DrawerEntry.Header) map[entry.category] = index
        }
        map
    }
    val gridState = rememberLazyGridState()

    // ---------- תיקייה פתוחה (כמו ב-Lawnchair) ----------
    val folderBounds = remember { mutableMapOf<String, Rect>() }
    var containerBounds by remember { mutableStateOf(Rect.Zero) }
    var shownCategory by remember { mutableStateOf<DrawerGroup?>(null) }
    var folderAnchor by remember { mutableStateOf(Rect.Zero) }
    val folderProgress = remember { Animatable(0f) }
    LaunchedEffect(openCategory) {
        val category = openCategory
        if (category != null) {
            folderAnchor = folderBounds[category.id] ?: Rect.Zero
            shownCategory = category
            folderProgress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 420f))
        } else if (shownCategory != null) {
            folderProgress.animateTo(0f, tween(durationMillis = 200))
            shownCategory = null
        }
    }
    LaunchedEffect(query) { if (query.isNotBlank()) openCategory = null }
    val chipsState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentCategory by remember(headerIndex) {
        derivedStateOf {
            headerIndex.entries.lastOrNull { it.value <= gridState.firstVisibleItemIndex }?.key
        }
    }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = backgroundOpacity)
    ) {
      Box(
          Modifier
              .fillMaxSize()
              .onGloballyPositioned { containerBounds = it.boundsInRoot() }
      ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {
            // אזור שקוף בראש המגירה: החלקה למטה סוגרת
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (total > 60f) close() },
                            onVerticalDrag = { change, delta ->
                                change.consume()
                                total += delta
                            }
                        )
                    }
            )

            TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = {
                    Text("חיפוש באפליקציות", color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = if (query.isNotEmpty()) {
                    @Composable {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "ניקוי")
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = {
                    filtered.firstOrNull()?.let {
                        keyboard?.hide()
                        onSubmit(it)
                    }
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .focusRequester(focusRequester)
            )

            Spacer(Modifier.height(8.dp))

            // הצ'יפ של הקטגוריה הנוכחית תמיד נראה
            LaunchedEffect(currentCategory) {
                val index = headerIndex.keys.indexOf(currentCategory)
                if (index >= 0) chipsState.animateScrollToItem(index)
            }

            if (sections != null && !folderMode && headerIndex.isNotEmpty()) {
                LazyRow(
                    state = chipsState,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    items(headerIndex.keys.toList(), key = { it.id }) { category ->
                        FilterChip(
                            selected = category == currentCategory,
                            onClick = {
                                scope.launch {
                                    gridState.animateScrollToItem(headerIndex[category] ?: 0)
                                }
                            },
                            label = { Text(category.label) },
                            shape = RoundedCornerShape(50)
                        )
                    }
                }
            }

            if (categoryProgress != null && categories != null) {
                val (done, total) = categoryProgress
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "ממיין לפי Google Play · $done/$total",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // בלי חיפוש: הצעות חכמות. בחיפוש, התוצאות הנוספות מופיעות מתחת לאפליקציות
            if (query.isBlank() && suggestions.isNotEmpty() && openCategory == null) {
                Text(
                    "הצעות",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                    val shown = suggestions.take(columns)
                    shown.forEach { app ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { suggestionContent(app) }
                    }
                    repeat(columns - shown.size) { Spacer(Modifier.weight(1f)) }
                }
                HorizontalDivider(
                    Modifier.padding(horizontal = 22.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }

            if (filtered.isEmpty() && query.isNotBlank()) {
                // אין אפליקציה מתאימה, ולכן מוצגות רק התוצאות הנוספות
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "לא נמצאו אפליקציות",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 32.dp, bottom = 8.dp)
                    )
                    searchExtras(query.trim())
                }
            } else {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .nestedScroll(pullToClose)
                ) {
                    items(
                        entries,
                        key = { entry ->
                            when (entry) {
                                is DrawerEntry.Header -> "header:${entry.category.id}"
                                is DrawerEntry.Folder -> "folder:${entry.category.id}"
                                is DrawerEntry.App -> entry.app.key
                            }
                        },
                        span = { entry ->
                            if (entry is DrawerEntry.Header) {
                                GridItemSpan(maxLineSpan)
                            } else {
                                GridItemSpan(1)
                            }
                        },
                        contentType = { entry ->
                            when (entry) {
                                is DrawerEntry.Header -> 0
                                is DrawerEntry.Folder -> 2
                                is DrawerEntry.App -> 1
                            }
                        }
                    ) { entry ->
                        when (entry) {
                            is DrawerEntry.Header -> Text(
                                entry.category.label,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, top = 14.dp)
                            )
                            is DrawerEntry.Folder -> Box(
                                Modifier.fillMaxWidth().then(if (query.isBlank()) Modifier.animateItem() else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                FolderTile(
                                    entry,
                                    iconSize = iconSize,
                                    shape = iconShape.toComposeShape(),
                                    hidden = entry.category.id == shownCategory?.id,
                                    dragActive = dragActive,
                                    onBounds = { folderBounds[entry.category.id] = it },
                                    onClick = { openCategory = entry.category },
                                    onRename = { onRenameFolder(entry.category) },
                                    onDelete = { onDeleteFolder(entry.category) },
                                    onAddToHome = { onFolderToHome(entry.category, entry.apps) },
                                    onDragStart = { root -> onFolderDrag(entry.category, entry.apps, root) },
                                    onMove = onItemMove,
                                    onRelease = onItemRelease
                                )
                            }
                            is DrawerEntry.App -> Box(
                                Modifier.fillMaxWidth().then(if (query.isBlank()) Modifier.animateItem() else Modifier),
                                contentAlignment = Alignment.Center
                            ) {
                                appContent(entry.app)
                            }
                        }
                    }

                    // תוצאות נוספות: מחשבון, המרות, אנשי קשר, הגדרות ואינטרנט.
                    // תמיד מתחת לאפליקציות, כדי שהאפליקציות יופיעו ראשונות
                    if (query.isNotBlank()) {
                        item(
                            key = "search-extras",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = 3
                        ) {
                            searchExtras(query.trim())
                        }
                    }
                }
            }
        }

        // סרגל האותיות מוצג רק כשיש בו טעם: רשימה ארוכה, בלי חיפוש ובלי תיקייה פתוחה
        if (query.isBlank() && openCategory == null && letterIndex.size >= 6 && entries.size >= 30) {
            AlphabetScroller(
                letters = letterIndex,
                modifier = Modifier.align(Alignment.CenterEnd),
                onJump = { index -> scope.launch { gridState.scrollToItem(index) } }
            )
        }

        val folder = entries.firstOrNull {
            it is DrawerEntry.Folder && it.category.id == shownCategory?.id
        } as? DrawerEntry.Folder
        if (folder != null) {
            FolderOverlay(
                folder = folder,
                anchor = folderAnchor,
                container = containerBounds,
                drawerColumns = columns,
                iconSize = iconSize,
                progress = { folderProgress.value },
                onDismiss = { openCategory = null },
                appContent = appContent
            )
        }
      }
    }
}

/**
 * סרגל אותיות בצד המגירה, לקפיצה מהירה. הגרירה ממופה על כל האותיות,
 * גם כשמוצגת רק חלק מהן, ובועה מציגה את האות הנוכחית.
 */
@Composable
private fun AlphabetScroller(
    letters: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    onJump: (Int) -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var active by remember { mutableIntStateOf(-1) }
    val haptics = LocalHapticFeedback.current
    // לא כל האותיות נכנסות לגובה המסך, ולכן מוצגת דגימה מתוכן
    val step = maxOf(1, (letters.size + 23) / 24)
    val shown = letters.filterIndexed { index, _ -> index % step == 0 }

    Box(modifier.fillMaxHeight(0.78f), contentAlignment = Alignment.CenterEnd) {
        if (dragging && active in letters.indices) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 34.dp).size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        letters[active].first,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier
                .fillMaxHeight()
                .width(40.dp)
                .semantics { contentDescription = "גלילה מהירה לפי אותיות" }
                .pointerInput(letters) {
                    fun jump(y: Float) {
                        if (size.height <= 0) return
                        val fraction = (y / size.height).coerceIn(0f, 0.999f)
                        val index = (fraction * letters.size).toInt().coerceIn(0, letters.size - 1)
                        if (index != active) {
                            active = index
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onJump(letters[index].second)
                        }
                    }
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            jump(offset.y)
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            jump(change.position.y)
                        }
                    )
                }
                .pointerInput(letters) {
                    detectTapGestures { offset ->
                        if (size.height > 0) {
                            val fraction = (offset.y / size.height).coerceIn(0f, 0.999f)
                            val index = (fraction * letters.size).toInt().coerceIn(0, letters.size - 1)
                            active = index
                            onJump(letters[index].second)
                        }
                    }
                }
        ) {
            shown.forEach { (letter, _) ->
                Text(
                    letter,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** אייקון תיקייה: עיגול עם 4 אייקונים קטנים */
@Composable
private fun FolderTile(
    folder: DrawerEntry.Folder,
    iconSize: Dp,
    shape: Shape,
    hidden: Boolean,
    dragActive: Boolean,
    onBounds: (Rect) -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAddToHome: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // ברגע שהאצבע זזה התפריט נסגר, והתיקייה נגררת למסך הבית
    LaunchedEffect(dragActive) { if (dragActive) menuOpen = false }
  Box {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .launcherItemGestures(
                key = folder.category.id,
                onClick = onClick,
                onLongPress = { root ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                    onDragStart(root)
                },
                onMove = onMove,
                onRelease = onRelease
            )
            .padding(horizontal = 4.dp, vertical = 6.dp)
    ) {
        val badges = LocalIconBadges.current
        val hasNotifications = badges.dots && folder.apps.any { (NotifService.counts[it.packageName] ?: 0) > 0 }
      Box(Modifier.graphicsLayer { alpha = if (hidden) 0f else 1f }) {
        Box(
            Modifier
                .size(iconSize)
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                folder.apps.take(4).chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { app ->
                            Image(
                                bitmap = app.icon,
                                contentDescription = null,
                                modifier = Modifier.size(iconSize / 3)
                            )
                        }
                    }
                }
            }
        }
        if (hasNotifications) NotificationDot(Modifier.align(Alignment.TopEnd), iconSize)
      }
        Spacer(Modifier.height(6.dp))
        Text(
            text = folder.category.label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 72.dp)
        )
    }

    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        DropdownMenuItem(
            text = { Text("הוספה למסך הבית") },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            onClick = {
                menuOpen = false
                onAddToHome()
            }
        )
        if (folder.category.isCustom) {
            DropdownMenuItem(
                text = { Text("שינוי שם") },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onRename()
                }
            )
            DropdownMenuItem(
                text = { Text("מחיקת התיקייה") },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        } else {
            DropdownMenuItem(
                text = { Text("פתיחה") },
                onClick = {
                    menuOpen = false
                    onClick()
                }
            )
        }
    }
  }
}

private fun lerp(start: Float, stop: Float, fraction: Float) = start + (stop - start) * fraction

/**
 * תיקייה פתוחה כמו ב-Lawnchair: כרטיס שגדל מתוך האייקון ונפתח מעל הרשת,
 * בלי להזיז שום דבר. 4×3 אפליקציות בכל דף, נקודות ושם התיקייה למטה.
 * לחיצה מחוץ לכרטיס מכווצת אותו חזרה לתוך האייקון.
 */
@Composable
private fun FolderOverlay(
    folder: DrawerEntry.Folder,
    anchor: Rect,
    container: Rect,
    drawerColumns: Int,
    iconSize: Dp,
    progress: () -> Float,
    onDismiss: () -> Unit,
    appContent: @Composable (AppInfo) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            // לחיצה מחוץ לתיקייה סוגרת אותה
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .graphicsLayer { alpha = progress() }
            .background(Color.Black.copy(alpha = 0.06f))
    ) {
        val density = LocalDensity.current
        // הגודל של המגירה, כפי שנמדד
        val maxWidth = with(density) { container.width.toDp() }
        val maxHeight = with(density) { container.height.toDp() }
        val pageSize = FOLDER_COLUMNS * FOLDER_ROWS
        val pages = maxOf(1, ceil(folder.apps.size / pageSize.toFloat()).toInt())
        val rows = if (pages > 1) FOLDER_ROWS
            else ceil(folder.apps.size / FOLDER_COLUMNS.toFloat()).toInt().coerceIn(1, FOLDER_ROWS)

        val margin = 16.dp
        val cellWidth = (maxWidth - 20.dp) / maxOf(drawerColumns, FOLDER_COLUMNS)
        val cellHeight = iconSize + 38.dp
        val panelWidth = minOf(cellWidth * FOLDER_COLUMNS + 24.dp, maxWidth - margin * 2)
        val panelHeight = 16.dp + cellHeight * rows + 56.dp

        // מיקום: ממורכז על האייקון עצמו, ולא חורג מהמסך
        val hasAnchor = anchor != Rect.Zero
        val anchorCenterX = if (hasAnchor)
            with(density) { (anchor.center.x - container.left).toDp() } else maxWidth / 2
        val anchorCenterY = if (hasAnchor)
            with(density) { (anchor.center.y - container.top).toDp() } else maxHeight / 2
        val left = (anchorCenterX - panelWidth / 2)
            .coerceIn(margin, maxOf(margin, maxWidth - panelWidth - margin))
        // מרכוז אנכי סביב האייקון, כדי שהתיקייה תתרחב במקום שבו נלחצה
        val top = (anchorCenterY - panelHeight / 2)
            .coerceIn(8.dp, maxOf(8.dp, maxHeight - panelHeight - margin))

        val panelWidthPx = with(density) { panelWidth.toPx() }
        val panelHeightPx = with(density) { panelHeight.toPx() }
        val iconPx = with(density) { iconSize.toPx() }
        val cornerPx = with(density) { 28.dp.toPx() }
        // נקודת ההתרחבות היא מרכז האייקון. בלי הגבלה ל-0..1, כדי שגם כשהכרטיס
        // נדחף פנימה מקצה המסך ההתרחבות תתחיל בדיוק מהתיקייה ולא תקפוץ
        val origin = TransformOrigin(
            (anchorCenterX - left) / panelWidth,
            (anchorCenterY - top) / panelHeight
        )
        val pagerState = rememberPagerState { pages }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                // נעיצה לפינה השמאלית, כדי שהמיקום יהיה נכון גם בעברית (RTL)
                .align(AbsoluteAlignment.TopLeft)
                .absoluteOffset(left, top)
                .size(panelWidth, panelHeight)
                .graphicsLayer {
                    val p = progress()
                    // מתחיל בגודל של האייקון וגדל לגודל המלא
                    scaleX = lerp(iconPx / panelWidthPx, 1f, p)
                    scaleY = lerp(iconPx / panelHeightPx, 1f, p)
                    transformOrigin = origin
                    shape = RoundedCornerShape(lerp(panelHeightPx / 2f, cornerPx, p))
                    clip = true
                    shadowElevation = 10.dp.toPx() * p
                }
                // לחיצה בתוך התיקייה לא סוגרת אותה
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ((progress() - 0.35f) / 0.65f).coerceIn(0f, 1f) }
                    .padding(top = 16.dp)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(cellHeight * rows)
                ) { page ->
                    val pageApps = folder.apps.drop(page * pageSize).take(pageSize)
                    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        pageApps.chunked(FOLDER_COLUMNS).forEach { row ->
                            Row(Modifier.fillMaxWidth().height(cellHeight)) {
                                row.forEach { app ->
                                    Box(
                                        Modifier.weight(1f).fillMaxHeight(),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        appContent(app)
                                    }
                                }
                                repeat(FOLDER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                // שורה תחתונה: שם התיקייה ונקודות הדפים
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        folder.category.label,
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (pages > 1) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            repeat(pages) { i ->
                                Box(
                                    Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (i == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                                        )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
