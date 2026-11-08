package org.fossify.gallery.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.toast
import org.fossify.gallery.R
import org.fossify.gallery.saf.SafBrowseResult
import org.fossify.gallery.saf.SafBrowserAdapter
import org.fossify.gallery.saf.SafBrowserItem
import org.fossify.gallery.saf.SafDocumentEnumerator
import org.fossify.gallery.saf.SafDocumentOperations
import org.fossify.gallery.saf.SafCreateFolderResult
import org.fossify.gallery.saf.SafItemMutationResult
import org.fossify.gallery.saf.SafLocation
import org.fossify.gallery.saf.SafLocationStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

class SafBrowserActivity : SimpleActivity() {
    private lateinit var treeUri: Uri
    private lateinit var location: SafLocation
    private lateinit var recyclerView: RecyclerView
    private lateinit var refreshLayout: SwipeRefreshLayout
    private lateinit var loading: ProgressBar
    private lateinit var empty: TextView
    private lateinit var title: TextView
    private lateinit var createFolderButton: TextView
    private lateinit var chooseFolderButton: TextView
    private lateinit var executor: ExecutorService
    private val folderStack = ArrayList<Folder>()
    private var queryTask: Future<*>? = null
    private var cancellationSignal: CancellationSignal? = null
    private var adapter: SafBrowserAdapter? = null
    private var selectDestinationMode = false
    private var pendingTransfer: PendingTransfer? = null

    private data class PendingTransfer(
        val action: TransferAction,
        val documentId: String,
        val sourceParentDocumentId: String,
        val displayName: String,
        val mimeType: String,
        val isDirectory: Boolean
    )

    private enum class TransferAction { COPY, MOVE }

    private val destinationPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val transfer = pendingTransfer
        pendingTransfer = null
        val data = result.data
        val targetTreeUri = data?.getStringExtra(EXTRA_RESULT_TREE_URI)?.let(Uri::parse)
        val targetDocumentId = data?.getStringExtra(EXTRA_RESULT_DOCUMENT_ID)
        val targetPathIds = data?.getStringArrayListExtra(EXTRA_RESULT_PATH_IDS).orEmpty()
        if (result.resultCode == RESULT_OK && transfer != null && targetTreeUri != null && !targetDocumentId.isNullOrBlank()) {
            val targetLocation = SafLocationStore(this).getLocations().firstOrNull { it.treeUri == targetTreeUri }
            if (targetLocation?.hasWriteAccess != true) {
                toast(R.string.saf_location_unavailable)
                return@registerForActivityResult
            }
            if (transfer.isDirectory && transfer.documentId in targetPathIds) {
                toast(R.string.saf_operation_unsupported)
                return@registerForActivityResult
            }
            val operations = SafDocumentOperations(contentResolver)
            when (transfer.action) {
                TransferAction.COPY -> runItemMutation(R.string.saf_item_copied) {
                    operations.copyDocument(
                        treeUri,
                        transfer.documentId,
                        transfer.displayName,
                        transfer.mimeType,
                        transfer.isDirectory,
                        targetTreeUri,
                        targetDocumentId
                    )
                }
                TransferAction.MOVE -> runItemMutation(R.string.saf_item_moved) {
                    operations.moveDocument(
                        treeUri,
                        transfer.documentId,
                        transfer.sourceParentDocumentId,
                        transfer.displayName,
                        transfer.mimeType,
                        transfer.isDirectory,
                        targetTreeUri,
                        targetDocumentId
                    )
                }
            }
        }
    }

    private data class Folder(val documentId: String, val displayName: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uriValue = intent.getStringExtra(EXTRA_TREE_URI)
        treeUri = runCatching { uriValue?.let(Uri::parse) }.getOrNull() ?: run {
            finish()
            return
        }
        location = SafLocationStore(this).getLocations().firstOrNull { it.treeUri == treeUri } ?: run {
            toast(R.string.saf_location_unavailable)
            finish()
            return
        }
        if (!location.hasReadAccess) {
            toast(R.string.saf_location_unavailable)
            finish()
            return
        }
        val rootDocumentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: run {
            finish()
            return
        }
        selectDestinationMode = intent.getBooleanExtra(EXTRA_SELECT_DESTINATION, false)
        restoreFolderStack(savedInstanceState, rootDocumentId, location.displayName)
        restorePendingTransfer(savedInstanceState)

        executor = Executors.newSingleThreadExecutor()
        buildContent()
        adapter = SafBrowserAdapter(this, ::onItemClicked, ::onItemLongClicked)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        refreshLayout.setOnRefreshListener { loadChildren() }
        onBackPressedDispatcher.addCallback(this) { navigateUp() }
        loadChildren()
    }

    private fun restorePendingTransfer(state: Bundle?) {
        val action = state?.getString(KEY_TRANSFER_ACTION)?.let { runCatching { TransferAction.valueOf(it) }.getOrNull() }
        val documentId = state?.getString(KEY_TRANSFER_DOCUMENT_ID)
        val parentId = state?.getString(KEY_TRANSFER_PARENT_ID)
        val displayName = state?.getString(KEY_TRANSFER_NAME)
        val mimeType = state?.getString(KEY_TRANSFER_MIME)
        if (action != null && documentId != null && parentId != null && displayName != null && mimeType != null) {
            pendingTransfer = PendingTransfer(
                action,
                documentId,
                parentId,
                displayName,
                mimeType,
                state.getBoolean(KEY_TRANSFER_IS_DIRECTORY)
            )
        }
    }

    private fun restoreFolderStack(state: Bundle?, rootId: String, rootName: String) {
        val ids = state?.getStringArrayList(KEY_FOLDER_IDS)
        val names = state?.getStringArrayList(KEY_FOLDER_NAMES)
        if (ids != null && names != null && ids.size == names.size && ids.isNotEmpty()) {
            folderStack.addAll(ids.indices.map { Folder(ids[it], names[it]) })
        } else {
            folderStack.add(Folder(rootId, rootName))
        }
    }

    private fun buildContent() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 0, 16.dp(), 0)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 32f
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.saf_back)
            setOnClickListener { navigateUp() }
        }
        header.addView(back, LinearLayout.LayoutParams(48.dp(), 56.dp()))
        title = TextView(this).apply {
            textSize = 18f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        createFolderButton = TextView(this).apply {
            text = getString(R.string.saf_create_folder)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12.dp(), 0, 0, 0)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { promptCreateFolder() }
        }
        header.addView(createFolderButton, LinearLayout.LayoutParams(-2, 56.dp()))
        chooseFolderButton = TextView(this).apply {
            text = getString(R.string.saf_choose_folder)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(12.dp(), 0, 0, 0)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            setOnClickListener { returnSelectedFolder() }
        }
        header.addView(chooseFolderButton, LinearLayout.LayoutParams(-2, 56.dp()))

        val frame = FrameLayout(this)
        refreshLayout = SwipeRefreshLayout(this)
        recyclerView = RecyclerView(this)
        refreshLayout.addView(recyclerView)
        frame.addView(refreshLayout, FrameLayout.LayoutParams(-1, -1))
        loading = ProgressBar(this)
        frame.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        empty = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setPadding(24.dp(), 24.dp(), 24.dp(), 24.dp())
            visibility = View.GONE
        }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(header, LinearLayout.LayoutParams(-1, 56.dp()))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(KEY_FOLDER_IDS, ArrayList(folderStack.map { it.documentId }))
        outState.putStringArrayList(KEY_FOLDER_NAMES, ArrayList(folderStack.map { it.displayName }))
        pendingTransfer?.let { transfer ->
            outState.putString(KEY_TRANSFER_ACTION, transfer.action.name)
            outState.putString(KEY_TRANSFER_DOCUMENT_ID, transfer.documentId)
            outState.putString(KEY_TRANSFER_PARENT_ID, transfer.sourceParentDocumentId)
            outState.putString(KEY_TRANSFER_NAME, transfer.displayName)
            outState.putString(KEY_TRANSFER_MIME, transfer.mimeType)
            outState.putBoolean(KEY_TRANSFER_IS_DIRECTORY, transfer.isDirectory)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        cancellationSignal?.cancel()
        queryTask?.cancel(true)
        if (::executor.isInitialized) executor.shutdownNow()
        super.onDestroy()
    }

    private fun loadChildren() {
        if (!::executor.isInitialized || folderStack.isEmpty()) return
        cancellationSignal?.cancel()
        queryTask?.cancel(true)
        val signal = CancellationSignal()
        cancellationSignal = signal
        title.text = folderStack.last().displayName
        loading.visibility = View.VISIBLE
        empty.visibility = View.GONE
        refreshLayout.isRefreshing = false
        val parentId = folderStack.last().documentId
        createFolderButton.visibility = View.GONE
        chooseFolderButton.visibility = View.GONE

        queryTask = executor.submit {
            val supportsCreate = SafDocumentOperations(contentResolver).supportsCreate(treeUri, parentId)
            val canCreateFolder = location.hasWriteAccess && supportsCreate
            val result = SafDocumentEnumerator(contentResolver).listChildren(treeUri, parentId, signal)
            runOnUiThread {
                if (isFinishing || isDestroyed || cancellationSignal !== signal) return@runOnUiThread
                loading.visibility = View.GONE
                refreshLayout.isRefreshing = false
                createFolderButton.visibility = if (canCreateFolder && result is SafBrowseResult.Success) View.VISIBLE else View.GONE
                chooseFolderButton.visibility = if (selectDestinationMode && canCreateFolder && result is SafBrowseResult.Success) View.VISIBLE else View.GONE
                when (result) {
                    is SafBrowseResult.Success -> {
                        adapter?.submitItems(result.items)
                        if (result.items.isEmpty()) showEmptyState(R.string.saf_no_supported_media)
                    }
                    SafBrowseResult.PermissionRevoked -> showEmptyState(R.string.saf_location_unavailable)
                    SafBrowseResult.Unavailable -> showEmptyState(R.string.saf_provider_unavailable)
                    SafBrowseResult.Cancelled -> Unit
                }
            }
        }
    }

    private fun promptCreateFolder() {
        val input = EditText(this).apply {
            hint = getString(R.string.saf_folder_name)
        }
        getAlertDialogBuilder()
            .setTitle(R.string.saf_create_folder)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                createFolder(input.text.toString())
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun createFolder(displayName: String) {
        if (!location.hasWriteAccess || !::executor.isInitialized) return
        val parentId = folderStack.lastOrNull()?.documentId ?: return
        executor.submit {
            val result = SafDocumentOperations(contentResolver).createFolder(treeUri, parentId, displayName)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    is SafCreateFolderResult.Created -> {
                        toast(R.string.saf_folder_created)
                        loadChildren()
                    }
                    SafCreateFolderResult.InvalidName -> toast(R.string.saf_folder_name_invalid)
                    SafCreateFolderResult.Unsupported -> toast(R.string.saf_folder_create_unsupported)
                    SafCreateFolderResult.PermissionRevoked -> toast(R.string.saf_location_unavailable)
                    SafCreateFolderResult.Unavailable -> toast(R.string.saf_folder_create_failed)
                }
            }
        }
    }

    private enum class ItemAction {
        RENAME,
        DELETE,
        COPY,
        MOVE
    }

    private fun onItemLongClicked(item: SafBrowserItem) {
        if (selectDestinationMode) return
        val actions = buildList {
            if (location.hasWriteAccess && item.supportsRename) add(ItemAction.RENAME)
            if (location.hasWriteAccess && item.supportsDelete) add(ItemAction.DELETE)
            if (item.supportsCopy || item is SafBrowserItem.Media) add(ItemAction.COPY)
            if (location.hasWriteAccess && (item.supportsMove ||
                (item is SafBrowserItem.Media && item.supportsDelete))
            ) add(ItemAction.MOVE)
        }
        if (actions.isEmpty()) return

        val labels = actions.map {
            getString(
                when (it) {
                    ItemAction.RENAME -> R.string.saf_rename
                    ItemAction.DELETE -> R.string.saf_delete
                    ItemAction.COPY -> R.string.saf_copy_to
                    ItemAction.MOVE -> R.string.saf_move_to
                }
            )
        }.toTypedArray()
        getAlertDialogBuilder()
            .setItems(labels) { _, index ->
                when (actions[index]) {
                    ItemAction.RENAME -> promptRename(item)
                    ItemAction.DELETE -> confirmDelete(item)
                    ItemAction.COPY -> chooseTransferDestination(item, TransferAction.COPY)
                    ItemAction.MOVE -> chooseTransferDestination(item, TransferAction.MOVE)
                }
            }
            .show()
    }

    private fun chooseTransferDestination(item: SafBrowserItem, action: TransferAction) {
        val writableLocations = SafLocationStore(this).getLocations().filter { it.hasWriteAccess }
            .filter { location ->
                when {
                    itemIsDirectory(item) -> location.treeUri.authority == treeUri.authority
                    action == TransferAction.MOVE && !item.supportsDelete ->
                        location.treeUri.authority == treeUri.authority
                    else -> true
                }
            }
        if (writableLocations.isEmpty()) {
            toast(R.string.saf_no_writable_locations)
            return
        }

        val sourceParentId = folderStack.lastOrNull()?.documentId ?: return
        val mimeType = (item as? SafBrowserItem.Media)?.let {
            it.mimeType?.takeIf(String::isNotBlank) ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(it.displayName.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
        } ?: "application/octet-stream"
        val transfer = PendingTransfer(
            action,
            item.documentId,
            sourceParentId,
            item.displayName,
            mimeType,
            item is SafBrowserItem.Directory
        )
        val labels = writableLocations.map { it.displayName }.toTypedArray()
        getAlertDialogBuilder()
            .setTitle(R.string.saf_select_destination)
            .setItems(labels) { _, index ->
                val destination = writableLocations.getOrNull(index) ?: return@setItems
                pendingTransfer = transfer
                destinationPicker.launch(
                    SafBrowserActivity.createIntent(this, destination, selectDestination = true)
                )
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun itemIsDirectory(item: SafBrowserItem) = item is SafBrowserItem.Directory

    private fun returnSelectedFolder() {
        val currentFolder = folderStack.lastOrNull() ?: return
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_RESULT_TREE_URI, treeUri.toString())
                .putExtra(EXTRA_RESULT_DOCUMENT_ID, currentFolder.documentId)
                .putStringArrayListExtra(EXTRA_RESULT_PATH_IDS, ArrayList(folderStack.map { it.documentId }))
        )
        finish()
    }

    private fun promptRename(item: SafBrowserItem) {
        val input = EditText(this).apply {
            setText(item.displayName)
            setSelection(text.length)
        }
        getAlertDialogBuilder()
            .setTitle(R.string.saf_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runItemMutation(R.string.saf_item_renamed) {
                    SafDocumentOperations(contentResolver).renameDocument(treeUri, item.documentId, input.text.toString())
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: SafBrowserItem) {
        getAlertDialogBuilder()
            .setTitle(R.string.saf_delete)
            .setMessage(getString(R.string.saf_delete_confirmation, item.displayName))
            .setPositiveButton(R.string.saf_delete) { _, _ ->
                runItemMutation(R.string.saf_item_deleted) {
                    SafDocumentOperations(contentResolver).deleteDocument(treeUri, item.documentId)
                }
            }
            .setNegativeButton(org.fossify.commons.R.string.cancel, null)
            .show()
    }

    private fun runItemMutation(successMessage: Int, operation: () -> SafItemMutationResult) {
        if (!::executor.isInitialized) return
        executor.submit {
            val result = operation()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (result) {
                    SafItemMutationResult.Completed -> {
                        toast(successMessage)
                        loadChildren()
                    }
                    SafItemMutationResult.InvalidName -> toast(R.string.saf_folder_name_invalid)
                    SafItemMutationResult.Unsupported -> toast(R.string.saf_operation_unsupported)
                    SafItemMutationResult.PermissionRevoked -> toast(R.string.saf_location_unavailable)
                    SafItemMutationResult.Unavailable -> toast(R.string.saf_operation_failed)
                    SafItemMutationResult.SourceRetained -> toast(R.string.saf_move_source_retained)
                    SafItemMutationResult.PartialTransfer -> toast(R.string.saf_partial_transfer)
                }
            }
        }
    }

    private fun onItemClicked(item: SafBrowserItem) {
        when (item) {
            is SafBrowserItem.Directory -> {
                folderStack.add(Folder(item.documentId, item.displayName))
                loadChildren()
            }
            is SafBrowserItem.Media -> startActivity(SafMediaViewerActivity.createIntent(this, item))
        }
    }

    private fun navigateUp() {
        if (folderStack.size > 1) {
            folderStack.removeAt(folderStack.lastIndex)
            loadChildren()
        } else {
            finish()
        }
    }

    private fun showEmptyState(message: Int) {
        adapter?.submitItems(emptyList())
        empty.setText(message)
        empty.visibility = View.VISIBLE
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_TREE_URI = "tree_uri"
        private const val EXTRA_SELECT_DESTINATION = "select_destination"
        private const val EXTRA_RESULT_TREE_URI = "result_tree_uri"
        private const val EXTRA_RESULT_DOCUMENT_ID = "result_document_id"
        private const val EXTRA_RESULT_PATH_IDS = "result_path_ids"
        private const val KEY_FOLDER_IDS = "folder_ids"
        private const val KEY_FOLDER_NAMES = "folder_names"
        private const val KEY_TRANSFER_ACTION = "transfer_action"
        private const val KEY_TRANSFER_DOCUMENT_ID = "transfer_document_id"
        private const val KEY_TRANSFER_PARENT_ID = "transfer_parent_id"
        private const val KEY_TRANSFER_NAME = "transfer_name"
        private const val KEY_TRANSFER_MIME = "transfer_mime"
        private const val KEY_TRANSFER_IS_DIRECTORY = "transfer_is_directory"

        fun createIntent(context: Context, location: SafLocation, selectDestination: Boolean = false) =
            Intent(context, SafBrowserActivity::class.java)
                .putExtra(EXTRA_TREE_URI, location.treeUri.toString())
                .putExtra(EXTRA_SELECT_DESTINATION, selectDestination)
    }
}
