# TODO

This file tracks planned, fork-specific work. Keep entries here until the corresponding behavior is implemented and verified.

## Android Storage Access Framework (SAF)

**Status:** Planned. See [SAF support investigation](docs/SAF_SUPPORT.md) for upstream research, current architecture findings, and the proposed design.

- [ ] Introduce a source-aware media identity so filesystem paths and SAF document URIs remain distinct; do not turn a `content://` URI into a `java.io.File` path.
- [ ] Add a system folder-selection flow using `ACTION_OPEN_DOCUMENT_TREE`; persist only the read/write URI grants returned by the picker and retain the exact user-selected tree.
- [ ] Store and restore selected provider roots, including permission revocation, moved/deleted roots, offline providers, and user-initiated removal.
- [ ] Add cancellable, background SAF directory and media enumeration using document-provider metadata; keep the existing MediaStore/local-filesystem path intact.
- [ ] Integrate provider-backed folders into album navigation, filtering, sorting, favorites, search, and persisted media metadata without assuming filesystem paths or that size/date metadata is always available.
- [ ] Load images, video, thumbnails, and EXIF metadata from document URIs through provider streams or file descriptors.
- [ ] Add capability-aware provider operations. Gate overwrite, create, rename, delete, and copy/move on the selected document's advertised flags; handle read-only and partially supported providers explicitly.
- [ ] Refresh the media index and image/thumbnail caches after provider-backed edits and mutations.
- [ ] Verify performance and correctness with local storage, removable storage, USB, SMB/CIFS, WebDAV, rclone, cloud providers, read-only providers, revoked grants, disconnected/offline providers, and large directory trees.
- [ ] Document Android 11+ picker restrictions (including storage roots, Downloads, Android/data, and Android/obb) so these are not misreported as general SAF failures.
