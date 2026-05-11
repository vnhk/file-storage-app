# file-storage-app

Web-based file manager with dual storage: files on disk + metadata in a database. Supports upload, download, folder organization, copy/move, and inline text editing.

## Features

- **Dual storage**: Every file stored on disk; metadata (path, size, description) in DB
- **Path routing**: Configurable automapping routes paths to different storage locations (e.g., `/Movies/*` → `/mnt/movies`)
- **File viewers**: PDF, image, video, and text files (with inline editing for files ≤ 2 MB)
- **Operations**: Upload single/folder, ZIP extraction, new folder, rename, copy, move, delete, download as ZIP
- **Sync**: Bidirectional disk↔DB synchronization (batch of 100 items)
- **Async ops**: Background processing with notifications for long-running operations

## Key Entity

`Metadata` — filename, path, is_directory, extension, size, description, owner

## REST API

| Endpoint | Description |
|----------|-------------|
| `GET /file-storage-app/files/download?uuid={uuid}` | Download file (supports byte ranges) |

## Configuration

```properties
file.service.storage.folders=main,backup
file.service.folder.automapping=documents:/Documents/*;/Files/Documents/*
file.service.storage.folders.mapping=main:./storage/main,backup:./storage/backup
global-tmp-dir.file-storage-relative-path=tmp
```

## Supported Text Formats (inline edit)

`.json`, `.txt`, `.vtt`, `.srt`, `.html`, `.css`, `.js`, `.xml`, `.csv`

## Build

```bash
mvn clean install -DskipTests
```

Part of the `my-tools` multi-module Maven project. Requires `common` to be built first.
