package inc.reactor.sdk.android

/**
 * A file the platform has accepted, ready to be named in a command.
 *
 * Returned by the upload calls and passed straight back as a command argument — a model that
 * takes a file takes one of these, not a path, because the path on the device means nothing on
 * the far side.
 */
public data class FileRef(
    public val uploadId: String,
    public val name: String,
    public val mimeType: String,
    public val size: Long,
)
