package com.mainul.mishare.adapter

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.mainul.mishare.databinding.ItemReceivedFileBinding
import com.mainul.mishare.model.SharedFile
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

class ReceivedFileAdapter(
    private val context: Context,
    private val files: MutableList<SharedFile>,
    private val onDeleteClick: (SharedFile) -> Unit
) : RecyclerView.Adapter<ReceivedFileAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemReceivedFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReceivedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.binding.tvReceivedName.text = file.name

        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(file.timestamp))
        holder.binding.tvReceivedMeta.text = "${formatFileSize(file.size)} • $timeStr"

        holder.binding.btnOpenReceived.setOnClickListener {
            openFile(file)
        }

        holder.binding.btnDeleteReceived.setOnClickListener {
            onDeleteClick(file)
        }
    }

    override fun getItemCount(): Int = files.size

    fun updateList(newFiles: List<SharedFile>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    private fun openFile(file: SharedFile) {
        if (file.localPath == null) return
        val target = File(file.localPath)
        if (!target.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, file.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return "${df.format(size / 1024.0.pow(digitGroups.toDouble()))} ${units[digitGroups]}"
    }
}
