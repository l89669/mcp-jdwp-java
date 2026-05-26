package one.edee.mcp.jdwp.evaluation;

import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link InMemoryJavaCompiler#configure(String, String, int)} — specifically the
 * version clamping logic and the empty-classpath path that logs a warning but still works.
 */
class InMemoryJavaCompilerConfigureTest {

	private InMemoryJavaCompiler compiler;

	@BeforeEach
	void setUp() {
		compiler = new InMemoryJavaCompiler();
	}

	@Nested
	@DisplayName("Version clamping")
	class VersionClamping {

		@Test
		@DisplayName("Negative version is clamped to 8")
		void shouldClampNegativeVersionToEight() throws Exception {
			String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", -1);

			assertThat(readTargetMajorVersion()).isEqualTo(8);
		}

		@Test
		@DisplayName("Zero version is clamped to 8")
		void shouldClampZeroVersionToEight() throws Exception {
			String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", 0);

			assertThat(readTargetMajorVersion()).isEqualTo(8);
		}

		@Test
		@DisplayName("Version 17 is kept as-is")
		void shouldKeepValidVersionAsIs() throws Exception {
			String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", 17);

			assertThat(readTargetMajorVersion()).isEqualTo(17);
		}

		private int readTargetMajorVersion() throws Exception {
			Field field = InMemoryJavaCompiler.class.getDeclaredField("targetMajorVersion");
			field.setAccessible(true);
			return (int) field.get(compiler);
		}
	}

	@Nested
	@DisplayName("Empty classpath")
	class EmptyClasspath {

		@Test
		@DisplayName("Empty classpath does not prevent compilation")
		void shouldStillCompileWithEmptyClasspath() {
			String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", 17);

			// A simple class with no external dependencies should compile fine
			String source = "public class EmptyClasspathTest { public static String run() { return \"ok\"; } }";

			assertThatCode(() -> {
				Map<String, byte[]> result = compiler.compile("EmptyClasspathTest", source);
				assertThat(result).containsKey("EmptyClasspathTest");
			}).doesNotThrowAnyException();
		}
	}

	@Nested
	@DisplayName("Reset")
	class Reset {

		@Test
		@DisplayName("After reset, compile throws the not-configured error instead of using stale config")
		void shouldThrowNotConfiguredAfterReset() {
			final String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "", 17);

			// Sanity: configured compiler compiles fine.
			final String source = "public class ResetProbe { public static String run() { return \"ok\"; } }";
			assertThatCode(() -> compiler.compile("ResetProbe", source)).doesNotThrowAnyException();

			compiler.reset();

			// After reset the compiler must behave as never-configured: compile() refuses rather
			// than silently reusing the previous connection's JDK/classpath.
			assertThatThrownBy(() -> compiler.compile("ResetProbe", source))
				.isInstanceOf(JdiEvaluationException.class)
				.hasMessageContaining("not configured");
		}

		@Test
		@DisplayName("Reset clears jdkPath, classpath, and restores the default target version")
		void shouldClearConfiguredState() throws Exception {
			final String jdkHome = System.getProperty("java.home");
			compiler.configure(jdkHome, "/some/app.jar", 21);

			compiler.reset();

			assertThat(readField("jdkPath")).isNull();
			assertThat(readField("classpath")).isNull();
			assertThat(readTargetMajorVersion()).isEqualTo(8);
		}

		private Object readField(String name) throws Exception {
			final Field field = InMemoryJavaCompiler.class.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(compiler);
		}

		private int readTargetMajorVersion() throws Exception {
			final Field field = InMemoryJavaCompiler.class.getDeclaredField("targetMajorVersion");
			field.setAccessible(true);
			return (int) field.get(compiler);
		}
	}
}
