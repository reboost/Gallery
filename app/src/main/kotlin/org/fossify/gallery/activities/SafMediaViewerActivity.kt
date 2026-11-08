package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.exifinterface.media.ExifInterface
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import com.davemorrissey.labs.subscaleview.DecoderFactory
import com.davemorrissey.labs.subscaleview.ImageDecoder
import com.davemorrissey.labs.subscaleview.ImageRegionDecoder
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.fossify.commons.extensions.formatSize
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.helpers.MyGlideImageDecoder
import org.fossify.gallery.helpers.PicassoRegionDecoder
import org.fossify.gallery.saf.SafBrowserItem
import org.fossify.gallery.saf.SafMediaType
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SafMediaViewerActivity : SimpleActivity() {
    private data class SafImageDetails(
        val rotation: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val dateTaken: String? = null,
        val cameraMake: String? = null,
        val cameraModel: String? = null
    )

    private lateinit var image: ImageView
    private lateinit var zoomImage: SubsamplingScaleImageView
    private lateinit var video: VideoView
    private lateinit var loading: ProgressBar
    private lateinit var documentUri: Uri
    private lateinit var displayName: String
    private lateinit var mimeType: String
    private var imageSize = -1L
    private var imageLastModified = 0L
    private var imageWidth = 0
    private var imageHeight = 0
    private var imageDetails = SafImageDetails()
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageLoadTask: Future<*>? = null

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
        findViewById<View>(VIEWER_INFO).apply {
            visibility = if (mediaType == SafMediaType.IMAGE.name) View.VISIBLE else View.GONE
            setOnClickListener { showImageDetails() }
        }

        val size = intent.getLongExtra(EXTRA_SIZE, -1L)
        val modified = intent.getLongExtra(EXTRA_MODIFIED, 0L)
        when (mediaType) {
            SafMediaType.IMAGE.name -> loadImage(size, modified)
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
        val info = TextView(this).apply {
            id = VIEWER_INFO
            text = getString(R.string.saf_details)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8.dp(), 0, 8.dp(), 0)
            isClickable = true
            isFocusable = true
        }
        header.addView(info, LinearLayout.LayoutParams(-2, 56.dp()))
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
        zoomImage = SubsamplingScaleImageView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(zoomImage, FrameLayout.LayoutParams(-1, -1))
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        video = VideoView(this).apply { visibility = View.GONE }
        frame.addView(video, FrameLayout.LayoutParams(-1, -1))
        loading = ProgressBar(this).apply { visibility = View.GONE }
        frame.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(header)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun loadImage(size: Long, modified: Long) {
        loading.visibility = View.VISIBLE
        image.visibility = View.GONE
        zoomImage.visibility = View.GONE
        imageSize = size
        imageLastModified = modified
        imageLoadTask?.cancel(true)
        imageLoadTask = imageExecutor.submit {
            val metadata = readImageDetails()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                imageDetails = metadata
                imageWidth = metadata.width
                imageHeight = metadata.height
                if (metadata.rotation == 90 || metadata.rotation == 270) {
                    val width = imageWidth
                    imageWidth = imageHeight
                    imageHeight = width
                }
                val signature = ObjectKey("$documentUri|$modified|$size")
                val metrics = resources.displayMetrics
                val bitmapDecoder = object : DecoderFactory<ImageDecoder> {
                    override fun make(): ImageDecoder = MyGlideImageDecoder(metadata.rotation, signature)
                }
                val regionDecoder = object : DecoderFactory<ImageRegionDecoder> {
                    override fun make(): ImageRegionDecoder = PicassoRegionDecoder(
                        true,
                        metrics.widthPixels,
                        metrics.heightPixels,
                        -1
                    )
                }
                zoomImage.apply {
                    bitmapDecoderFactory = bitmapDecoder
                    regionDecoderFactory = regionDecoder
                    setMaxTileSize(4096)
                    setMinimumTileDpi(-1)
                    maxScale = 10f
                    orientation = metadata.rotation
                    onImageEventListener = object : SubsamplingScaleImageView.OnImageEventListener {
                        override fun onReady() {
                            loading.visibility = View.GONE
                            image.visibility = View.GONE
                            val width = metadata.width.takeIf { it > 0 } ?: sWidth
                            val height = metadata.height.takeIf { it > 0 } ?: sHeight
                            if (metadata.rotation == 90 || metadata.rotation == 270) {
                                imageWidth = height
                                imageHeight = width
                            } else {
                                imageWidth = width
                                imageHeight = height
                            }
                        }

                        override fun onImageLoadError(e: Exception) {
                            loading.visibility = View.GONE
                            zoomImage.visibility = View.GONE
                            image.visibility = View.VISIBLE
                            Glide.with(this@SafMediaViewerActivity)
                                .load(documentUri)
                                .signature(signature)
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .fitCenter()
                                .into(image)
                        }

                        override fun onImageRotation(degrees: Int) = Unit

                        override fun onUpEvent() = Unit
                    }
                    visibility = View.VISIBLE
                    setImage(documentUri.toString())
                }
            }
        }
    }

    private fun readImageDetails(): SafImageDetails = runCatching {
        contentResolver.openInputStream(documentUri)?.use { input ->
            val exif = ExifInterface(input)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90,
                ExifInterface.ORIENTATION_TRANSVERSE -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270,
                ExifInterface.ORIENTATION_TRANSPOSE -> 270
                else -> 0
            }
            SafImageDetails(
                rotation = rotation,
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                dateTaken = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),
                cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE),
                cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)
            )
        } ?: SafImageDetails()
    }.getOrDefault(SafImageDetails())

    private fun showImageDetails() {
        val details = buildList {
            if (imageWidth > 0 && imageHeight > 0) {
                add(getString(R.string.saf_dimensions_format, imageWidth, imageHeight))
            }
            if (imageSize > 0L) {
                add(getString(R.string.saf_file_size, imageSize.formatSize()))
            }
            if (imageLastModified > 0L) {
                val modified = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", imageLastModified)
                add(getString(R.string.saf_last_modified, modified))
            }
            imageDetails.dateTaken?.takeIf(String::isNotBlank)?.let {
                add(getString(R.string.saf_date_taken, it))
            }
            listOfNotNull(imageDetails.cameraMake, imageDetails.cameraModel)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
                ?.let { add(getString(R.string.saf_camera, it)) }
        }
        getAlertDialogBuilder()
            .setTitle(displayName)
            .setMessage(details.ifEmpty { listOf(getString(R.string.saf_no_details)) }.joinToString("\n"))
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .show()
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
        imageLoadTask?.cancel(true)
        imageExecutor.shutdownNow()
        if (::video.isInitialized) video.stopPlayback()
        if (::image.isInitialized) Glide.with(this).clear(image)
        if (::zoomImage.isInitialized) zoomImage.recycle()
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
        private const val VIEWER_INFO = 1004

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
