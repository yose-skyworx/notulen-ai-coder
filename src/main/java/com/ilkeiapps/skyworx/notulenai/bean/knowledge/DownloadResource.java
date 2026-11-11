package com.ilkeiapps.skyworx.notulenai.bean.knowledge;

/**
 * Metadata needed to stream a stored original document back to the client. The object key stays
 * server-side (used by the controller to open a MinIO stream) and is never serialized to the client.
 */
public record DownloadResource(String filename, String contentType, String objectKey) {
}
