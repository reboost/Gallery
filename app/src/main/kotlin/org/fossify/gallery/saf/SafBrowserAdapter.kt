package org.fossify.gallery.saf

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import org.fossify.gallery.R
import org.fossify.gallery.databinding.PhotoItemGridBinding
import org.fossify.gallery.databinding.VideoItemGridBinding
import org.fossify.gallery.extensions.config

class SafBrowserAdapter(
    context: Context,
    private val onClick: (SafBrowserItem) -> Unit,
    private val onLongClick: (SafBrowserItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = ArrayList<SafBrowserItem>()
    private val config = context.config

    fun submitItems(newItems: List<SafBrowserItem>) {
        val oldItems = items.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                oldItems[oldPosition].documentUri == newItems[newPosition].documentUri

            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                oldItems[oldPosition] == newItems[newPosition]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun getItemViewType(position: Int): Int =
        if ((items[position] as? SafBrowserItem.Media)?.type == SafMediaType.VIDEO) {
            VIEW_TYPE_VIDEO
        } else {
            VIEW_TYPE_PHOTO
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_VIDEO) {
            VideoViewHolder(VideoItemGridBinding.inflate(inflater, parent, false))
        } else {
            PhotoViewHolder(PhotoItemGridBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        when (holder) {
            is PhotoViewHolder -> holder.bind(item)
            is VideoViewHolder -> holder.bind(item as SafBrowserItem.Media)
        }
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        holder.itemView.findViewById<ImageView>(R.id.medium_thumbnail)?.let { thumbnail ->
            Glide.with(thumbnail).clear(thumbnail)
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = items.size

    private fun thumbnailSignature(item: SafBrowserItem.Media) =
        ObjectKey("${item.documentUri}|${item.lastModified ?: 0L}|${item.size ?: -1L}")

    private inner class PhotoViewHolder(
        private val binding: PhotoItemGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SafBrowserItem) {
            binding.favorite.visibility = View.GONE
            binding.mediumCheck.visibility = View.GONE
            binding.fileType.visibility = View.GONE
            binding.mediumName.text = item.displayName
            binding.mediumName.visibility = if (item is SafBrowserItem.Directory || config.displayFileNames) {
                View.VISIBLE
            } else {
                View.GONE
            }
            val padding = config.thumbnailSpacing.takeIf { it <= 1 } ?: 0
            binding.mediaItemHolder.setPadding(padding, padding, padding, padding)
            when (item) {
                is SafBrowserItem.Directory -> {
                    Glide.with(binding.mediumThumbnail).clear(binding.mediumThumbnail)
                    binding.mediumThumbnail.scaleType = ImageView.ScaleType.CENTER
                    binding.mediumThumbnail.setImageResource(org.fossify.commons.R.drawable.ic_folder_vector)
                }
                is SafBrowserItem.Media -> {
                    binding.mediumThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                    Glide.with(binding.mediumThumbnail)
                        .load(item.documentUri)
                        .signature(thumbnailSignature(item))
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .centerCrop()
                        .placeholder(org.fossify.commons.R.drawable.ic_file_generic)
                        .into(binding.mediumThumbnail)
                }
            }
        }
    }

    private inner class VideoViewHolder(
        private val binding: VideoItemGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SafBrowserItem.Media) {
            binding.favorite.visibility = View.GONE
            binding.mediumCheck.visibility = View.GONE
            binding.mediumName.text = item.displayName
            binding.mediumName.visibility = if (config.displayFileNames) View.VISIBLE else View.GONE
            val padding = config.thumbnailSpacing.takeIf { it <= 1 } ?: 0
            binding.mediaItemHolder.setPadding(padding, padding, padding, padding)
            binding.videoDuration.visibility = View.GONE
            binding.playPortraitOutline.setImageResource(R.drawable.ic_play_vector)
            binding.playPortraitOutline.visibility = View.VISIBLE
            binding.mediumThumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(binding.mediumThumbnail)
                .load(item.documentUri)
                .signature(thumbnailSignature(item))
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .centerCrop()
                .placeholder(org.fossify.commons.R.drawable.ic_file_generic)
                .into(binding.mediumThumbnail)
        }
    }

    private companion object {
        const val VIEW_TYPE_PHOTO = 0
        const val VIEW_TYPE_VIDEO = 1
    }
}


/** Matches the local gallery grid's edge and inter-item spacing without path-based item models. */
class SafGridSpacingItemDecoration(
    private val spanCount: Int,
    private val spacing: Int,
    private val addSideSpacing: Boolean
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        if (spacing <= 1) return
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val column = position % spanCount

        if (addSideSpacing) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount
            outRect.bottom = spacing
            if (position < spanCount) outRect.top = spacing
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) outRect.top = spacing
        }
    }
}
