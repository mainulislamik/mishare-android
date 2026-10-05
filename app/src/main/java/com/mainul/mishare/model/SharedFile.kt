package com.mainul.mishare.model

import android.net.Uri

data class SharedFile(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: Uri?,
    val localPath: String? = null,
    val isReceived: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
