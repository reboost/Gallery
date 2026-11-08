# TODO

This file tracks planned, fork-specific work. Keep entries here until the corresponding behavior is implemented and verified.

## Android Storage Access Framework (SAF)

**Status:** Foundation in progress. The exact tree-grant registry and additive storage-selection entry point are implemented; provider media browsing and operations remain planned. See [SAF support investigation](docs/SAF_SUPPORT.md) for architecture findings and implementation constraints.

### Change constraints

- Keep SAF additive and isolated. Put provider-specific selection, permission, enumeration, and capability code in a small SAF-specific package.
- Leave the existing MediaStore scanner, local filesystem scanner, local path semantics, and current OTG implementation intact.
- Integrate SAF at the smallest necessary boundaries; do not refactor or rename unrelated upstream classes, utilities, models, or database structures.
- Keep filesystem paths and document URIs as distinct identities. Never encode a content URI as a fake filesystem path.
- Prefer a separate, additive SAF persistence layer. Change existing media models or Room entities only where the feature demonstrably requires it, using a focused migration.
- Reuse the existing AndroidX DocumentFile dependency and platform APIs unless implementation proves another dependency is necessary.

### Agreed entry point and user flow

- [x] Add a Gallery-owned storage selector at **Create new folder** with **Other locations…**; keep the existing Internal/SD/USB path flow delegated to Commons unchanged.
- [x] Launch the Android system folder picker from **Other locations…** using ACTION_OPEN_DOCUMENT_TREE. Persist the exact returned tree URI and only its granted read/write modes.
- [x] Show saved roots in **Previously added locations**, identifying revoked/unavailable grants and read-only roots without using display names as IDs.
- [x] Persist each selected tree as a Gallery source record separately from the local destination path; selecting it does not copy files or put a content URI in a filesystem path.
- [ ] Connect registered SAF sources to Gallery media discovery and album navigation.
- [ ] Allow read-only roots to be browsed. Enable folder creation or other mutations only when the selected document advertises the required capability.

### Implementation work

- [x] Add a small SAF source model/store for the selected tree URI, provider display label, and granted modes, with exact-grant removal and UI recovery by re-adding a source after revocation.
- [ ] Add provider-backed folder and media enumeration using DocumentsContract metadata, performed in cancellable background work and loaded on demand.
- [ ] Expose SAF media through a narrow adapter at the existing Gallery boundary without replacing or rewriting the local MediaStore/filesystem pipeline.
- [ ] Load image/video content, thumbnails, and EXIF metadata through content URIs, with source-aware cache identity and invalidation.
- [ ] Add capability-aware create, write, rename, delete, and copy/move behavior only where supported by the provider; keep unsupported actions unavailable.
- [ ] Verify local media behavior remains unchanged and exercise removable/USB, SMB/CIFS, WebDAV, rclone, cloud, read-only, revoked/offline, and large-tree scenarios.
- [ ] Document Android 11+ picker restrictions, including internal/SD storage roots, Downloads, Android/data, and Android/obb.

Do not broaden the work into a general storage rewrite. Keep each integration change small enough to review and rebase cleanly against upstream Fossify updates.
