package org.fossify.gallery.activities

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.exifinterface.media.ExifInterface
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private data class SafPagerItem(
    val documentUri: Uri,
    val displayName: String,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    val type: SafMediaType
)

private data class SafImageDetails(
    val rotation: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val dateTaken: String? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null
)

class SafMediaViewerActivity : SimpleActivity() {
    private lateinit var pager: ViewPager
    private lateinit var title: TextView
    private lateinit var infoButton: TextView
    private lateinit var mediaItems: List<SafPagerItem>
    private lateinit var pagerAdapter: SafPagerAdapter
    private val imageExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var currentPosition = 0
    private var isResumed = false
    private var sessionId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        mediaItems = readMediaItems()
        if (mediaItems.isEmpty()) {
            finish()
            return
        }
        currentPosition = (savedInstanceState?.getInt(KEY_POSITION)
            ?: intent.getIntExtra(EXTRA_POSITION, 0)).coerceIn(mediaItems.indices)
        buildContent()
        pagerAdapter = SafPagerAdapter()
        pager.adapter = pagerAdapter
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                pagerAdapter.page(currentPosition)?.setActive(false)
                currentPosition = position
                updateCurrentItem()
                pagerAdapter.page(currentPosition)?.setActive(isResumed)
            }
        })
        pager.currentItem = currentPosition
        updateCurrentItem()
    }

    private fun readMediaItems(): List<SafPagerItem> {
        sessionId?.let { pagerSessions[it] }?.let { return it }

        val uri = intent.data ?: return emptyList()
        val type = runCatching {
            SafMediaType.valueOf(intent.getStringExtra(EXTRA_TYPE).orEmpty())
        }.getOrNull() ?: return emptyList()
        return listOf(
            SafPagerItem(
                uri,
                intent.getStringExtra(EXTRA_NAME).orEmpty(),
                intent.getStringExtra(EXTRA_SINGLE_MIME_TYPE),
                intent.getLongExtra(EXTRA_SINGLE_SIZE, -1L),
                intent.getLongExtra(EXTRA_SINGLE_MODIFIED, 0L),
                type
            )
        )
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
            text = "‹"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.saf_back)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(56.dp(), 56.dp()))
        title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        infoButton = TextView(this).apply {
            text = getString(R.string.saf_details)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(8.dp(), 0, 8.dp(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showImageDetails() }
        }
        header.addView(infoButton, LinearLayout.LayoutParams(-2, 56.dp()))
        val share = TextView(this).apply {
            text = getString(R.string.saf_share)
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12.dp(), 0, 12.dp(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { shareMedia() }
        }
        header.addView(share, LinearLayout.LayoutParams(-2, 56.dp()))
        pager = ViewPager(this).apply {
            id = View.generateViewId()
            offscreenPageLimit = 1
        }
        root.addView(header)
        root.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun updateCurrentItem() {
        val item = mediaItems[currentPosition]
        title.text = item.displayName
        infoButton.visibility = if (item.type == SafMediaType.IMAGE) View.VISIBLE else View.GONE
    }

    private fun showImageDetails() {
        val item = mediaItems[currentPosition]
        val page = pagerAdapter.page(currentPosition)
        if (item.type != SafMediaType.IMAGE) return
        val details = page?.imageDetails ?: SafImageDetails()
        val width = page?.imageWidth?.takeIf { it > 0 } ?: details.width
        val height = page?.imageHeight?.takeIf { it > 0 } ?: details.height
        val lines = buildList {
            if (width > 0 && height > 0) {
                add(getString(R.string.saf_dimensions_format, width, height))
            }
            if (item.size > 0L) {
                add(getString(R.string.saf_file_size, item.size.formatSize()))
            }
            if (item.lastModified > 0L) {
                val modified = android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", item.lastModified)
                add(getString(R.string.saf_last_modified, modified))
            }
            details.dateTaken?.takeIf(String::isNotBlank)?.let {
                add(getString(R.string.saf_date_taken, it))
            }
            listOfNotNull(details.cameraMake, details.cameraModel)
                .filter(String::isNotBlank)
                .joinToString(" ")
                .takeIf(String::isNotBlank)
                ?.let { add(getString(R.string.saf_camera, it)) }
        }
        getAlertDialogBuilder()
            .setTitle(item.displayName)
            .setMessage(lines.ifEmpty { listOf(getString(R.string.saf_no_details)) }.joinToString("\n"))
            .setPositiveButton(org.fossify.commons.R.string.ok, null)
            .show()
    }

    private fun shareMedia() {
        val item = mediaItems[currentPosition]
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType?.takeIf(String::isNotBlank) ?: when (item.type) {
                SafMediaType.IMAGE -> "image/*"
                SafMediaType.VIDEO -> "video/*"
            }
            putExtra(Intent.EXTRA_STREAM, item.documentUri)
            clipData = ClipData.newUri(contentResolver, item.displayName, item.documentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.saf_share)))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_POSITION, currentPosition)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (::pagerAdapter.isInitialized) pagerAdapter.page(currentPosition)?.setActive(true)
    }

    override fun onPause() {
        isResumed = false
        if (::pagerAdapter.isInitialized) pagerAdapter.page(currentPosition)?.setActive(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (::pagerAdapter.isInitialized) pagerAdapter.releaseAll()
        imageExecutor.shutdownNow()
        if (isFinishing) sessionId?.let(pagerSessions::remove)
        super.onDestroy()
    }

    private inner class SafPagerAdapter : PagerAdapter() {
        private val pages = HashMap<Int, SafMediaPageView>()

        override fun getCount() = mediaItems.size

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val page = SafMediaPageView(mediaItems[position])
            pages[position] = page
            container.addView(page)
            page.load()
            page.setActive(position == currentPosition && isResumed)
            return page
        }

        override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
            (item as? SafMediaPageView)?.release()
            container.removeView(item as View)
            pages.remove(position)
        }

        override fun isViewFromObject(view: View, item: Any) = view === item

        fun page(position: Int) = pages[position]

        fun releaseAll() {
            pages.values.toList().forEach { it.release() }
            pages.clear()
        }
    }

    private inner class SafMediaPageView(
        private val item: SafPagerItem
    ) : FrameLayout(this@SafMediaViewerActivity) {
        private val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
        }
        private val zoomImage = SubsamplingScaleImageView(context).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
        }
        private val video = VideoView(context).apply { visibility = View.GONE }
        private val loading = ProgressBar(context).apply { visibility = View.GONE }
        private var imageLoadTask: Future<*>? = null
        private var isReleased = false
        private var isActive = false
        private var videoPrepared = false
        var imageDetails = SafImageDetails()
            private set
        var imageWidth = 0
            private set
        var imageHeight = 0
            private set

        init {
            setBackgroundColor(Color.BLACK)
            addView(zoomImage, LayoutParams(-1, -1))
            addView(image, LayoutParams(-1, -1))
            addView(video, LayoutParams(-1, -1))
            addView(loading, LayoutParams(-2, -2, Gravity.CENTER))
        }

        fun load() {
            when (item.type) {
                SafMediaType.IMAGE -> loadImage()
                SafMediaType.VIDEO -> loadVideo()
            }
        }

        fun setActive(active: Boolean) {
            isActive = active
            if (!videoPrepared) return
            if (active && isResumed) {
                video.start()
            } else if (video.isPlaying) {
                video.pause()
            }
        }

        private fun loadVideo() {
            video.visibility = View.VISIBLE
            loading.visibility = View.VISIBLE
            video.setMediaController(MediaController(this@SafMediaViewerActivity).apply { setAnchorView(video) })
            video.setOnPreparedListener {
                videoPrepared = true
                loading.visibility = View.GONE
                if (isActive && isResumed) video.start()
            }
            video.setOnErrorListener { _, _, _ ->
                loading.visibility = View.GONE
                if (isActive) toast(R.string.saf_media_open_failed)
                true
            }
            video.setVideoURI(item.documentUri)
        }

        private fun loadImage() {
            loading.visibility = View.VISIBLE
            imageLoadTask?.cancel(true)
            imageLoadTask = imageExecutor.submit {
                val metadata = readImageDetails()
                post {
                    if (isReleased) return@post
                    imageDetails = metadata
                    imageWidth = metadata.width
                    imageHeight = metadata.height
                    if (metadata.rotation == 90 || metadata.rotation == 270) {
                        val width = imageWidth
                        imageWidth = imageHeight
                        imageHeight = width
                    }
                    val signature = ObjectKey("${item.documentUri}|${item.lastModified}|${item.size}")
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
                                val width = metadata.width.takeIf { it > 0 } ?: zoomImage.sWidth
                                val height = metadata.height.takeIf { it > 0 } ?: zoomImage.sHeight
                                if (metadata.rotation == 90 || metadata.rotation == 270) {
                                    imageWidth = height
                                    imageHeight = width
                                } else {
                                    imageWidth = width
                                    imageHeight = height
                                }
                            }

                            override fun onImageLoadError(e: Exception) {
                                if (isReleased) return
                                loading.visibility = View.GONE
                                zoomImage.visibility = View.GONE
                                image.visibility = View.VISIBLE
                                Glide.with(this@SafMediaViewerActivity)
                                    .load(item.documentUri)
                                    .signature(signature)
                                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                    .placeholder(org.fossify.commons.R.drawable.ic_file_generic)
                                    .fitCenter()
                                    .into(image)
                            }

                            override fun onImageRotation(degrees: Int) = Unit
                            override fun onUpEvent() = Unit
                        }
                        visibility = View.VISIBLE
                        setImage(item.documentUri.toString())
                    }
                }
            }
        }

        private fun readImageDetails(): SafImageDetails = runCatching {
            contentResolver.openInputStream(item.documentUri)?.use { input ->
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

        fun release() {
            if (isReleased) return
            isReleased = true
            imageLoadTask?.cancel(true)
            video.stopPlayback()
            if (!isDestroyed) Glide.with(this@SafMediaViewerActivity).clear(image)
            zoomImage.recycle()
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SESSION_ID = "media_session_id"
        private const val EXTRA_POSITION = "media_position"
        private const val EXTRA_TYPE = "media_type"
        private const val EXTRA_NAME = "display_name"
        private const val EXTRA_SINGLE_MIME_TYPE = "mime_type"
        private const val EXTRA_SINGLE_SIZE = "media_size"
        private const val EXTRA_SINGLE_MODIFIED = "media_modified"
        private const val KEY_POSITION = "current_position"
        private val pagerSessions = ConcurrentHashMap<String, List<SafPagerItem>>()

        fun createIntent(context: Context, item: SafBrowserItem.Media) =
            createIntent(context, listOf(item), 0)

        fun createIntent(context: Context, items: List<SafBrowserItem.Media>, position: Int): Intent {
            require(items.isNotEmpty())
            val selectedPosition = position.coerceIn(items.indices)
            val selectedItem = items[selectedPosition]
            val sessionId = UUID.randomUUID().toString()
            pagerSessions[sessionId] = items.map {
                SafPagerItem(
                    it.documentUri,
                    it.displayName,
                    it.mimeType,
                    it.size ?: -1L,
                    it.lastModified ?: 0L,
                    it.type
                )
            }
            return Intent(context, SafMediaViewerActivity::class.java).apply {
                setData(selectedItem.documentUri)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_POSITION, selectedPosition)
                putExtra(EXTRA_TYPE, selectedItem.type.name)
                putExtra(EXTRA_NAME, selectedItem.displayName)
                putExtra(EXTRA_SINGLE_MIME_TYPE, selectedItem.mimeType)
                putExtra(EXTRA_SINGLE_SIZE, selectedItem.size ?: -1L)
                putExtra(EXTRA_SINGLE_MODIFIED, selectedItem.lastModified ?: 0L)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
