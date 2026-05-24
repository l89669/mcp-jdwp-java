package one.edee.mcp.jdwp.evaluation.exceptions;

import one.edee.mcp.jdwp.evaluation.RemoteCodeExecutor;

import java.io.Serial;

/**
 * Raised exclusively by {@link RemoteCodeExecutor} when <em>defining</em> the wrapper class into the
 * target VM fails — i.e. before the user's expression has run. Distinct from the base
 * {@link JdiEvaluationException} (which also covers user-code failures from the wrapper's
 * {@code invokeMethod}) so callers can safely retry the define step in a different package without
 * risking double execution of a side-effecting expression: a define failure guarantees the user
 * code never executed.
 * <p>
 * The motivating case is the target-package wrapper feature: when {@code this} is non-public, the
 * wrapper is emitted into {@code this}'s own package so package-private members are reachable. If
 * {@code ClassLoader.defineClass} rejects that package at runtime (sealed package, module
 * encapsulation, a restrictive custom classloader), {@link one.edee.mcp.jdwp.evaluation.JdiExpressionEvaluator}
 * retries once in the default isolated package to preserve the pre-feature behaviour for
 * public-only expressions.
 */
public class JdiClassDefinitionException extends JdiEvaluationException {

    @Serial
    private static final long serialVersionUID = 7128658239011934401L;

    public JdiClassDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
