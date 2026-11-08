package org.fossify.gallery.saf

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import org.fossify.commons.extensions.formatSize
import org.fossify.gallery.R

class SafBrowserAdapter(
    private val context: Context,
    private val onClick: (SafBrowserItem) -> Unit
) : RecyclerView.Adapter<SafBrowserAdapter.ViewHolder>() {
    private val items = ArrayList<SafBrowserItem>()

    fun submitItems(newItems: List<SafBrowserItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            minimumHeight = 80.dp()
            isClickable = true
            isFocusable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        val image = ImageView(parent.context).apply {
            layoutParams = LinearLayout.LayoutParams(64.dp(), 64.dp())
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val textColumn = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 16.dp()
            }
        }
        val name = TextView(parent.context).apply {
            textSize = 16f
            maxLines = 2
        }
        val details = TextView(parent.context).apply {
            textSize = 12f
            alpha = 0.7f
            maxLines = 1
        }
        textColumn.addView(name)
        textColumn.addView(details)
        row.addView(image)
        row.addView(textColumn)
        return ViewHolder(row, image, name, details)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    private fun Int.dp() = (this * context.resources.displayMetrics.density).toInt()

    inner class ViewHolder(
        private val row: LinearLayout,
        private val image: ImageView,
        private val name: TextView,
        private val details: TextView
    ) : RecyclerView.ViewHolder(row) {
        fun bind(item: SafBrowserItem) {
            name.text = item.displayName
            row.setOnClickListener { onClick(item) }
            when (item) {
                is SafBrowserItem.Directory -> {
                    Glide.with(image).clear(image)
                    image.setImageResource(org.fossify.commons.R.drawable.ic_folder_vector)
                    details.text = context.getString(R.string.saf_folder)
                }
                is SafBrowserItem.Media -> {
                    details.text = item.size?.formatSize() ?: context.getString(
                        if (item.type == SafMediaType.IMAGE) R.string.saf_image else R.string.saf_video
                    )
                    Glide.with(image)
                        .load(item.documentUri)
                        .signature(ObjectKey("${item.documentUri}|${item.lastModified ?: 0L}|${item.size ?: -1L}"))
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .placeholder(org.fossify.commons.R.drawable.ic_file_generic)
                        .into(image)
                }
            }
        }
    }
}
