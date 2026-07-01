package one.edee.mcp.jdwp.evaluation;

import com.sun.jdi.ClassLoaderReference;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import one.edee.mcp.jdwp.evaluation.exceptions.JdiEvaluationException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Executes caller-provided, already-compiled bytecode in the target VM.
 *
 * <p>This deliberately bypasses the expression compiler. The caller is responsible for compiling
 * the class against the target application's real toolchain/classpath (for example a Minecraft
 * Gradle build that also reobfuscates names). This service only reads the resulting {@code .class}
 * bytes, defines them into the suspended frame's classloader, and invokes a static no-arg method.
 */
@Service
public class CompiledClassExecutor {

    /**
     * Loads {@code classFile} into the classloader associated with {@code frame} and invokes a
     * static no-argument method.
     */
    public @Nullable Value executeClassFile(
        VirtualMachine vm,
        ThreadReference thread,
        StackFrame frame,
        Path classFile,
        String className,
        String methodName
    ) throws IOException, JdiEvaluationException {
        final ClassLoaderReference classLoader = frame.location().declaringType().classLoader();
        if (classLoader == null) {
            throw new JdiEvaluationException(
                "Cannot define a class from a bootstrap-loaded frame. Pick a suspended application "
                    + "frame/thread whose declaring class has a non-null ClassLoader."
            );
        }

        return RemoteCodeExecutor.execute(
            vm,
            thread,
            classLoader,
            className,
            Files.readAllBytes(classFile),
            methodName,
            List.of()
        );
    }
}
