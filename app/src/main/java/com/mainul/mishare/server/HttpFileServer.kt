package com.mainul.mishare.server

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.mainul.mishare.model.SharedFile
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HttpFileServer(
    private val context: Context,
    port: Int,
    private val onFileUploaded: ((SharedFile) -> Unit)? = null
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val sharedFiles = Collections.synchronizedList(mutableListOf<SharedFile>())
    private val receivedFiles = Collections.synchronizedList(mutableListOf<SharedFile>())

    private val receivedDir: File by lazy {
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadDir, "MiShare")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    init {
        // Populate existing received files
        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setSharedFiles(files: List<SharedFile>) {
        synchronized(sharedFiles) {
            sharedFiles.clear()
            sharedFiles.addAll(files)
        }
    }

    fun getReceivedFiles(): List<SharedFile> {
        return synchronized(receivedFiles) {
            ArrayList(receivedFiles)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(response)
            return response
        }

        try {
            // Static Web Client
            if (uri == "/" || uri == "/index.html") {
                val res = serveAsset("web/index.html", "text/html; charset=UTF-8")
                addCorsHeaders(res)
                return res
            }

            // API: Status
            if (uri == "/api/status" && method == Method.GET) {
                val status = mapOf(
                    "status" to "online",
                    "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                    "sharedCount" to sharedFiles.size,
                    "receivedCount" to receivedFiles.size,
                    "timestamp" to System.currentTimeMillis()
                )
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(status))
                addCorsHeaders(res)
                return res
            }

            // API: Files staged from Mobile
            if (uri == "/api/files" && method == Method.GET) {
                val list = synchronized(sharedFiles) {
                    sharedFiles.map {
                        mapOf(
                            "id" to it.id,
                            "name" to it.name,
                            "size" to it.size,
                            "mimeType" to it.mimeType,
                            "timestamp" to it.timestamp
                        )
                    }
                }
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", gson.toJson(list))
                addCorsHeaders(res)
                return res
            }

            // API: Download a single file to PC
            if (uri.startsWith("/api/download/")) {
                val fileId = uri.substringAfter("/api/download/")
                val target = synchronized(sharedFiles) {
                    sharedFiles.find { it.id == fileId }
                }

                if (target != null) {
                    val localFile = if (target.localPath != null) File(target.localPath) else null
                    val stream: InputStream? = when {
                        localFile != null && localFile.exists() -> FileInputStream(localFile)
                        target.uri != null -> {
                            try {
                                context.contentResolver.openInputStream(target.uri)
                            } catch (e: Exception) {
                                e.printStackTrace()
                                null
                            }
                        }
                        else -> null
                    }

                    val totalLength = when {
                        localFile != null && localFile.exists() -> localFile.length()
                        target.size > 0 -> target.size
                        else -> stream?.available()?.toLong() ?: -1L
                    }

                    if (stream != null) {
                        val response = if (totalLength >= 0) {
                            newFixedLengthResponse(Response.Status.OK, target.mimeType, stream, totalLength)
                        } else {
                            newChunkedResponse(Response.Status.OK, target.mimeType, stream)
                        }

                        val encodedName = URLEncoder.encode(target.name, "UTF-8").replace("+", "%20")
                        val safeName = target.name.replace("\"", "\\\"")
                        response.addHeader(
                            "Content-Disposition",
                            "attachment; filename=\"$safeName\"; filename*=UTF-8''$encodedName"
                        )
                        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                        addCorsHeaders(response)
                        return response
                    }
                }
                val notFound = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found or unreadable on device")
                addCorsHeaders(notFound)
                return notFound
            }

            // API: Download all files as a ZIP archive
            if (uri == "/api/download-all" && method == Method.GET) {
                val filesSnapshot = synchronized(sharedFiles) { ArrayList(sharedFiles) }
                if (filesSnapshot.isEmpty()) {
                    val res = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "No files to download")
                    addCorsHeaders(res)
                    return res
                }

                // Create temp zip file in cache
                val tempZip = File(context.cacheDir, "MiShare_bundle_${System.currentTimeMillis()}.zip")
                ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                    for (file in filesSnapshot) {
                        val inputStream: InputStream? = when {
                            file.localPath != null && File(file.localPath).exists() -> FileInputStream(File(file.localPath))
                            file.uri != null -> {
                                try { context.contentResolver.openInputStream(file.uri) } catch (e: Exception) { null }
                            }
                            else -> null
                        }

                        if (inputStream != null) {
                            zos.putNextEntry(ZipEntry(file.name))
                            inputStream.copyTo(zos)
                            zos.closeEntry()
                            inputStream.close()
                        }
                    }
                }

                val zipStream = FileInputStream(tempZip)
                val response = newFixedLengthResponse(Response.Status.OK, "application/zip", zipStream, tempZip.length())
                response.addHeader("Content-Disposition", "attachment; filename=\"MiShare_files.zip\"")
                addCorsHeaders(response)
                return response
            }

            // API: Upload files from PC to Mobile
            if (uri == "/api/upload" && method == Method.POST) {
                val files = HashMap<String, String>()
                session.parseBody(files)

                val params = session.parameters
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
                val res = newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
                addCorsHeaders(res)
                return res
            }

        } catch (e: Exception) {
            e.printStackTrace()
            val err = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error: ${e.message}")
            addCorsHeaders(err)
            return err
        }

        val res = newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        addCorsHeaders(res)
        return res
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Range")
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
            "html" -> "text/html"
            "doc", "docx" -> "application/msword"
            else -> "application/octet-stream"
        }
    }
}
