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
            onAdd = { u, n, t, s ->
                if (products.any { it.url == u }) {
                    say("المنتج ده مضاف بالفعل")
                } else {
                    Store.add(
                        ctx,
                        Product(
                            id = UUID.randomUUID().toString(),
                            name = n.ifBlank { hostOf(u) },
                            url = u,
                            selector = s.ifBlank { null },
                            target = t,
                            lastPrice = null,
                            lastChecked = null,
                            error = null,
                            history = emptyList(),
                            nameAuto = n.isBlank()
                        )
                    )
                    products = Store.load(ctx)
                    checkNow(ctx)
                    say("تمت الإضافة، جاري جلب السعر…")
                }
                showAdd = false
                prefill = ""
            }
        )
    }

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
    onAdd: (String, String, Double?, String) -> Unit
) {
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf(initialUrl) }
    var name by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var selector by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة منتج") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
        },
        confirmButton = {
            TextButton(
                enabled = url.trim().startsWith("http"),
                onClick = {
                    onAdd(
                        url.trim(),
                        name.trim(),
                        target.trim().replace(",", ".").toDoubleOrNull(),
                        selector.trim()
                    )
                }
            ) { Text("إضافة") }
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

            Spacer(modifier = Modifier.height(8.dp))

            if (price == null) {
                if (p.error == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${fmtPrice(price)} $CURRENCY",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (prev != null) {
                        val diff = price - prev
                        val pct = abs(diff) / prev * 100
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            (if (diff < 0) "⬇ " else "⬆ ") + fmtPrice(abs(diff)) + " (" + "%.1f".format(pct) + "%)",
                            color = if (diff < 0) GreenColor else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (p.history.size >= 2) {
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
