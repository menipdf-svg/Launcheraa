package com.meni.pixellauncher

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

// ================= מחשבון =================

/** מחשבון פשוט: + - * / % ^ וסוגריים. מחזיר null אם זה לא תרגיל */
object Calculator {
    private val ALLOWED = Regex("^[\\d\\s+\\-*/×÷().,%^]+$")

    fun evaluate(input: String): String? {
        val text = input.replace('×', '*').replace('÷', '/').replace(',', '.').replace(" ", "")
        if (!ALLOWED.matches(input) || text.none { it in "+-*/%^" } || text.none { it.isDigit() }) return null
        // מספר שלילי לבד (למשל "-5") הוא לא תרגיל
        if (text.trimStart('-').none { it in "+-*/%^" }) return null
        return runCatching {
            val parser = Parser(text)
            val value = parser.expression()
            if (parser.pos != text.length || value.isNaN() || value.isInfinite()) null else format(value)
        }.getOrNull()
    }

    fun format(value: Double): String =
        if (abs(value - value.roundToLong()) < 1e-9 && abs(value) < 1e15) value.roundToLong().toString()
        else "%.6f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var pos = 0
        private fun peek() = if (pos < s.length) s[pos] else '\u0000'

        fun expression(): Double {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[pos++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = power()
            while (peek() == '*' || peek() == '/' || peek() == '%') {
                val op = s[pos++]
                val r = power()
                v = when (op) {
                    '*' -> v * r
                    '/' -> v / r
                    else -> v % r
                }
            }
            return v
        }

        private fun power(): Double {
            val base = unary()
            if (peek() == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double {
            if (peek() == '-') {
                pos++
                return -unary()
            }
            if (peek() == '+') {
                pos++
                return unary()
            }
            return primary()
        }

        private fun primary(): Double {
            if (peek() == '(') {
                pos++
                val v = expression()
                require(peek() == ')')
                pos++
                return v
            }
            val start = pos
            while (peek().isDigit() || peek() == '.') pos++
            require(pos > start)
            return s.substring(start, pos).toDouble()
        }
    }
}

// ================= המרות =================

object Converter {
    private enum class Dim { LENGTH, MASS, VOLUME, TEMP, CURRENCY }

    private class UnitDef(val id: String, val dim: Dim, val factor: Double, val symbol: String, vararg val aliases: String)

    private val UNITS = listOf(
        UnitDef("km", Dim.LENGTH, 1000.0, "ק\"מ", "km", "ק\"מ", "קמ", "קילומטר", "קילומטרים"),
        UnitDef("m", Dim.LENGTH, 1.0, "מ'", "m", "מטר", "מטרים", "מ'"),
        UnitDef("cm", Dim.LENGTH, 0.01, "ס\"מ", "cm", "ס\"מ", "סמ", "סנטימטר", "סנטימטרים"),
        UnitDef("mm", Dim.LENGTH, 0.001, "מ\"מ", "mm", "מ\"מ", "ממ", "מילימטר"),
        UnitDef("mi", Dim.LENGTH, 1609.344, "מייל", "mi", "mile", "miles", "מייל", "מיילים"),
        UnitDef("ft", Dim.LENGTH, 0.3048, "רגל", "ft", "feet", "foot", "רגל", "פיט"),
        UnitDef("in", Dim.LENGTH, 0.0254, "אינץ'", "in", "inch", "inches", "אינץ", "אינץ'", "\""),
        UnitDef("kg", Dim.MASS, 1.0, "ק\"ג", "kg", "ק\"ג", "קג", "קילו", "קילוגרם"),
        UnitDef("g", Dim.MASS, 0.001, "גרם", "g", "גרם", "ג'"),
        UnitDef("lb", Dim.MASS, 0.45359237, "ליברה", "lb", "lbs", "pound", "pounds", "ליברה", "ליברות"),
        UnitDef("oz", Dim.MASS, 0.0283495, "אונקיה", "oz", "אונקיה", "אונקיות"),
        UnitDef("l", Dim.VOLUME, 1.0, "ליטר", "l", "ליטר", "ליטרים"),
        UnitDef("ml", Dim.VOLUME, 0.001, "מ\"ל", "ml", "מ\"ל", "מל"),
        UnitDef("gal", Dim.VOLUME, 3.785411784, "גלון", "gal", "gallon", "gallons", "גלון", "גלונים"),
        UnitDef("c", Dim.TEMP, 1.0, "°C", "c", "°c", "צלזיוס", "מעלות"),
        UnitDef("f", Dim.TEMP, 1.0, "°F", "f", "°f", "פרנהייט"),
        UnitDef("USD", Dim.CURRENCY, 1.0, "$", "usd", "$", "דולר", "דולרים"),
        UnitDef("ILS", Dim.CURRENCY, 1.0, "₪", "ils", "nis", "₪", "שקל", "שקלים", "ש\"ח", "שח"),
        UnitDef("EUR", Dim.CURRENCY, 1.0, "€", "eur", "€", "יורו"),
        UnitDef("GBP", Dim.CURRENCY, 1.0, "£", "gbp", "£", "שטרלינג", "ליש\"ט")
    )

    private val DEFAULT_TARGET = mapOf(
        "km" to "mi", "mi" to "km", "m" to "ft", "ft" to "m", "cm" to "in", "in" to "cm", "mm" to "in",
        "kg" to "lb", "lb" to "kg", "g" to "oz", "oz" to "g",
        "l" to "gal", "gal" to "l", "ml" to "oz",
        "c" to "f", "f" to "c",
        "USD" to "ILS", "EUR" to "ILS", "GBP" to "ILS", "ILS" to "USD"
    )

    private val PATTERN = Regex("^\\s*([\\d.,]+)\\s*([^\\d\\s]+?)\\s*(?:(?:to|in|ל|ב)\\s*)?([^\\d\\s]+)?\\s*$")

    private fun find(alias: String?): UnitDef? {
        if (alias.isNullOrBlank()) return null
        val a = alias.lowercase().replace('״', '"').replace('׳', '\'')
        return UNITS.firstOrNull { u -> u.aliases.any { it.lowercase() == a } }
    }

    /** האם זו המרה של מטבע (צריך שערים מהאינטרנט) */
    fun isCurrency(query: String): Boolean {
        val m = PATTERN.matchEntire(query.trim()) ?: return false
        return find(m.groupValues[2])?.dim == Dim.CURRENCY
    }

    /** למשל "100$ בשקלים" ← "100 $ = 365.2 ₪". rates: שערים מול הדולר */
    fun convert(query: String, rates: Map<String, Double>?): String? {
        val m = PATTERN.matchEntire(query.trim()) ?: return null
        val amount = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = find(m.groupValues[2]) ?: return null
        val to = find(m.groupValues[3].ifBlank { null }) ?: UNITS.firstOrNull { it.id == DEFAULT_TARGET[from.id] } ?: return null
        if (from.dim != to.dim || from.id == to.id) return null

        val result = when (from.dim) {
            Dim.TEMP -> if (from.id == "c") amount * 9 / 5 + 32 else (amount - 32) * 5 / 9
            Dim.CURRENCY -> {
                val r = rates ?: return null
                val fromRate = if (from.id == "USD") 1.0 else r[from.id] ?: return null
                val toRate = if (to.id == "USD") 1.0 else r[to.id] ?: return null
                amount / fromRate * toRate
            }
            else -> amount * from.factor / to.factor
        }
        val rounded = if (from.dim == Dim.CURRENCY) "%.2f".format(java.util.Locale.US, result) else Calculator.format(result)
        return "${Calculator.format(amount)} ${from.symbol} = $rounded ${to.symbol}"
    }
}

// ================= הגדרות, אנשי קשר, אינטרנט =================

data class SettingLink(val label: String, val action: String, val keywords: List<String>)
data class ContactResult(val name: String, val number: String?, val lookupUri: Uri?)

object UniversalSearch {

    val SETTINGS = listOf(
        SettingLink("Wi-Fi", Settings.ACTION_WIFI_SETTINGS, listOf("wifi", "וויפי", "ויפי", "אינטרנט", "רשת")),
        SettingLink("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS, listOf("bluetooth", "בלוטות", "בלוטוס")),
        SettingLink("רשת ואינטרנט", Settings.ACTION_WIRELESS_SETTINGS, listOf("רשת", "סלולר", "נתונים", "network", "data")),
        SettingLink("מצב טיסה", Settings.ACTION_AIRPLANE_MODE_SETTINGS, listOf("טיסה", "airplane")),
        SettingLink("תצוגה", Settings.ACTION_DISPLAY_SETTINGS, listOf("תצוגה", "בהירות", "מסך", "display", "brightness")),
        SettingLink("צלילים ורטט", Settings.ACTION_SOUND_SETTINGS, listOf("צליל", "רטט", "רינגטון", "sound", "ringtone")),
        SettingLink("סוללה", Intent.ACTION_POWER_USAGE_SUMMARY, listOf("סוללה", "battery")),
        SettingLink("אפליקציות", Settings.ACTION_APPLICATION_SETTINGS, listOf("אפליקציות", "apps")),
        SettingLink("מיקום", Settings.ACTION_LOCATION_SOURCE_SETTINGS, listOf("מיקום", "gps", "location")),
        SettingLink("אבטחה", Settings.ACTION_SECURITY_SETTINGS, listOf("אבטחה", "נעילה", "טביעת", "security")),
        SettingLink("אחסון", Settings.ACTION_INTERNAL_STORAGE_SETTINGS, listOf("אחסון", "זיכרון", "storage")),
        SettingLink("תאריך ושעה", Settings.ACTION_DATE_SETTINGS, listOf("תאריך", "שעה", "date", "time")),
        SettingLink("שפה", Settings.ACTION_LOCALE_SETTINGS, listOf("שפה", "language")),
        SettingLink("נגישות", Settings.ACTION_ACCESSIBILITY_SETTINGS, listOf("נגישות", "accessibility")),
        SettingLink("NFC", Settings.ACTION_NFC_SETTINGS, listOf("nfc", "תשלום")),
        SettingLink("אפשרויות מפתח", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, listOf("מפתח", "developer")),
        SettingLink("הגדרות", Settings.ACTION_SETTINGS, listOf("הגדרות", "settings"))
    )

    fun settings(query: String): List<SettingLink> {
        val q = query.lowercase()
        return SETTINGS.filter { s -> s.label.lowercase().contains(q) || s.keywords.any { it.startsWith(q) || q.startsWith(it) } }
            .take(3)
    }

    fun contacts(context: Context, query: String): List<ContactResult> {
        if (!GlanceData.hasPermission(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(query))
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY
                ),
                null, null, null
            )?.use { c ->
                val seen = HashSet<Long>()
                val list = mutableListOf<ContactResult>()
                while (c.moveToNext() && list.size < 3) {
                    val id = c.getLong(0)
                    if (!seen.add(id)) continue
                    val lookup = c.getString(3)
                    list += ContactResult(
                        name = c.getString(1) ?: "",
                        number = c.getString(2),
                        lookupUri = lookup?.let { ContactsContract.Contacts.getLookupUri(id, it) }
                    )
                }
                list
            }
        }.getOrNull().orEmpty()
    }

    /** שערי מטבע מול הדולר, נשמרים ל-12 שעות */
    fun currencyRates(context: Context): Map<String, Double>? {
        val prefs = context.getSharedPreferences("search", Context.MODE_PRIVATE)
        val cached = prefs.getString("rates", null)
        val age = System.currentTimeMillis() - prefs.getLong("ratesTime", 0)
        if (cached != null && age < 12 * 3_600_000L) return parseRates(cached)
        val fresh = runCatching {
            val conn = URL("https://api.frankfurter.app/latest?from=USD&to=ILS,EUR,GBP").openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        if (fresh != null && parseRates(fresh) != null) {
            prefs.edit().putString("rates", fresh).putLong("ratesTime", System.currentTimeMillis()).apply()
            return parseRates(fresh)
        }
        return cached?.let { parseRates(it) }
    }

    private fun parseRates(json: String): Map<String, Double>? = runCatching {
        val rates = JSONObject(json).getJSONObject("rates")
        rates.keys().asSequence().associateWith { rates.getDouble(it) }
    }.getOrNull()

    fun open(context: Context, intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun webSearch(context: Context, query: String) {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        if (search.resolveActivity(context.packageManager) != null) open(context, search)
        else open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))
    }
}

// ================= תצוגה =================

/** תוצאות נוספות בחיפוש במגירה: מחשבון/המרה, אנשי קשר, הגדרות ואינטרנט */
@Composable
fun UniversalResults(query: String, store: LauncherStore, safeMode: Boolean, onAction: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val calc = remember(query) { if (store.on(Feature.SEARCH_CALCULATOR)) Calculator.evaluate(query) else null }
    var rates by remember { mutableStateOf<Map<String, Double>?>(null) }
    val needsRates = store.on(Feature.SEARCH_CALCULATOR) && Converter.isCurrency(query)
    LaunchedEffect(needsRates) {
        if (needsRates && rates == null) rates = withContext(Dispatchers.IO) { UniversalSearch.currencyRates(context) }
    }
    val conversion = remember(query, rates) {
        if (store.on(Feature.SEARCH_CALCULATOR) && calc == null) Converter.convert(query, rates) else null
    }

    var contacts by remember { mutableStateOf<List<ContactResult>>(emptyList()) }
    val wantContacts = store.on(Feature.SEARCH_CONTACTS) && !safeMode && query.length >= 2
    LaunchedEffect(query, wantContacts) {
        contacts = if (wantContacts) {
            delay(150)
            withContext(Dispatchers.IO) { UniversalSearch.contacts(context, query) }
        } else {
            emptyList()
        }
    }
    val settings = remember(query) {
        if (store.on(Feature.SEARCH_SETTINGS) && !safeMode && query.length >= 2) UniversalSearch.settings(query) else emptyList()
    }
    val showWeb = store.on(Feature.SEARCH_WEB) && !safeMode

    val answer = calc?.let { "$query = $it" } ?: conversion
    if (answer == null && contacts.isEmpty() && settings.isEmpty() && !showWeb) return

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // תשובה (מחשבון או המרה). לחיצה מעתיקה
        if (answer != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboard.setText(AnnotatedString(calc ?: answer.substringAfter("= ").substringBefore(" ")))
                        android.widget.Toast.makeText(context, "הועתק", android.widget.Toast.LENGTH_SHORT).show()
                    }
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        answer,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                }
            }
        }

        contacts.forEach { contact ->
            ResultRow(
                leading = {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(contact.name.take(1), color = MaterialTheme.colorScheme.onTertiaryContainer, fontSize = 16.sp)
                    }
                },
                title = contact.name,
                subtitle = contact.number,
                onClick = {
                    contact.lookupUri?.let { UniversalSearch.open(context, Intent(Intent.ACTION_VIEW, it)) }
                    onAction()
                },
                trailing = contact.number?.let { number ->
                    @Composable {
                        IconButton(onClick = {
                            UniversalSearch.open(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
                            onAction()
                        }) { Icon(Icons.Outlined.Call, contentDescription = "חיוג") }
                    }
                }
            )
        }

        settings.forEach { link ->
            ResultRow(
                leading = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = link.label,
                subtitle = "הגדרות המכשיר",
                onClick = {
                    UniversalSearch.open(context, Intent(link.action))
                    onAction()
                }
            )
        }

        if (showWeb) {
            ResultRow(
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                title = "חיפוש בגוגל: $query",
                subtitle = null,
                onClick = {
                    UniversalSearch.webSearch(context, query)
                    onAction()
                }
            )
        }
    }
}

@Composable
private fun ResultRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                if (subtitle != null) {
                    Text(subtitle, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}
