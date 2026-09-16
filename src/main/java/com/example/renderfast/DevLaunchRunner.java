package com.example.renderfast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Simple launcher that delegates to the Gradle runClient task.
 * Useful when an IDE attempts to run net.fabricmc.devlaunchinjector.Main directly and fails
 * with "Main module not specified". Run this class from your IDE instead (it will invoke
 * the project wrapper: gradlew[.bat] runClient).
 */
public final class DevLaunchRunner {
    static void main() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        Path cwd = Path.of(System.getProperty("user.dir"));
        
        // Search upwards for gradlew if not in CWD
        File gradlew = null;
        Path searchPath = cwd;
        while (searchPath != null) {
            File potential = searchPath.resolve(windows ? "gradlew.bat" : "gradlew").toFile();
            if (potential.exists()) {
                gradlew = potential;
                cwd = searchPath;
                break;
            }
            searchPath = searchPath.getParent();
        }

        if (gradlew == null) {
            System.err.println("CRITICAL: Gradle wrapper (gradlew) not found in " + cwd + " or any parent directory.");
            System.exit(2);
        }

        System.out.println("Launching RenderFast dev environment from: " + cwd);
        String[] cmd = windows ? new String[]{gradlew.getAbsolutePath(), "runClient"} : new String[]{"/bin/sh", "-c", "./gradlew runClient"};
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        
        Process p;
        try {
            p = pb.start();
        } catch (Exception e) {
            System.err.println("Failed to launch process: " + e.getMessage());
            System.exit(1);
            return;
        }

        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) System.out.println(line);
        }

        int code = p.waitFor();
        if (code != 0) System.err.println("Process exited with non-zero code: " + code);
        System.exit(code);
    }
}
