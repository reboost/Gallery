# TODO

This file tracks planned, fork-specific work. Keep entries here until the corresponding behavior is implemented and verified.

## Repository workflow

- Never create, open, or use a pull request for this project at any stage. Work and commit directly on the designated feature branch.
- Verify changes using push-triggered GitHub Actions and direct branch/commit inspection. Do not introduce a PR-based handoff or validation requirement.
- Do not merge into `main` without explicit user authorization.

## Android Storage Access Framework (SAF)

**Status:** Read-only browsing in progress. Tree-grant persistence, saved-location management, on-demand provider browsing, thumbnails, and a separate URI-based viewer are implemented; advanced metadata and mutations remain planned. See [SAF support investigation](docs/SAF_SUPPORT.md) for architecture findings and implementation constraints.

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
- [x] Allow read-only roots to be browsed in a separate SAF browser. Folder creation and other mutations remain unavailable until capability-aware operations are implemented.

### Implementation work

- [x] Add a small SAF source model/store for the selected tree URI, provider display label, and granted modes, with exact-grant removal and UI recovery by re-adding a source after revocation.
- [x] Add provider-backed immediate-child enumeration using DocumentsContract metadata, cancellable provider queries, and on-demand folder navigation.
- [x] Expose SAF roots through a separate read-only browser and item adapter without replacing or rewriting the local MediaStore/filesystem pipeline.
- [x] Load SAF thumbnails and open images/videos through content URIs with URI/metadata-based thumbnail cache signatures.
- [ ] Add provider-backed dimensions and EXIF details, high-resolution/deep-zoom image viewing, and source-aware invalidation after future mutations.
- [ ] Add capability-aware create, write, rename, delete, and copy/move behavior only where supported by the provider; keep unsupported actions unavailable.
- [ ] Integrate SAF items with Gallery's main album/media UI, including search, sorting/grouping, favorites, filters, and album navigation.
- [ ] Add provider-aware sharing, selection, and editing only where URI-based APIs support them.
- [ ] Verify local media behavior remains unchanged and exercise removable/USB, SMB/CIFS, WebDAV, rclone, cloud, read-only, revoked/offline, and large-tree scenarios.
- [ ] Document Android 11+ picker restrictions, including internal/SD storage roots, Downloads, Android/data, and Android/obb.

Do not broaden the work into a general storage rewrite. Keep each integration change small enough to review and rebase cleanly against upstream Fossify updates.
