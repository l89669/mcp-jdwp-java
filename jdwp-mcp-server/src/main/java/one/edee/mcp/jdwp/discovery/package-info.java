/**
 * Local-JVM discovery for the {@code jdwp_diagnose} tool. Combines the {@code jdk.attach} API
 * with Linux-specific {@code /proc} parsing to enumerate JVMs visible to the current user, then
 * optionally confirms a JDWP handshake to distinguish "configured for JDWP" from "actually
 * accepting attaches".
 */
@NullMarked
package one.edee.mcp.jdwp.discovery;

import org.jspecify.annotations.NullMarked;
