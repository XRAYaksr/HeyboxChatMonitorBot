package com.heychat.monitor.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val IMAGE_CONNECT_TIMEOUT_MS = 8000
private const val IMAGE_READ_TIMEOUT_MS = 12000

// Android 的 LruCache 按条目计数，这里缓存 48 张图片，够一屏滚动复用
private val imageCache = LruCache<String, ImageBitmap>(48)

/** 头像/封面加载：内存 LRU 缓存 + 失败回退占位图标，不引入图片库依赖。 */
private suspend fun loadBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    imageCache.get(url)?.let { return@withContext it }
    if (!(url.startsWith("http://") || url.startsWith("https://"))) return@withContext null
    try {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = IMAGE_CONNECT_TIMEOUT_MS
            readTimeout = IMAGE_READ_TIMEOUT_MS
            instanceFollowRedirects = true
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val bytes = connection.inputStream.use { it.readBytes() }
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: return@withContext null
            bitmap.asImageBitmap().also { imageCache.put(url, it) }
        } finally {
            connection.disconnect()
        }
    } catch (exc: Exception) {
        null
    }
}

/** 网络图片区域，加载失败或地址为空时显示占位图标。 */
@Composable
fun RemoteImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    placeholder: ImageVector = Icons.Rounded.Person,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = url) {
        value = loadBitmap(url)
    }
    val loaded = bitmap
    Box(
        modifier = modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                imageVector = placeholder,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
