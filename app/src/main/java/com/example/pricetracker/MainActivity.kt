package com.example.pricetracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannel(this)
        schedulePeriodic(this)

        // لو الرابط جاي من "مشاركة" في المتصفح
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)
            ?.let { Regex("https?://\\S+").find(it)?.value } ?: ""

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { App(shared) }
            }
        }
    }
}

private fun fmtPrice(d: Double): String =
    if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d)

@Composable
fun App(sharedUrl: String) {
    val ctx = LocalContext.current
    var products by remember { mutableStateOf(Store.load(ctx)) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(sharedUrl) }
    var target by remember { mutableStateOf("") }
    var selector by remember { mutableStateOf("") }

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

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("متابع الأسعار", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("رابط المنتج") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("اسم المنتج (اختياري)") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = target, onValueChange = { target = it },
                    label = { Text("نبّهني لو السعر وصل لـ... (اختياري)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = selector, onValueChange = { selector = it },
                    label = { Text("CSS selector للسعر (اختياري، لو الاستخراج التلقائي فشل)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = url.trim().startsWith("http"),
                        onClick = {
                            val u = url.trim()
                            val p = Product(
                                id = UUID.randomUUID().toString(),
                                name = name.trim().ifBlank { Uri.parse(u).host ?: u },
                                url = u,
                                selector = selector.trim().ifBlank { null },
                                target = target.trim().replace(",", ".").toDoubleOrNull(),
                                lastPrice = null,
                                lastChecked = null,
                                error = null
                            )
                            Store.add(ctx, p)
                            products = Store.load(ctx)
                            name = ""; url = ""; target = ""; selector = ""
                            checkNow(ctx)
                        }
                    ) { Text("إضافة ومتابعة") }
                    OutlinedButton(onClick = { checkNow(ctx) }) { Text("افحص الآن") }
                }
                Text(
                    "بيفحص تلقائيًا كل $CHECK_EVERY_MINUTES دقيقة في الخلفية.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        items(products, key = { it.id }) { p ->
            ProductCard(p) {
                Store.remove(ctx, p.id)
                products = Store.load(ctx)
            }
        }
    }
}

@Composable
fun ProductCard(p: Product, onDelete: () -> Unit) {
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDelete) { Text("حذف") }
            }
            val price = p.lastPrice
            Text(if (price != null) "السعر الحالي: ${fmtPrice(price)}" else "جاري أول فحص…")
            val t = p.target
            if (t != null) Text("المستهدف: ${fmtPrice(t)}", style = MaterialTheme.typography.bodySmall)
            val checked = p.lastChecked
            if (checked != null) {
                Text("آخر فحص: ${fmt.format(Date(checked))}", style = MaterialTheme.typography.bodySmall)
            }
            val err = p.error
            if (err != null) {
                Text("⚠️ $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
