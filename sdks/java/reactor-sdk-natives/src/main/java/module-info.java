/**
 * Every platform's {@code libreactor_ffi}, and nothing else.
 *
 * <p>A real descriptor rather than an {@code Automatic-Module-Name}, because {@code inc.reactor.sdk}
 * has to {@code requires} this module for a consumer on the module path to resolve it at all — and
 * requiring an automatic module is a name derived from a file name, which javac warns about for
 * good reason.
 *
 * <p>It exports nothing. The libraries live under {@code reactor-native/}, which is not a valid
 * package name and therefore not encapsulated: the core reads them through the class loader, which
 * is the only reason splitting them into their own artifact works at all.
 */
module inc.reactor.sdk.natives {}
