package com.example.tvdash

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun DashCard(title: String, value: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(20.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.displaySmall)
            Text(sub, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun AppCard(app: AppEntry) {
    val ctx = LocalContext.current
    Card(onClick = {
        val pm = ctx.packageManager
        (pm.getLeanbackLaunchIntentForPackage(app.pkg) ?: pm.getLaunchIntentForPackage(app.pkg))
            ?.let { ctx.startActivity(it) }
    }, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            Image(app.image, app.label, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
        }
        Text(app.label, maxLines = 1, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(12.dp))
    }
}

