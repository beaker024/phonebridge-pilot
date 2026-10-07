import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Syntax check only. Does not replace compilation against the Android SDK. */
public class ParseJava {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<String> files = new ArrayList<>();
            try (var paths = Files.walk(Path.of("android"))) {
                paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> files.add(p.toString()));
            }
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("-proc:none"), null, manager.getJavaFileObjectsFromStrings(files));
            task.parse();
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) throw new AssertionError(diagnostic);
            }
            System.out.println("Java syntax parsed: " + files.size() + " Android source files (SDK type-check still required)");
        }
    }
}
