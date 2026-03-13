# File Storage App - Project Notes

> **IMPORTANT**: Keep this file updated when making significant changes to the codebase. This file serves as persistent memory between Claude Code sessions.

## Overview
Web-based file management system with dual storage: files on disk + metadata in database. Supports upload, download, folder organization, copy/move, and file editing. Built with Spring Boot + Vaadin.

## Key Architecture

### Entities

#### Metadata
- `id: UUID`, `filename: String`, `path: String`, `isDirectory: boolean`
- `extension: String`, `createDate`, `modificationDate`, `userName: String`
- `description: String` (max 2000), `fileSize: Long`
- Unique constraint on (path, filename)
- Extends `BervanOwnedBaseEntity`

### Services

#### FileServiceManager (Primary Orchestrator)
- `save(MultipartFile, description, path)` - upload single file
- `saveAndExtractZip(...)` - upload + extract ZIP
- `renameFile(Metadata, newName)`, `updateFileContent(Metadata, newContent)`
- `copyFileToPath(Metadata, destPath)`, `moveFileToPath(Metadata, destPath)`
- `delete(Metadata)` - cascades for directories
- `loadByPath(String)`, `getDirectoriesInPath(String)`
- `readFile(Metadata)` - read file bytes

#### FileDiskStorageService (Disk I/O)
- Config: `file.service.storage.folders`, `file.service.folder.automapping`, `file.service.storage.folders.mapping`
- Auto-mapping routes paths to different storage folders (e.g., `/Movies/*` → `/mnt/movies`)
- Handles filename conflicts by appending `(1)`, `(2)`, etc.

#### FileDBStorageService (Database)
- Store/update/delete metadata, batch operations

#### LoadStorageAndIntegrateWithDB (Sync)
- `synchronizeStorageWithDB()` - bidirectional sync between disk and DB
- Batch processing (100 items/batch)

### REST API
- `GET /file-storage-app/files/download?uuid={uuid}` - download file, supports byte ranges

### Views

#### AbstractFileStorageView
- Route: `/file-storage-app/files` (+ `?path=...`)
- Grid: icon+filename, size, date; hierarchical navigation with `../`
- Floating toolbar: Upload (single/folder), New Folder, Sync, Download ZIP, Copy, Move, Delete, Rename, Edit Description, Edit Text Files, View Details

#### File Viewers (pluggable `FileViewer` interface)
- `PDFViewer`, `PictureViewer`, `VideoViewer`
- `TextViewer`: .json, .txt, .vtt, .srt, .html, .css, .js, .xml, .csv — inline editing for files ≤ 2MB

#### UploadComponent
- Tabbed: single file vs. folder upload; optional ZIP extraction
- Async processing with progress tracking

#### FolderPickerDialog
- Browse directory tree for copy/move target selection

## Configuration
```properties
file.service.storage.folders=main,backup
file.service.folder.automapping=documents:/Documents/*;/Files/Documents/*
file.service.storage.folders.mapping=main:./storage/main,backup:./storage/backup
global-tmp-dir.file-storage-relative-path=tmp
```

## Important Notes
1. Dual storage: every file stored on disk AND metadata in DB
2. Path-based routing determines storage location via configurable patterns
3. Async operations (sync, delete, copy, move) run in background with notifications
4. Spring Security `@PostFilter` for row-level access control
5. Soft deletes via `deleted` flag
