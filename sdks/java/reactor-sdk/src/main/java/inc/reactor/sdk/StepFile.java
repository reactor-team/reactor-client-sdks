package inc.reactor.sdk;

/**
 * An extra file the model kept with a step, as {@link StepResult#files()} names it.
 *
 * <p>The result names the file; it does not carry it. Fetch its bytes over HTTP from the session's
 * step results — {@code GET /sessions/{id}/steps/{step}} answers with each of the step's files and
 * a URL to download it from, or 202 while they are not stored yet.
 *
 * @param name the file's name, one path segment
 * @param contentType its media type, guessed from the name
 * @param size its size in bytes
 */
public record StepFile(String name, String contentType, long size) {}
