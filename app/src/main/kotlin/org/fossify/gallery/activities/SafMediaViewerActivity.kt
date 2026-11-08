package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.saf.SafBrowserItem
import org.fossify.gallery.saf.SafMediaType

class SafMediaViewerActivity : SimpleActivity() {
    private lateinit var image: ImageView
    private lateinit var video: VideoView
    private lateinit var loading: ProgressBar
    private lateinit var documentUri: android.net.Uri
    private lateinit var displayName: String
    private lateinit var mimeType: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentUri = intent.data ?: run {
            finish()
            return
        }
        displayName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val mediaType = intent.getStringExtra(EXTRA_TYPE)
        mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)?.takeIf {
            it.startsWith(if (mediaType == SafMediaType.IMAGE.name) "image/" else "video/", true)
        } ?: if (mediaType == SafMediaType.IMAGE.name) "image/*" else "video/*"
        buildContent()
        findViewById<TextView>(VIEWER_TITLE).text = displayName
        findViewById<View>(VIEWER_BACK).setOnClickListener { finish() }
        findViewById<View>(VIEWER_SHARE).setOnClickListener { shareMedia() }

        val size = intent.getLongExtra(EXTRA_SIZE, -1L)
        val modified = intent.getLongExtra(EXTRA_MODIFIED, 0L)
        when (mediaType) {
            SafMediaType.IMAGE.name -> {
                image.visibility = View.VISIBLE
                Glide.with(this).load(documentUri)
                    .signature(ObjectKey("$documentUri|$modified|$size"))
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .fitCenter()
                    .into(image)
            }
            SafMediaType.VIDEO.name -> {
                video.visibility = View.VISIBLE
                loading.visibility = View.VISIBLE
                video.setMediaController(MediaController(this).apply { setAnchorView(video) })
                video.setOnPreparedListener {
                    loading.visibility = View.GONE
                    it.start()
                }
                video.setOnErrorListener { _, _, _ ->
                    loading.visibility = View.GONE
                    toast(R.string.saf_media_open_failed)
                    true
                }
                video.setVideoURI(documentUri)
            }
            else -> finish()
        }
    }

    private fun buildContent() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = TextView(this).apply {
            id = VIEWER_BACK
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.saf_back)
        }
        header.addView(back, LinearLayout.LayoutParams(56.dp(), 56.dp()))
        val title = TextView(this).apply {
            id = VIEWER_TITLE
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val share = TextView(this).apply {
            id = VIEWER_SHARE
            text = getString(R.string.saf_share)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12.dp(), 0, 12.dp(), 0)
            isClickable = true
            isFocusable = true
        }
        header.addView(share, LinearLayout.LayoutParams(-2, 56.dp()))
        val frame = FrameLayout(this)
        image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        video = VideoView(this).apply { visibility = View.GONE }
        frame.addView(video, FrameLayout.LayoutParams(-1, -1))
        loading = ProgressBar(this).apply { visibility = View.GONE }
        frame.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(header)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun shareMedia() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, documentUri)
            clipData = ClipData.newUri(contentResolver, displayName, documentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.saf_share)))
    }

    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        if (::image.isInitialized) Glide.with(this).clear(image)
        super.onDestroy()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_TYPE = "media_type"
        private const val EXTRA_SIZE = "media_size"
        private const val EXTRA_MODIFIED = "media_modified"
        private const val EXTRA_MIME_TYPE = "mime_type"
        private const val VIEWER_TITLE = 1001
        private const val VIEWER_BACK = 1002
        private const val VIEWER_SHARE = 1003

        fun createIntent(context: Context, item: SafBrowserItem.Media) =
            Intent(context, SafMediaViewerActivity::class.java)
                .setData(item.documentUri)
                .putExtra(EXTRA_NAME, item.displayName)
                .putExtra(EXTRA_TYPE, item.type.name)
                .putExtra(EXTRA_MIME_TYPE, item.mimeType)
                .putExtra(EXTRA_SIZE, item.size ?: -1L)
                .putExtra(EXTRA_MODIFIED, item.lastModified ?: 0L)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
