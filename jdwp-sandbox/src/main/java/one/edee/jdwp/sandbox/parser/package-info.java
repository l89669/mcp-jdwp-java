/**
 * Flight #8: The Magic Patch — confirm a hypothesis by mutating live state instead of rebuilding.
 *
 * <p>{@code DateParser.parse} splits {@code YYYY-MM-DD} and {@code Integer.parseInt}s each part. The
 * feed delivers {@code "2026-05-15 "} with a trailing space, so {@code parseInt("15 ")} throws
 * {@link NumberFormatException}. The value looks almost right, and the fix (trim the input) is
 * obvious — but the point of the flight is to <em>prove</em> it at runtime without a rebuild.
 *
 * <p>Break at the top of {@code parse}, see {@code input = "2026-05-15 "}, then
 * {@code jdwp_set_local("input", "2026-05-15")} and resume. The parse now succeeds and the test
 * passes — confirming the trailing space is the whole story. Patching the local in place turns a
 * rebuild-and-rerun loop into a single resume.
 */
package one.edee.jdwp.sandbox.parser;
