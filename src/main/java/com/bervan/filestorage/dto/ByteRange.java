package com.bervan.filestorage.dto;

/**
 * Encapsulates byte-range information for HTTP 206 Partial Content range requests.
 */
public record ByteRange(long start, long end, long contentLength) {

    /**
     * Parses an HTTP Range header (e.g., "bytes=0-1024" or "bytes=1024-") given total file size.
     *
     * @param rangeHeader the raw Range header string
     * @param fileSize    total size of the file in bytes
     * @return parsed ByteRange
     */
    public static ByteRange parse(String rangeHeader, long fileSize) {
        String[] ranges = rangeHeader.replace("bytes=", "").split("-");
        long start = Long.parseLong(ranges[0]);
        long end = (ranges.length > 1 && !ranges[1].isEmpty())
                ? Long.parseLong(ranges[1])
                : fileSize - 1;
        long contentLength = end - start + 1;
        return new ByteRange(start, end, contentLength);
    }

    /**
     * Formats the Content-Range header value (e.g., "bytes 0-1024/5000").
     *
     * @param totalFileSize total size of the file in bytes
     * @return header value for Content-Range
     */
    public String toContentRangeHeader(long totalFileSize) {
        return "bytes " + start + "-" + end + "/" + totalFileSize;
    }
}
