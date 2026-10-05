package com.mainul.mishare

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mainul.mishare.adapter.ReceivedFileAdapter
import com.mainul.mishare.adapter.SharedFileAdapter
import com.mainul.mishare.databinding.ActivityMainBinding
import com.mainul.mishare.model.SharedFile
import com.mainul.mishare.server.FileServerService
import com.mainul.mishare.utils.NetworkUtils
import com.mainul.mishare.utils.QrCodeHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val sharedFilesList = mutableListOf<SharedFile>()
    private val receivedFilesList = mutableListOf<SharedFile>()

    private lateinit var sharedAdapter: SharedFileAdapter
    private lateinit var receivedAdapter: ReceivedFileAdapter

    // Use OpenMultipleDocuments for broad storage permission access
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        uris?.let { if (it.isNotEmpty()) stageSelectedUris(it) }
    }

    // Fallback picker
    private val fallbackPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        uris?.let { if (it.isNotEmpty()) stageSelectedUris(it) }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Continue
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerViews()
        setupListeners()
        requestAppPermissions()
        handleIncomingShareIntent(intent)
        updateUiState(FileServerService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        updateNetworkInfo()
        updateUiState(FileServerService.isRunning)
        refreshSharedFiles()
        refreshReceivedFiles()

        FileServerService.onStateChangeListener = { isRunning ->
            runOnUiThread {
                updateUiState(isRunning)
            }
        }

        FileServerService.onFileUploadedListener = { newFile ->
            runOnUiThread {
                refreshReceivedFiles()
                Toast.makeText(this, "Received: ${newFile.name}", Toast.LENGTH_SHORT).show()
            }
        }

        FileServerService.onStagedFilesChangedListener = {
            runOnUiThread {
                refreshSharedFiles()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIncomingShareIntent(it) }
    }

    private fun setupRecyclerViews() {
        sharedAdapter = SharedFileAdapter(sharedFilesList) { fileToRemove ->
            FileServerService.removeStagedFile(fileToRemove.id)
            refreshSharedFiles()
        }
        binding.rvSharedFiles.layoutManager = LinearLayoutManager(this)
        binding.rvSharedFiles.adapter = sharedAdapter

        receivedAdapter = ReceivedFileAdapter(this, receivedFilesList) { fileToDelete ->
            if (fileToDelete.localPath != null) {
                val f = File(fileToDelete.localPath)
                if (f.exists()) f.delete()
            }
            refreshReceivedFiles()
        }
        binding.rvReceivedFiles.layoutManager = LinearLayoutManager(this)
        binding.rvReceivedFiles.adapter = receivedAdapter
    }

    private fun setupListeners() {
        binding.btnToggleServer.setOnClickListener {
            if (FileServerService.isRunning) {
                stopServer()
            } else {
                startServer()
            }
        }

        binding.btnAddFiles.setOnClickListener {
            try {
                filePickerLauncher.launch(arrayOf("*/*"))
            } catch (e: Exception) {
                fallbackPickerLauncher.launch("*/*")
            }
        }

        binding.btnClearShared.setOnClickListener {
            FileServerService.clearStagedFiles()
            refreshSharedFiles()
            Toast.makeText(this, "Cleared staged files", Toast.LENGTH_SHORT).show()
        }

        binding.btnCopyUrl.setOnClickListener {
            val url = binding.tvServerUrl.text.toString()
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("MiShare URL", url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "URL copied! Open in PC browser", Toast.LENGTH_SHORT).show()
        }

        // Share link via messaging/email to PC
        binding.btnShareLink.setOnClickListener {
            val url = binding.tvServerUrl.text.toString()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "MiShare PC Link")
                putExtra(Intent.EXTRA_TEXT, "Open this link on your PC to download/upload files: $url")
            }
            startActivity(Intent.createChooser(shareIntent, "Share link to PC"))
        }
    }

    private fun startServer() {
        val intent = Intent(this, FileServerService::class.java).apply {
            action = FileServerService.ACTION_START
            putExtra(FileServerService.EXTRA_PORT, 8888)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopServer() {
        val intent = Intent(this, FileServerService::class.java).apply {
            action = FileServerService.ACTION_STOP
        }
        startService(intent)
    }

    private fun updateUiState(isRunning: Boolean) {
        val ip = NetworkUtils.getLocalIpAddress()
        val port = FileServerService.currentPort

        if (isRunning && ip != null) {
            val url = "http://$ip:$port"
            binding.tvStatusBadge.text = getString(R.string.server_status_online)
            binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_online_bg))
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_online))

            binding.btnToggleServer.text = getString(R.string.stop_server)
            binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.status_offline)

            binding.layoutUrlSection.visibility = View.VISIBLE
            binding.tvServerUrl.text = url

            // Generate QR Code
            val qrBitmap = QrCodeHelper.generateQrCode(url, 400)
            if (qrBitmap != null) {
                binding.imgQrCode.setImageBitmap(qrBitmap)
                binding.layoutQrSection.visibility = View.VISIBLE
            }
        } else {
            binding.tvStatusBadge.text = getString(R.string.server_status_offline)
            binding.tvStatusBadge.setBackgroundColor(ContextCompat.getColor(this, R.color.status_offline_bg))
            binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_offline))

            binding.btnToggleServer.text = getString(R.string.start_server)
            binding.btnToggleServer.backgroundTintList = ContextCompat.getColorStateList(this, R.color.primary)

            binding.layoutUrlSection.visibility = View.GONE
            binding.layoutQrSection.visibility = View.GONE
        }

        updateNetworkInfo()
        refreshSharedFiles()
    }

    private fun updateNetworkInfo() {
        val ssid = NetworkUtils.getWifiSSID(this)
        binding.tvWifiBadge.text = ssid
    }

    /**
     * Cache/Stage files into local internal storage to prevent Uri permission expiration,
     * allow high-speed zero-latency streaming to PC, and eliminate Android permission denial.
     */
    private fun stageSelectedUris(uris: List<Uri>) {
        Toast.makeText(this, "Staging ${uris.size} file(s)...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val stagingDir = File(cacheDir, "shared_staging")
            if (!stagingDir.exists()) stagingDir.mkdirs()

            for (uri in uris) {
                try {
                    // Try persistable permission if possible
                    try {
                        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    } catch (_: Exception) {}

                    var name = "file_${System.currentTimeMillis()}"
                    var size: Long = 0
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

                    contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIndex != -1) name = cursor.getString(nameIndex)
                            if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                        }
                    }

                    // Copy file to cache for permanent access during transfer
                    val cleanSafeName = name.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
                    val targetCacheFile = File(stagingDir, "${System.currentTimeMillis()}_$cleanSafeName")

                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(targetCacheFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    if (targetCacheFile.exists() && targetCacheFile.length() > 0) {
                        val shared = SharedFile(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            size = targetCacheFile.length(),
                            mimeType = mimeType,
                            uri = uri,
                            localPath = targetCacheFile.absolutePath,
                            timestamp = System.currentTimeMillis()
                        )
                        FileServerService.addStagedFile(shared)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            withContext(Dispatchers.Main) {
                refreshSharedFiles()
                // Auto start server if not running so user can download right away
                if (!FileServerService.isRunning) {
                    startServer()
                }
                Toast.makeText(
                    this@MainActivity,
                    "Files ready! Download them on your PC browser.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshSharedFiles() {
        sharedFilesList.clear()
        sharedFilesList.addAll(FileServerService.stagedFiles)
        sharedAdapter.updateList(sharedFilesList)

        if (sharedFilesList.isEmpty()) {
            binding.tvEmptyShared.visibility = View.VISIBLE
            binding.rvSharedFiles.visibility = View.GONE
            binding.btnClearShared.visibility = View.GONE
        } else {
            binding.tvEmptyShared.visibility = View.GONE
            binding.rvSharedFiles.visibility = View.VISIBLE
            binding.btnClearShared.visibility = View.VISIBLE
        }
    }

    private fun refreshReceivedFiles() {
        val server = FileServerService.currentServer
        val list = server?.getReceivedFiles() ?: emptyList()
        receivedFilesList.clear()
        receivedFilesList.addAll(list)
        receivedAdapter.updateList(receivedFilesList)

        if (receivedFilesList.isEmpty()) {
            binding.tvEmptyReceived.visibility = View.VISIBLE
            binding.rvReceivedFiles.visibility = View.GONE
        } else {
            binding.tvEmptyReceived.visibility = View.GONE
            binding.rvReceivedFiles.visibility = View.VISIBLE
        }
    }

    private fun handleIncomingShareIntent(intent: Intent) {
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            uri?.let { stageSelectedUris(listOf(it)) }
        } else if (Intent.ACTION_SEND_MULTIPLE == action && type != null) {
            val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            uris?.let { stageSelectedUris(it) }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (toRequest.isNotEmpty()) {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }
}
