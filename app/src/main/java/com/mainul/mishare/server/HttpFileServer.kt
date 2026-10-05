package com.mainul.mishare.server

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.mainul.mishare.model.SharedFile
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

class HttpFileServer(
    private val context: Context,
    port: Int,
    private val onFileUploaded: ((SharedFile) -> Unit)? = null
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val sharedFiles = mutableListOf<SharedFile>()
    private val receivedFiles = mutableListOf<SharedFile>()

    private val receivedDir: File by lazy {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadDir, "MiShare")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    init {
        loadExistingReceivedFiles()
    }

    private fun loadExistingReceivedFiles() {
        if (receivedDir.exists() && receivedDir.isDirectory) {
            receivedDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
                if (file.isFile) {
                    receivedFiles.add(
                        SharedFile(
                            id = "rec_" + file.name.hashCode(),
                            name = file.name,
                            size = file.length(),
                            mimeType = resolveMimeType(file.name),
                            uri = null,
                            localPath = file.absolutePath,
                            isReceived = true,
                            timestamp = file.lastModified()
                        )
                    )
                }
            }
        }
    }

    fun setSharedFiles(files: List<SharedFile>) {
        synchronized(sharedFiles) {
            sharedFiles.clear()
            sharedFiles.addAll(files)
        }
    }

    fun getSharedFiles(): List<SharedFile> {
        synchronized(sharedFiles) {
            return ArrayList(sharedFiles)
        }
    }

    fun getReceivedFiles(): List<SharedFile> {
        synchronized(receivedFiles) {
            return ArrayList(receivedFiles)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        try {
            // Static Web Client
            if (uri == "/" || uri == "/index.html") {
                return serveAsset("web/index.html", "text/html")
            }

            // API: Status
            if (uri == "/api/status" && method == Method.GET) {
                val status = mapOf(
                    "status" to "online",
                    "device" to android.os.Build.MODEL,
                    "sharedCount" to sharedFiles.size,
                    "receivedCount" to receivedFiles.size
                )
                return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
            }

            // API: Files staged from Mobile
            if (uri == "/api/files" && method == Method.GET) {
                val list = synchronized(sharedFiles) {
                    sharedFiles.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "size" to it.size,
                            "mimeType" to it.mimeType
                        )
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(list))
            }

            // API: Download a file to PC
            if (uri.startsWith("/api/download/")) {
                val fileId = uri.substringAfter("/api/download/")
                val target = synchronized(sharedFiles) {
                    sharedFiles.find { it.id == fileId }
                }

                if (target != null) {
                    val stream: InputStream? = if (target.uri != null) {
                        context.contentResolver.openInputStream(target.uri)
                    } else if (target.localPath != null) {
                        FileInputStream(File(target.localPath))
                    } else null

                    if (stream != null) {
                        val response = newChunkedResponse(Response.Status.OK, target.mimeType, stream)
                        response.addHeader(
                            "Content-Disposition",
                            "attachment; filename=\"${target.name}\""
                        )
                        response.addHeader("Content-Length", target.size.toString())
                        return response
                    }
                }
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }

            // API: Upload files from PC to Mobile
            if (uri == "/api/upload" && method == Method.POST) {
                val files = HashMap<String, String>()
                session.parseBody(files)

                val params = session.parameters
                // Check uploaded files
                for ((key, tempFilePath) in files) {
                    if (key != "postData") {
                        val originalFileName = params[key]?.firstOrNull() ?: "file_${System.currentTimeMillis()}"
                        val cleanName = URLDecoder.decode(originalFileName, "UTF-8")
                        val tempFile = File(tempFilePath)
                        val targetFile = getUniqueFile(receivedDir, cleanName)

                        tempFile.copyTo(targetFile, overwrite = true)
                        tempFile.delete()

                        val newReceived = SharedFile(
                            id = "rec_" + targetFile.name.hashCode(),
                            name = targetFile.name,
                            size = targetFile.length(),
                            mimeType = resolveMimeType(targetFile.name),
                            uri = null,
                            localPath = targetFile.absolutePath,
                            isReceived = true,
                            timestamp = System.currentTimeMillis()
                        )

                        synchronized(receivedFiles) {
                            receivedFiles.add(0, newReceived)
                        }
                        onFileUploaded?.invoke(newReceived)
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error: ${e.message}")
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun getUniqueFile(directory: File, fileName: String): File {
        var file = File(directory, fileName)
        if (!file.exists()) return file

        val nameWithoutExt = fileName.substringBeforeLast(".", fileName)
        val ext = if (fileName.contains(".")) "." + fileName.substringAfterLast(".") else ""
        var counter = 1
        while (file.exists()) {
            file = File(directory, "${nameWithoutExt}_$counter$ext")
            counter++
        }
        return file
    }

    private fun serveAsset(assetPath: String, mimeType: String): Response {
        return try {
            val stream = context.assets.open(assetPath)
            newChunkedResponse(Response.Status.OK, mimeType, stream)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Asset not found: $assetPath")
        }
    }

    private fun resolveMimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "apk" -> "application/vnd.android.package-archive"
            "txt" -> "text/plain"
            "json" -> "application/json"
            else -> "application/octet-stream"
        }
    }
}
