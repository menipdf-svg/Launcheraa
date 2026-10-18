package com.meni.pixellauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** המסך הסודי: רק האפליקציות שהוסתרו */
@Composable
fun HiddenAppsScreen(
    apps: List<AppInfo>,
    columns: Int,
    iconSize: Dp,
    onLaunch: (AppInfo) -> Unit,
    onUnhide: (AppInfo) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var openMenu by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    "אפליקציות מוסתרות",
                    fontSize = 26.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "סגירה")
                }
            }

            if (apps.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "אין אפליקציות מוסתרות.\nכדי להסתיר: לחיצה ארוכה על אפליקציה ← הסתרה",
                        textAlign = TextAlign.Center,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Text(
                    "לחיצה ארוכה על אפליקציה כדי להחזיר אותה",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(apps, key = { it.key }) { app ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            AppIcon(
                                app = app,
                                iconSize = iconSize,
                                showLabel = true,
                                labelColor = MaterialTheme.colorScheme.onSurface,
                                labelShadow = false,
                                hidden = false,
                                menuOpen = openMenu == app.key,
                                menuActions = {
                                    listOf(
                                        MenuAction("הצגה בלאנצ'ר", vector = Icons.Outlined.Lock) { onUnhide(app) },
                                        MenuAction("פרטי האפליקציה", vector = Icons.Outlined.Info) {
                                            AppsRepository.openAppInfo(context, app.packageName)
                                        }
                                    )
                                },
                                onDismissMenu = { openMenu = null },
                                onClick = { onLaunch(app) },
                                onLongPress = { openMenu = app.key },
                                onMove = {},
                                onRelease = {}
                            )
                        }
                    }
                }
            }
        }
    }
}
