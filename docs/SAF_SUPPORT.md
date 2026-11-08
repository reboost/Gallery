# Storage Access Framework (SAF) support

**Status:** Investigation and implementation proposal. No SAF implementation is included in this change.

## Goal

Allow Gallery to browse image and video collections exposed by Android document providers, not only media discoverable through MediaStore or ordinary filesystem paths. This includes provider-backed storage such as SMB/CIFS or WebDAV mounts, rclone, USB storage providers, and cloud storage providers.

This is broader than adding a native folder picker. The picker grants access to a document tree, but Gallery must also enumerate that tree, represent documents without filesystem paths, load their content, and respect the operations each provider supports.

## Findings from the upstream Fossify repositories

Research checked the public Gallery issue history and related Fossify cross-app issue threads on 8 November 2026.

| Reference | Status / context | Relevance |
| --- | --- | --- |
| [Gallery #201 — Support Android File Chooser (rclone remote)](https://github.com/FossifyOrg/Gallery/issues/201) | Open feature request, filed 13 April 2024 | The primary Gallery request. It describes rclone remotes exposed as a document provider and asks to browse remote photo albums. A comment also asks for provider-backed destinations when moving photos. |
| [Gallery #1135 — Support for Document Picker/SAF](https://github.com/FossifyOrg/Gallery/issues/1135) | Closed as duplicate of #201, 28 August 2026 | Describes the same general gap using a CIFS/NAS document provider. |
| [Gallery #966 — Impossible to save image opened from SAF](https://github.com/FossifyOrg/Gallery/issues/966) | Open bug report, filed 22 February 2026 | Shows a separate URI-handling problem: an externally opened `content://` URI was treated as a path during editing, and the workaround still left Gallery's cached image stale. This is a regression scenario to retest, not evidence that tree browsing is implemented. |
| [Gallery #450 — Add a WebDAV folder to the app](https://github.com/FossifyOrg/Gallery/issues/450) | Closed as invalid / not planned, May 2025 | The reproduction selected a folder under `Android/data`. The maintainer clarified that Android restricts this area (and `Android/obb`); this platform restriction is distinct from general document-provider support. |

### Picker and permission discussions

The comments on [Gallery #201](https://github.com/FossifyOrg/Gallery/issues/201) contain two UI proposals:

- Keep the Fossify picker for ordinary local folders and add an **Other** action that opens Android's system picker. This preserves the familiar local workflow while exposing document providers.
- Remove the custom picker and use the Android picker more broadly. The maintainer later leaned toward removal and linked the cross-app picker issue below.

The related Fossify discussions/issues reinforce the trade-off:

- [General-Discussion #131 — Improve file/folder selection UX](https://github.com/FossifyOrg/General-Discussion/issues/131) describes users selecting a folder twice and being asked to grant access to a parent.
- [General-Discussion #211 — File picker forces permission for parent folder](https://github.com/FossifyOrg/General-Discussion/issues/211) explains why broader ancestor grants are undesirable: they can expose unrelated files and allow more destructive access than the chosen folder requires.
- [General-Discussion #698 — Cannot select existing directory in file picker](https://github.com/FossifyOrg/General-Discussion/issues/698) records directory-selection failures across several Fossify apps, including Gallery, and links back to #201.

No separate Gallery Discussions post specifically about SAF surfaced in the public discussion search. The actionable Gallery design debate is in the #201 issue comments; the cross-app issues above provide the permission and picker context.

A related cross-app request, [Voice Recorder #315](https://github.com/FossifyOrg/Voice-Recorder/issues/315), discusses replacing the custom picker with SAF, but also notes the UX cost of losing automatic local-folder selection. For Gallery, this supports a hybrid design: keep MediaStore for the device's normal media library and use SAF for explicitly selected provider roots.

## Current fork architecture

The fork is based on Fossify Gallery and currently has a path-oriented media pipeline:

- `MediaFetcher.getFoldersToScan()` builds local album paths using MediaStore's `Files` collection and `Images.Media.DATA`.
- `MediaFetcher.getFilesFrom()` uses MediaStore on newer Android versions where applicable, then falls back to `getMediaInFolder()`, which enumerates entries with `File(folder).listFiles()`.
- Removable OTG storage has a separate `getMediaOnOTG()` path. It uses the existing `DocumentFile` helper, but reconstructs filesystem-style paths from OTG document URIs. That is specific to the current OTG implementation and must not be copied as a general SAF strategy.
- `Medium.path` and `Directory.path` are strings treated as filesystem paths by parts of the model and UI. For example, `Medium.getSignature()` may call `File(path).lastModified()`, and `toFileDirItem()` wraps the path in the legacy `FileDirItem` model.
- The Room database is at schema version 10. Media rows are uniquely keyed by `full_path`; directory and include/exclude preferences also use path strings.
- The `androidx.documentfile` dependency is already present (version 1.1.0), and the OTG implementation uses Fossify Commons' `getDocumentFile` helper. This is useful infrastructure, but it does not provide a generic provider-backed album model.
- There is already a `resolveUriScheme()` helper and an editor write path using `ContentResolver.openOutputStream()` for content URIs. The editor therefore has some URI-aware behavior, but this does not make scanning, metadata, caching, and all file operations SAF-aware.

The central mismatch is that a document URI is an opaque provider reference, not a filesystem path. A provider may represent remote files, may not expose a real path, may return unknown size or timestamps, and may support reading but not writing or deleting.

## Recommended design

### 1. Keep MediaStore and add SAF as a separate source

Do not replace the existing MediaStore scanner with SAF.

MediaStore is already the efficient, permission-aware source for the device's normal media library. It should continue to populate ordinary local albums without forcing users to select folders manually. SAF should add user-selected document trees, including providers that are not represented in MediaStore.

For the first UI increment, keep the current local-folder workflow and add an explicit **Other location / Document provider** action that launches the system tree picker. Reconsider replacing the custom picker only after the SAF flow is complete and the local UX has been evaluated.

### 2. Select and persist the exact tree grant

Use `Intent.ACTION_OPEN_DOCUMENT_TREE` for album roots. `ACTION_OPEN_DOCUMENT` selects one document and is not sufficient for browsing a folder hierarchy.

On the picker result:

1. Read the returned tree URI and the read/write grant flags from the result intent.
2. Persist only the flags actually granted by the system with `ContentResolver.takePersistableUriPermission()`.
3. Store the exact selected tree URI and grant modes as a provider source in app state/database.
4. Restore sources on startup by checking persisted grants; handle missing, revoked, moved, or temporarily unavailable roots and let users remove a source, releasing its grant.

Do not silently widen the grant to a parent or ancestor directory. The selected tree is the user's access boundary.

### 3. Use document IDs and content URIs end-to-end

Introduce a small source-aware abstraction at the media boundary. A local item can retain its existing filesystem path; a document-provider item should retain its tree URI plus opaque document ID (and derived document URI). Do not infer a physical path from a document ID or URI.

Use `DocumentsContract` to query children beneath the persisted tree and read the metadata the provider exposes, such as document ID, display name, MIME type, size, last-modified time, and flags. Build child/document URIs through the tree URI. The AndroidX `DocumentFile` API is available for basic operations, but direct `DocumentsContract` queries are a better fit for a scanner that needs explicit cursor handling and cancellation.

Metadata is not guaranteed to be complete: size and timestamps can be unavailable, and display names are provider-supplied. Classify media using MIME type first and extension as a fallback. Keep document IDs opaque and use a source-aware stable key for database/cache identity.

### 4. Make scanning incremental and cancellable

Document providers can be remote, temporarily disconnected, or slow. Android's own guidance warns that iterating a large selected tree can reduce performance.

Do not recursively enumerate every document-provider tree synchronously during application startup. Scan on a background dispatcher, support cancellation, and publish results incrementally. Start with the selected root and enumerate child folders as they are opened; add deeper indexing only if the UX requires it. Avoid assuming every provider supports paging, sorting, or fast metadata queries.

Preserve existing media filters and visibility rules where their semantics apply, but define how SAF sources participate in hidden-file handling, `.nomedia`, include/exclude lists, favorites, sorting, grouping, search, duplicate detection, and stale-index cleanup.

### 5. Load media through provider streams

Image/video display, thumbnails, video playback, dimensions, and EXIF access must accept content URIs. Use Glide/other loaders with URI models where supported, and use `ContentResolver.openInputStream()` or `openFileDescriptor()` for operations that need a stream or descriptor. Keep provider I/O off the UI thread and do not assume random filesystem access is available.

The cache key must identify the source document, not just its display name. Invalidate or update the relevant cache entry after a provider-backed edit or mutation.

### 6. Gate mutations on provider capabilities

Read-only browsing should be the first deliverable. Add mutations only after the source abstraction is in place.

Query `Document.COLUMN_FLAGS` and expose only supported actions. For example, `FLAG_SUPPORTS_WRITE`, `FLAG_SUPPORTS_DELETE`, and `FLAG_DIR_SUPPORTS_CREATE` describe different capabilities. Use `ContentResolver` streams and `DocumentsContract` operations rather than `FileOutputStream`, `File.renameTo()`, or other filesystem-only calls for provider items.

Treat these as distinct operations:

- **Save as:** create a document in a destination directory when the provider supports directory creation.
- **Overwrite:** write to an existing document only when it advertises write support and the provider accepts the requested mode.
- **Delete / rename / create folder:** show these actions only when their corresponding provider capabilities are available.
- **Move / copy:** use provider-supported operations when available; otherwise implement an explicit stream-copy fallback only when both sides support the required reads/writes. Delete the source after a copy only when delete is supported and the user confirms the operation.

A provider can be read-only or support only a subset of these operations. Do not promise that every SAF source behaves like a writable local folder.

### 7. Extend persistence deliberately

The current Room schema and app preferences assume path strings. Add a source type and stable source/document identity through a deliberate Room migration, or create a small provider-source table linked to media rows. Keep local-path compatibility intact.

Do not overload path strings with fake paths such as `/content:/...`. The #966 report is a concrete reminder that doing so can break writes and cache refresh. Re-test the current content-URI editor path as part of this work, even though it already has URI-specific save code.

## Suggested implementation order

1. **Source and permission foundation:** model a selected tree, request/persist/release exact grants, list a selected root, and handle revoked or unavailable grants.
2. **Read-only provider browsing:** enumerate media and subfolders on demand, persist source-aware identities, and show provider-backed albums without changing the MediaStore path.
3. **Viewing pipeline:** thumbnails, image viewing, video playback, metadata and cache invalidation for document URIs.
4. **Gallery features:** favorites, search, filters, sorting/grouping, hidden/excluded folders, duplicate/stale-index behavior for provider sources.
5. **Capability-aware writes:** save-as, overwrite, create, rename, copy/move and delete, each gated by provider support.
6. **UX and hardening:** provider management/removal, permission recovery, offline/error states, progress/cancellation, and large-tree performance.

## Validation matrix

Test at least:

- Existing internal-storage albums and MediaStore results remain unchanged.
- Selected removable/USB document tree.
- SMB/CIFS provider, WebDAV provider, rclone/RSAF provider, and a cloud provider.
- Read-only provider and providers with partial write/delete/create support.
- Provider disconnected, signed out, document moved/deleted, or grant revoked.
- Large/deep trees, empty folders, unknown file sizes/timestamps, unusual MIME types, hidden files, and duplicate media.
- Image and video thumbnails, full-resolution viewing, playback, EXIF/dimensions, editing, save-as, overwrite and cache refresh.
- Android 11+ tree-picker restrictions, including internal/SD roots, Downloads, `Android/data`, and `Android/obb`.

## References

### Android platform documentation

- [Access documents and other files from shared storage](https://developer.android.com/training/data-storage/shared/documents-files) — picker intents, exact tree scope, persistable URI grants, provider metadata, and Android 11+ restrictions.
- [Open files using the Storage Access Framework](https://developer.android.com/guide/topics/providers/document-provider) — document-provider model and client workflow.
- [DocumentsContract API reference](https://developer.android.com/reference/android/provider/DocumentsContract) — building tree/document/child URIs and provider operations.

### Upstream Fossify references

- [Fossify Gallery #201](https://github.com/FossifyOrg/Gallery/issues/201)
- [Fossify Gallery #1135](https://github.com/FossifyOrg/Gallery/issues/1135)
- [Fossify Gallery #966](https://github.com/FossifyOrg/Gallery/issues/966)
- [Fossify Gallery #450](https://github.com/FossifyOrg/Gallery/issues/450)
- [Fossify General-Discussion #131](https://github.com/FossifyOrg/General-Discussion/issues/131)
- [Fossify General-Discussion #211](https://github.com/FossifyOrg/General-Discussion/issues/211)
- [Fossify General-Discussion #698](https://github.com/FossifyOrg/General-Discussion/issues/698)
- [Fossify Voice Recorder #315](https://github.com/FossifyOrg/Voice-Recorder/issues/315)
