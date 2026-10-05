package com.mainul.mishare.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mainul.mishare.databinding.ItemSharedFileBinding
import com.mainul.mishare.model.SharedFile
import java.text.DecimalFormat
import kotlin.math.log10
import kotlin.math.pow

class SharedFileAdapter(
    private val files: MutableList<SharedFile>,
    private val onRemoveClick: (SharedFile) -> Unit
) : RecyclerView.Adapter<SharedFileAdapter.ViewHolder>() {

    inner class ViewHolder(val binding: ItemSharedFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSharedFileBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = files[position]
        holder.binding.tvFileName.text = file.name
        holder.binding.tvFileSize.text = formatFileSize(file.size)

        holder.binding.btnRemoveFile.setOnClickListener {
            onRemoveClick(file)
        }
    }

    override fun getItemCount(): Int = files.size

    fun updateList(newFiles: List<SharedFile>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        val df = DecimalFormat("#,##0.#")
        return "${df.format(size / 1024.0.pow(digitGroups.toDouble()))} ${units[digitGroups]}"
    }
}
