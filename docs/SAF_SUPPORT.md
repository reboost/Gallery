# Storage Access Framework (SAF) support

**Status:** Implementation in progress on `feature/saf-support-plan`. Persistent tree registration, on-demand browsing, provider thumbnails, a URI-based image/video viewer with tiled image zoom and available EXIF details, and provider-gated create/rename/delete are implemented. Gallery-wide integration, cache invalidation, and other mutations remain planned.

## Implementation progress

### Foundation increment

- Added a Gallery-owned storage selector to the existing **Create new folder** entry point. Internal, SD Card, and the existing USB/OTG path continue through the original Commons file picker.
- **Other locations…** launches `ACTION_OPEN_DOCUMENT_TREE`. Gallery persists only the exact returned tree URI and the read/write grant modes actually returned by Android; no prefix or ancestor grant is requested.
- Saved roots are stored separately from path-based Gallery preferences. Their provider display names are labels only; the URI string remains the identity.
- **Previously added locations** shows saved roots, distinguishes unavailable/revoked grants from read-only access, reuses a saved root without opening the system picker, and can remove a root while releasing only its exact persisted URI grant.
- Selecting a saved root opens a separate SAF browser. Read-only roots remain browsable; folder creation, rename, and delete are exposed only when the saved write grant and the current directory/document capability flags allow the requested operation. The browser queries only the current folder's immediate children and opens nested folders on demand; it does not recursively scan the tree at startup.
- The browser classifies provider MIME types first, falls back to known image/video extensions, and tolerates missing size, last-modified, and capability metadata.
- Provider thumbnails load through Glide using document URIs and cache signatures derived from URI, size, and last-modified metadata. A separate SAF viewer opens large images through Gallery's existing tiled decoder using the document URI and EXIF orientation, with a Glide fallback for unsupported formats; videos use Android's `VideoView`/`ContentResolver` path. Its Share action uses `ACTION_SEND`, the document content URI, `ClipData`, and a temporary read grant; it does not try to resolve a filesystem path.
- SAF entries remain `SafBrowserItem` values with opaque document IDs and document URIs. They are never converted into `Medium.path`, `Directory.path`, or Room rows; the existing path-oriented viewer is not used for SAF.
- Enumeration runs on a dedicated background executor and passes a `CancellationSignal` into provider queries. Navigating to another folder or destroying the activity cancels the active query.
- This is a separate SAF browsing surface, not yet an integration with Gallery's main album grid. Existing Gallery search, favorites, sorting/grouping, hidden/excluded-folder rules, and media selection actions do not yet apply to SAF items.
- The SAF image viewer uses Gallery's existing tiled decoder for high-resolution zoom, applies EXIF orientation, and presents available dimensions, capture date, camera make/model, file size, and modified time. It falls back to Glide for image formats the tiled decoder cannot open. The video viewer uses Android's `VideoView`; editing and Gallery-style viewer actions remain unimplemented.
- Each opened folder is queried as one provider result set. Enumeration is on demand and off the UI thread, but provider-specific paging/limits for very large flat folders remain future work.
- Viewer inspection confirmed that the existing Gallery flow is not a safe SAF adapter: `MediaActivity`/`MediaAdapter` and Room queries identify media by filesystem path, while the external-URI viewer path temporarily places a `content://` URI in `Medium.path` and attempts path resolution. The SAF integration must not reuse that shortcut; the browsing increment needs a distinct document identity and URI-based loading.
- Folder creation is offered only when the saved tree grant includes write access and the currently open provider directory advertises `Document.FLAG_DIR_SUPPORTS_CREATE`. The operation re-queries the capability before calling `DocumentsContract.createDocument()` and reports revoked, unsupported, and unavailable outcomes separately. Item rename/delete also re-query `COLUMN_FLAGS` and call `DocumentsContract.renameDocument()` / `deleteDocument()` only when `FLAG_SUPPORTS_RENAME` / `FLAG_SUPPORTS_DELETE` is advertised; delete requires confirmation.
- File writes, copy, and move remain unavailable. Multi-item selection and provider-backed editing are also not integrated. A provider's read-only or partial capability set must continue to hide unsupported actions.
- The original Commons file picker, folder creation dialog, MediaStore scanner, filesystem scanner, OTG scanner, media model, and Room schema remain unchanged.

The storage-selection dialog itself is provided by the pinned `org.fossify:commons:7.1.3` dependency and has no extension point for additional choices. Rather than fork or copy the upstream file picker, this increment adds the selector at Gallery's existing folder-creation entry point and delegates all local paths back to the unchanged Commons picker. This keeps SAF changes additive and avoids maintaining a fork of an upstream-owned dialog.

## Repository workflow

This project does not use pull requests. **Never create or open a PR at any point in this project**, including draft or validation-only PRs. Implement and commit directly to the designated feature branch, verify with push-triggered GitHub Actions and direct commit/branch inspection, and never merge into `main` without explicit authorization.

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

### Keep the implementation additive and easy to rebase

The fork should add SAF without turning this feature into a storage-layer rewrite. Upstream Fossify changes are likely to continue touching the existing media pipeline, so keep the overlap with those files as small as possible.

- Put tree selection, persisted grants, document-provider queries, provider metadata, and capability checks in a small SAF-specific package (for example, a package dedicated to SAF under the existing app source tree). Keep provider-specific logic out of generic helpers.
- Leave the current MediaStore scanner, local filesystem enumeration, path-based local models, and OTG implementation in place. Add SAF as a separate source and connect it through the narrowest required integration points.
- Prefer a small adapter at the existing Gallery boundary over changing every caller to understand document-provider APIs. Do not introduce a broad, generic storage framework or rewrite the existing path-based pipeline as a prerequisite.
- Keep existing local database identities and path preferences valid. Prefer separate additive persistence for SAF roots and provider-backed metadata. If a shared media model or Room schema must change, add only the minimum source identity needed and use one focused additive migration; never store a document URI in a field that promises to be a filesystem path.
- Reuse the existing AndroidX DocumentFile dependency and platform APIs such as ContentResolver and DocumentsContract. Add another dependency only if a concrete requirement cannot be met by what is already present.
- Avoid unrelated renames, formatting churn, utility extraction, and broad refactors. Touch existing upstream-owned files only for the small UI, discovery, and presentation hooks that are actually required.

### Agreed UI entry point and behavior

Use the storage-selection UI that already exists in the app:

**Create new folder → Select storage**

The dialog currently offers **Internal** and **SD Card**. Keep both options and their existing behavior unchanged, and add **Other locations…** in the same dialog.

1. **Other locations…** launches Android's system folder picker with ACTION_OPEN_DOCUMENT_TREE. The system picker is the place where users choose a local or remote document provider, such as a USB, SMB/CIFS, WebDAV, rclone, or cloud provider.
2. When the user selects a tree, persist the exact URI and only the grant modes returned by Android. Do not widen access to a parent or ancestor directory.
3. Add the selected root to a **Previously added locations** section in the storage selector. Selecting a saved entry reuses its persisted permission instead of asking the user to pick it again.
4. Register the selected tree as a Gallery source so its media can be discovered and browsed in the Gallery. Merely selecting a destination in the folder-creation flow must not copy files or be mistaken for adding media to the library; source registration must be explicit in the implementation.
5. Keep browsing separate from mutation capability. A read-only provider is still a valid Gallery source. In the **Create new folder** flow, allow creation only when the selected destination directory advertises directory-creation support; otherwise disable the action and explain that the provider is read-only or does not support that operation.

The intended selector shape is:

```text
Select storage
  Internal
  SD Card
  Other locations…

Previously added locations
  NAS / Remote storage
  USB storage
```

The labels above are examples. Use a provider-supplied display name when available and retain the opaque tree URI as the internal identity. A display label must never be used as a unique key.

### 1. Keep MediaStore and add SAF as a separate source

Do not replace the existing MediaStore scanner with SAF.

MediaStore is already the efficient, permission-aware source for the device's normal media library. It should continue to populate ordinary local albums without forcing users to select folders manually. SAF should add user-selected document trees, including providers that are not represented in MediaStore.

Keep the current local-folder workflow intact. The new **Other locations…** action is an additive entry point; it must not require removing or redesigning the existing local picker.

### 2. Select and persist the exact tree grant

Use `Intent.ACTION_OPEN_DOCUMENT_TREE` for album roots. `ACTION_OPEN_DOCUMENT` selects one document and is not sufficient for browsing a folder hierarchy.

On the picker result:

1. Read the returned tree URI and the read/write grant flags from the result intent.
2. Persist only the flags actually granted by the system with `ContentResolver.takePersistableUriPermission()`.
3. Store the exact selected tree URI and grant modes as a provider source, including a display label for the saved-location list.
4. Add the saved root to **Previously added locations** and make it available to Gallery's source discovery.
5. Restore saved roots on startup by checking persisted grants. Handle missing, revoked, moved, or temporarily unavailable roots and let users remove a source, releasing its grant.

Do not silently widen the grant to a parent or ancestor directory. The selected tree is the user's access boundary. Re-selecting a saved root must reuse its existing grant rather than launch the picker again.

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

1. **Storage selector and source registration:** add **Other locations…** to the existing Select storage dialog, persist the exact tree grant, and list previously added roots. Keep Internal and SD Card behavior unchanged.
2. **Read-only provider browsing:** add isolated SAF enumeration and source identity, load folders on demand, and expose provider-backed albums without changing the MediaStore/local scanner.
3. **Viewing pipeline:** thumbnails, image viewing, video playback, metadata, and cache invalidation for document URIs.
4. **Gallery features:** favorites, search, filters, sorting/grouping, hidden/excluded folders, duplicate/stale-index behavior for provider sources.
5. **Capability-aware writes:** create folders, save-as, overwrite, rename, copy/move, and delete, each gated by provider support.
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
