/*
 * Copyright 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.palantir.javaformat.java;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.io.ByteStreams;
import com.palantir.javaformat.java.JavaFormatterOptions.Style;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** The main class for the Java formatter CLI. */
public final class Main {
    private static final int MAX_THREADS = 20;
    private static final String STDIN_FILENAME = "<stdin>";

    /** A file is not formatted, and {@code --set-exit-if-changed} was given. */
    private static final int EXIT_CHANGED = 1;

    /**
     * A file could not be read, parsed or written. This is not {@link #EXIT_CHANGED}, so that a caller can tell "not
     * formatted" from "could not format". It is also the exit code that makes an agent hook hand stderr to the model.
     */
    private static final int EXIT_ERROR = 2;

    static String versionString() {
        return "open-java-format: Version " + Main.class.getPackage().getImplementationVersion();
    }

    private final PrintWriter outWriter;
    private final PrintWriter errWriter;
    private final InputStream inStream;

    public Main(PrintWriter outWriter, PrintWriter errWriter, InputStream inStream) {
        this.outWriter = outWriter;
        this.errWriter = errWriter;
        this.inStream = inStream;
    }

    /**
     * The main method for the formatter, with some number of file names to format. We process them in parallel, but we
     * must be careful; if multiple file names refer to the same file (which is hard to determine), we must serialize
     * their updates.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        int result;
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out, UTF_8));
        PrintWriter err = new PrintWriter(new OutputStreamWriter(System.err, UTF_8));
        try {
            Main formatter = new Main(out, err, System.in);
            result = formatter.format(args);
        } catch (UsageException e) {
            err.print(e.getMessage());
            // A removed or mistyped flag must not pass for a check that found nothing.
            result = e.isError() ? EXIT_ERROR : 0;
        } finally {
            err.flush();
            out.flush();
        }
        System.exit(result);
    }

    /**
     * The main entry point for the formatter, with some number of file names to format. We process them in parallel,
     * but we must be careful; if multiple file names refer to the same file (which is hard to determine), we must
     * serialize their update.
     *
     * @param args the command-line arguments
     */
    public int format(String... args) throws UsageException {
        CommandLineOptions parameters = processArgs(args);
        if (parameters.version()) {
            errWriter.println(versionString());
            return 0;
        }
        if (parameters.help()) {
            throw new UsageException();
        }

        for (String flag : parameters.unsupportedFlags()) {
            errWriter.println("warning: flag \"" + flag + "\" is not supported, the style is always open-java-format;"
                    + " remove it from the command line to get rid of this message");
        }
        JavaFormatterOptions options =
                JavaFormatterOptions.builder().style(Style.OJF).build();

        if (parameters.stdin()) {
            return formatStdin(parameters, options);
        } else {
            return formatFiles(parameters, options);
        }
    }

    private int formatFiles(CommandLineOptions parameters, JavaFormatterOptions options) {
        int numThreads = Math.min(MAX_THREADS, parameters.files().size());
        // Closing the pool ends its threads, so that a tool that runs Main in-process does not keep them. The close
        // waits for the submitted tasks, which formatFiles has already waited for.
        try (ExecutorService executorService = Executors.newFixedThreadPool(numThreads)) {
            return formatFiles(parameters, options, executorService);
        }
    }

    private int formatFiles(
            CommandLineOptions parameters, JavaFormatterOptions options, ExecutorService executorService) {
        Map<Path, String> inputs = new LinkedHashMap<>();
        Map<Path, Future<String>> results = new LinkedHashMap<>();
        boolean allOk = true;
        boolean anyChanged = false;

        for (String fileName : parameters.files()) {
            if (!fileName.endsWith(".java")) {
                errWriter.println("Skipping non-Java file: " + fileName);
                continue;
            }
            Path path = Paths.get(fileName);
            String input;
            try {
                input = new String(Files.readAllBytes(path), UTF_8);
                inputs.put(path, input);
                results.put(path, executorService.submit(new FormatFileCallable(parameters, input, options)));
            } catch (IOException e) {
                errWriter.println(fileName + ": could not read file: " + e.getMessage());
                allOk = false;
            }
        }

        for (Map.Entry<Path, Future<String>> result : results.entrySet()) {
            Path path = result.getKey();
            String formatted;
            try {
                formatted = result.getValue().get();
            } catch (InterruptedException e) {
                errWriter.println(e.getMessage());
                allOk = false;
                continue;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof FormatterException) {
                    for (FormatterDiagnostic diagnostic : ((FormatterException) e.getCause()).diagnostics()) {
                        errWriter.println(path + ":" + diagnostic);
                    }
                } else {
                    errWriter.println(path + ": error: "
                            + Optional.ofNullable(e.getCause())
                                    .map(Throwable::getMessage)
                                    .orElse("null"));
                    Optional.ofNullable(e.getCause()).ifPresent(cause -> cause.printStackTrace(errWriter));
                }
                allOk = false;
                continue;
            }
            boolean changed = !formatted.equals(inputs.get(path));
            if (changed && parameters.setExitIfChanged()) {
                anyChanged = true;
            }
            if (parameters.inPlace()) {
                if (!changed) {
                    continue; // preserve original file
                }
                try {
                    Files.write(path, formatted.getBytes(UTF_8));
                } catch (IOException e) {
                    errWriter.println(path + ": could not write file: " + e.getMessage());
                    allOk = false;
                    continue;
                }
            } else if (parameters.dryRun()) {
                if (changed) {
                    outWriter.println(path);
                }
            } else {
                outWriter.write(formatted);
            }
        }
        return exitCode(allOk, anyChanged);
    }

    private int formatStdin(CommandLineOptions parameters, JavaFormatterOptions options) {
        String input;
        try {
            input = new String(ByteStreams.toByteArray(inStream), UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String stdinFilename = parameters.assumeFilename().orElse(STDIN_FILENAME);
        boolean ok = true;
        boolean anyChanged = false;
        try {
            String output = new FormatFileCallable(parameters, input, options).call();
            boolean changed = !input.equals(output);
            if (changed && parameters.setExitIfChanged()) {
                anyChanged = true;
            }
            if (parameters.dryRun()) {
                if (changed) {
                    outWriter.println(stdinFilename);
                }
            } else {
                outWriter.write(output);
            }
        } catch (FormatterException e) {
            for (FormatterDiagnostic diagnostic : e.diagnostics()) {
                errWriter.println(stdinFilename + ":" + diagnostic);
            }
            ok = false;
            // TODO(cpovirk): Catch other types of exception (as we do in the formatFiles case).
        }
        return exitCode(ok, anyChanged);
    }

    /** An error wins over {@code --set-exit-if-changed}: the caller has to hear about the file that did not parse. */
    private static int exitCode(boolean allOk, boolean anyChanged) {
        if (!allOk) {
            return EXIT_ERROR;
        }
        return anyChanged ? EXIT_CHANGED : 0;
    }

    /** Parses and validates command-line flags. */
    public static CommandLineOptions processArgs(String... args) throws UsageException {
        CommandLineOptions parameters;
        try {
            parameters = CommandLineOptionsParser.parse(Arrays.asList(args));
        } catch (IllegalArgumentException e) {
            throw new UsageException(Objects.requireNonNullElse(e.getMessage(), "null"));
        } catch (Throwable t) {
            t.printStackTrace();
            throw new UsageException(Objects.requireNonNullElse(t.getMessage(), "null"));
        }
        int filesToFormat = parameters.files().size();
        if (parameters.stdin()) {
            filesToFormat++;
        }

        if (parameters.inPlace() && parameters.files().isEmpty()) {
            throw UsageException.nothingToDo("in-place formatting was requested but no files were provided");
        }
        if (parameters.isSelection() && filesToFormat != 1) {
            throw new UsageException("partial formatting is only support for a single file");
        }
        if (parameters.offsets().size() != parameters.lengths().size()) {
            throw new UsageException("-offsets and -lengths flags must be provided in matching pairs");
        }
        if (filesToFormat <= 0 && !parameters.version() && !parameters.help()) {
            throw UsageException.nothingToDo("no files were provided");
        }
        if (parameters.stdin() && !parameters.files().isEmpty()) {
            throw new UsageException("cannot format from standard input and files simultaneously");
        }
        if (parameters.assumeFilename().isPresent() && !parameters.stdin()) {
            throw new UsageException("--assume-filename is only supported when formatting standard input");
        }
        if (parameters.dryRun() && parameters.inPlace()) {
            throw new UsageException("cannot use --dry-run and --replace at the same time");
        }
        return parameters;
    }
}
