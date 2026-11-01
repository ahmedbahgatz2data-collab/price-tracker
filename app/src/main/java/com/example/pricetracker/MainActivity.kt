package com.example.pricetracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs

const val CURRENCY = "ج.م"  // غيّرها لو حابب
private val GreenColor = Color(0xFF3FA34D)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        schedulePeriodic(this)

        // لو الرابط جاي من "مشاركة" في المتصفح
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { Regex("https?://\\S+").find(it)?.value } ?: ""

        setContent { AppTheme { App(shared) } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun fmtPrice(d: Double): String =
    if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d)

private fun hostOf(u: String): String = Uri.parse(u).host?.removePrefix("www.") ?: u

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        // لا يوجد متصفح
    }
}

private fun batterySettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: Exception) {
            // تجاهل
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(sharedUrl: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var products by remember { mutableStateOf(Store.load(ctx)) }
    var showAdd by remember { mutableStateOf(sharedUrl.isNotBlank()) }
    var prefill by remember { mutableStateOf(sharedUrl) }
    var deleteTarget by remember { mutableStateOf<Product?>(null) }
    var editTarget by remember { mutableStateOf<Product?>(null) }
    var topMenu by remember { mutableStateOf(false) }
    var showTelegram by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(Unit) {
        while (true) {
            products = Store.load(ctx)
            delay(2000)
        }
    }

    fun say(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("متابع الأسعار", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = {
                        checkNow(ctx)
                        say("جاري فحص الأسعار…")
                    }) { Text("تحديث") }
                    Box {
                        TextButton(onClick = { topMenu = true }) { Text("⋮") }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("إشعارات تيليجرام") },
                                onClick = {
                                    topMenu = false
                                    showTelegram = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("السماح بالعمل في الخلفية (البطارية)") },
                                onClick = {
                                    topMenu = false
                                    batterySettings(ctx)
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true }) { Text("＋ إضافة منتج") }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (products.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "مفيش منتجات لسه.\nاضغط «＋ إضافة منتج»، أو شارك رابط المنتج من المتصفح لهذا التطبيق.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(products, key = { it.id }) { p ->
                    ProductCard(
                        p = p,
                        onOpen = { openUrl(ctx, p.url) },
                        onEdit = { editTarget = p },
                        onDelete = { deleteTarget = p }
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddProductDialog(
            initialUrl = prefill,
            onDismiss = {
                showAdd = false
                prefill = ""
            },
            onAdd = { urls, n, t, s ->
                val known = products.map { it.url.trimEnd('/') }.toMutableSet()
                val fresh = ArrayList<Product>()
                var dup = 0
                val single = urls.size == 1 && n.isNotBlank()
                for (u in urls) {
                    if (!known.add(u.trimEnd('/'))) {
                        dup++
                        continue
                    }
                    fresh.add(
                        Product(
                            id = UUID.randomUUID().toString(),
                            name = if (single) n else hostOf(u),
                            url = u,
                            selector = s.ifBlank { null },
                            target = t,
                            lastPrice = null,
                            lastChecked = null,
                            error = null,
                            history = emptyList(),
                            nameAuto = !single
                        )
                    )
                }
                if (fresh.isNotEmpty()) {
                    Store.addAll(ctx, fresh)
                    products = Store.load(ctx)
                    checkNow(ctx)
                }
                say(
                    when {
                        fresh.isEmpty() -> "كل الروابط دي مضافة بالفعل"
                        dup > 0 -> "تمت إضافة ${fresh.size} منتج، وتم تخطي $dup مكرر"
                        else -> "تمت إضافة ${fresh.size} منتج، جاري جلب الأسعار…"
                    }
                )
                showAdd = false
                prefill = ""
            }
        )
    }

    if (showTelegram) TelegramDialog { showTelegram = false }

    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("حذف المنتج؟") },
            text = { Text(p.name) },
            confirmButton = {
                TextButton(onClick = {
                    Store.remove(ctx, p.id)
                    products = Store.load(ctx)
                    deleteTarget = null
                }) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("إلغاء") } }
        )
    }

    editTarget?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.target?.let { fmtPrice(it) } ?: "") }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("السعر المستهدف") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("نبّهني لو السعر وصل لـ (فاضي = بدون)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().replace(",", ".").toDoubleOrNull()
                    Store.update(ctx, p.id) { it.copy(target = v) }
                    products = Store.load(ctx)
                    editTarget = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editTarget = null }) { Text("إلغاء") } }
        )
    }
}

@Composable
fun AddProductDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onAdd: (List<String>, String, Double?, String) -> Unit
) {
    val clip = LocalClipboardManager.current
    var bulk by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf(initialUrl) }
    var bulkText by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var selector by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }

    val urlRegex = remember { Regex("https?://[^\\s,;]+") }
    val bulkUrls = remember(bulkText) { urlRegex.findAll(bulkText).map { it.value }.distinct().toList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bulk) "إضافة عدة منتجات" else "إضافة منتج") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { bulk = !bulk }) {
                    Text(if (bulk) "الرجوع لإضافة رابط واحد" else "إضافة عدة روابط مرة واحدة")
                }
                if (bulk) {
                    OutlinedTextField(
                        value = bulkText,
                        onValueChange = { bulkText = it },
                        label = { Text("الصق الروابط (كل رابط في سطر)") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        val t = clip.getText()?.text ?: ""
                        if (t.isNotBlank()) {
                            bulkText = if (bulkText.isBlank()) t else bulkText.trimEnd() + "\n" + t
                        }
                    }) { Text("لصق من الحافظة") }
                    Text(
                        "تم التعرف على ${bulkUrls.size} رابط",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("رابط المنتج") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        val t = clip.getText()?.text?.trim() ?: ""
                        if (t.startsWith("http")) url = t
                    }) { Text("لصق من الحافظة") }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("الاسم (اختياري، وإلا هيتجاب من الصفحة)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("نبّهني لو السعر وصل لـ (اختياري)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "إخفاء الخيارات المتقدمة" else "خيارات متقدمة")
                    }
                    if (advanced) {
                        OutlinedTextField(
                            value = selector,
                            onValueChange = { selector = it },
                            label = { Text("CSS selector للسعر") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (bulk) bulkUrls.isNotEmpty() else url.trim().startsWith("http"),
                onClick = {
                    if (bulk) {
                        onAdd(bulkUrls, "", null, "")
                    } else {
                        onAdd(
                            listOf(url.trim()),
                            name.trim(),
                            target.trim().replace(",", ".").toDoubleOrNull(),
                            selector.trim()
                        )
                    }
                }
            ) { Text(if (bulk) "إضافة ${bulkUrls.size}" else "إضافة") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
fun Sparkline(points: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.min()
        val hi = points.max()
        val range = if (hi - lo == 0.0) 1.0 else hi - lo
        val stepX = size.width / (points.size - 1)
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = i * stepX
            val y = (size.height - ((v - lo) / range * size.height * 0.8 + size.height * 0.1)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
fun ProductCard(p: Product, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val price = p.lastPrice
    val prev = if (p.history.size >= 2) p.history[p.history.size - 2] else null
    val low = p.history.minOrNull()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.clickable { onOpen() }.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (!p.imageUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = p.imageUrl,
                        contentDescription = p.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        p.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        hostOf(p.url),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    if (price == null) {
                        if (p.error == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            "${fmtPrice(price)} $CURRENCY",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (prev != null) {
                            val diff = price - prev
                            val pct = abs(diff) / prev * 100
                            Text(
                                (if (diff < 0) "⬇ " else "⬆ ") + fmtPrice(abs(diff)) + " (" + "%.1f".format(pct) + "%)",
                                color = if (diff < 0) GreenColor else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("فتح الصفحة") }, onClick = { menu = false; onOpen() })
                        DropdownMenuItem(text = { Text("السعر المستهدف") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("حذف") }, onClick = { menu = false; onDelete() })
                    }
                }
            }

            if (price != null && p.history.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))
                Sparkline(
                    points = p.history,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                )
            }

            val info = ArrayList<String>()
            if (p.history.size >= 2 && low != null) info.add("أقل سعر: ${fmtPrice(low)}")
            val t = p.target
            if (t != null) info.add("الهدف: ${fmtPrice(t)}")
            if (info.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    info.joinToString("  •  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val st = p.status
            if (st != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "الحالة: $st",
                    color = when (st) {
                        "متوفر" -> GreenColor
                        "نفد", "متوقف", "الصفحة غير موجودة" -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val err = p.error
            if (err != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "⚠️ $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            val checked = p.lastChecked
            if (checked != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "آخر فحص: " + DateUtils.getRelativeTimeSpanString(
                        checked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TelegramDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(Prefs.token(ctx)) }
    var chats by remember { mutableStateOf(Prefs.chatIds(ctx).joinToString(", ")) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إشعارات تيليجرام") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "1) في تيليجرام افتح @BotFather واكتب /newbot واتبع الخطوات، وانسخ التوكن (أو استخدم توكن بوت عندك).\n" +
                        "2) كل شخص عايز يستلم الإشعارات يفتح البوت ويكتب له أي رسالة (/start).\n" +
                        "3) الصق التوكن، واضغط «اكتشاف Chat IDs»، ثم «رسالة تجربة»، ثم «حفظ».\n" +
                        "تقدر كمان تكتب الـ Chat IDs بإيدك مفصولة بفاصلة.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bot Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = chats,
                    onValueChange = { chats = it },
                    label = { Text("Chat IDs (افصل بينهم بفاصلة)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    enabled = !busy && token.isNotBlank(),
                    onClick = {
                        busy = true
                        msg = "جاري البحث…"
                        val t = token.trim()
                        scope.launch {
                            val ids = withContext(Dispatchers.IO) { Telegram.detectChatIds(t) }
                            if (ids.isNotEmpty()) {
                                val cur = Prefs.parseIds(chats)
                                chats = (cur + ids.filter { it !in cur }).joinToString(", ")
                                msg = "تم العثور على ${ids.size} محادثة ✅"
                            } else {
                                msg = "ما لقيتش رسائل. خلّي الشخص يبعت رسالة للبوت وجرّب تاني. " +
                                    "(لو نفس البوت متربط بموقع بـ webhook، اكتب الـ Chat IDs بإيدك.)"
                            }
                            busy = false
                        }
                    }
                ) { Text("اكتشاف Chat IDs") }
                OutlinedButton(
                    enabled = !busy && token.isNotBlank() && Prefs.parseIds(chats).isNotEmpty(),
                    onClick = {
                        busy = true
                        msg = "جاري الإرسال…"
                        val t = token.trim()
                        val list = Prefs.parseIds(chats)
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                Telegram.sendAll(t, list, "✅ تم ربط متابع الأسعار بتيليجرام")
                            }
                            msg = if (err == null) "تم الإرسال إلى ${list.size} محادثة ✅" else "فشل: $err"
                            busy = false
                        }
                    }
                ) { Text("رسالة تجربة لكل المحادثات") }
                if (msg.isNotBlank()) {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.saveTelegram(ctx, token.trim(), Prefs.parseIds(chats).joinToString(","))
                onDismiss()
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}
