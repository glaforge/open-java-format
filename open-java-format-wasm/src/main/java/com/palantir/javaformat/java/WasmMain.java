/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.javaformat.java;

import com.palantir.javaformat.java.JavaFormatterOptions.Style;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSBoolean;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;

/**
 * The formatter for a web page. Once the module has started, {@code globalThis.openJavaFormat.format(source)} formats
 * a Java file exactly as {@code open-java-format -} does with no flags, and {@code globalThis.openJavaFormat.version}
 * is the release it was built from.
 *
 * <p>In the formatter's package for {@link FormatFileCallable}, which is what {@link Main} runs on each file and is
 * package-private. Main itself is not called: it reads standard input and writes to streams, where the page has a
 * string to hand over and a result to take back.
 */
public final class WasmMain {
    /** The command line's options for {@code open-java-format -}: the defaults, on standard input. */
    private static final CommandLineOptions STDIN = CommandLineOptionsParser.parse(List.of("-"));

    private static final JavaFormatterOptions OPTIONS =
            JavaFormatterOptions.builder().style(Style.OJF).build();

    private WasmMain() {}

    // A Java Function handed to @JS arrives in JavaScript as a function that takes and returns JavaScript values.
    @JS(
            args = {"format", "version"},
            value = "globalThis.openJavaFormat = {version: version, format: format};")
    private static native void publish(Function<JSString, JSObject> format, JSString version);

    public static void main(String[] args) {
        publish(WasmMain::format, JSString.of(BuildVersion.VALUE));
    }

    /**
     * The release, which the image build passes as {@code -Dopen-java-format.version}. In the module
     * {@code Package.getImplementationVersion()}, where the command line reads its version, is null. This class is
     * initialized at build time, so the value is the property's at build time.
     */
    static final class BuildVersion {
        static final String VALUE = Objects.requireNonNull(System.getProperty("open-java-format.version"));

        private BuildVersion() {}
    }

    private static JSObject format(JSString source) {
        var result = JSObject.create();
        try {
            var output = new FormatFileCallable(STDIN, source.asString(), OPTIONS).call();
            result.set("ok", JSBoolean.of(true));
            result.set("output", JSString.of(output));
        } catch (FormatterException e) {
            // The lines the command line prints for standard input, without its "<stdin>:" in front.
            var diagnostics =
                    e.diagnostics().stream().map(FormatterDiagnostic::toString).collect(Collectors.joining("\n"));
            result.set("ok", JSBoolean.of(false));
            result.set("error", JSString.of(diagnostics));
        } catch (RuntimeException e) {
            // A formatter bug rather than bad input: the page shows the exception, the console gets the stack trace.
            e.printStackTrace();
            result.set("ok", JSBoolean.of(false));
            result.set("error", JSString.of(e.toString()));
        }
        return result;
    }
}
