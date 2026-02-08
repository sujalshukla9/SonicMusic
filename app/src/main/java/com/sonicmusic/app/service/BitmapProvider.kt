package com.sonicmusic.app.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.createBitmap
import coil.ImageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.SuccessResult
import android.graphics.drawable.BitmapDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Efficient Bitmap Provider for notification thumbnails
 * 
 * Features:
 * - Caches last loaded bitmap
 * - Provides fallback default bitmap
 * - Handles dark/light mode for default bitmap
 * - Cancels previous requests when loading new image
 */
class BitmapProvider(
    private val bitmapSize: Int = 512,
    private val defaultColor: (isDark: Boolean) -> Int = { if (it) 0xFF1E1E1E.toInt() else 0xFFE0E0E0.toInt() },
    private val context: Context
) {
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val imageLoader = ImageLoader.Builder(context).build()
    
    @Volatile
    var lastUri: Uri? = null
        private set

    @Volatile
    private var lastBitmap: Bitmap? = null
        get() = field?.takeUnless { it.isRecycled }
        set(value) {
            field = value
            listener?.invoke(value)
        }
    
    private var lastIsSystemInDarkMode = false
    private var currentRequest: Disposable? = null
    
    private lateinit var defaultBitmap: Bitmap
    val bitmap: Bitmap get() = lastBitmap ?: defaultBitmap
    
    private var listener: ((Bitmap?) -> Unit)? = null
    
    init {
        setDefaultBitmap()
    }
    
    /**
     * Creates or updates the default bitmap based on system theme
     * @return true if the bitmap was changed
     */
    fun setDefaultBitmap(): Boolean {
        val isSystemInDarkMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        
        var oldBitmap: Bitmap? = null
        if (::defaultBitmap.isInitialized) {
            if (isSystemInDarkMode == lastIsSystemInDarkMode) return false
            oldBitmap = defaultBitmap
        }
        
        lastIsSystemInDarkMode = isSystemInDarkMode
        
        defaultBitmap = createBitmap(bitmapSize, bitmapSize).applyCanvas {
            drawColor(defaultColor(isSystemInDarkMode))
        }
        oldBitmap?.recycle()
        
        return lastBitmap == null
    }
    
    /**
     * Load a bitmap from URI
     * @param uri The URI to load, or null to clear
     * @param onDone Callback when loading completes
     */
    fun load(
        uri: Uri?,
        onDone: (Bitmap) -> Unit = { }
    ) {
        // If same URI, just return cached bitmap
        if (lastUri == uri) {
            listener?.invoke(lastBitmap)
            onDone(bitmap)
            return
        }
        
        lastUri = uri
        
        // Clear bitmap if no URI
        if (uri == null) {
            lastBitmap = null
            onDone(bitmap)
            return
        }
        
        // Cancel previous request
        currentRequest?.dispose()
        
        // Load new bitmap
        coroutineScope.launch {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri)
                    .size(bitmapSize)
                    .allowHardware(false)
                    .build()
                
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    lastBitmap = (result.drawable as? BitmapDrawable)?.bitmap
                } else {
                    lastBitmap = null
                }
                onDone(bitmap)
            } catch (e: Exception) {
                lastBitmap = null
                onDone(bitmap)
            }
        }
    }
    
    /**
     * Load a bitmap from URL string
     */
    fun load(
        url: String?,
        onDone: (Bitmap) -> Unit = { }
    ) {
        load(url?.let { Uri.parse(it) }, onDone)
    }
    
    /**
     * Set a listener for bitmap changes
     */
    fun setListener(callback: ((Bitmap?) -> Unit)?) {
        listener = callback
        listener?.invoke(lastBitmap)
    }
    
    /**
     * Release resources
     */
    fun release() {
        currentRequest?.dispose()
        if (::defaultBitmap.isInitialized && !defaultBitmap.isRecycled) {
            defaultBitmap.recycle()
        }
        lastBitmap?.recycle()
        lastBitmap = null
    }
}
