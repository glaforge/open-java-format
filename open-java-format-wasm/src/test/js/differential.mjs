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

// The module against the runnable jar of the same build, on the input of every test case: both must give the same
// bytes, or the same diagnostics. The jar formats all the inputs in one run with --replace, so the JVM starts once.
// Usage: node differential.mjs <open-java-format.js> <java> <open-java-format-all.jar> <test-cases dir> <work dir>

import { spawnSync } from "node:child_process";
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join, relative } from "node:path";
import { load } from "./load.mjs";

const [wrapperPath, java, jar, casesRoot, workDir] = process.argv.slice(2);

// As CaseFile.java reads a case: the input is what lies between the first two lines of six or more dashes.
const SEPARATOR = /-{6,}(?:\r\n|\r|\n|$)/g;

function inputOf(text) {
    const separators = [...text.matchAll(SEPARATOR)];
    return text.substring(separators[0].index + separators[0][0].length, separators[1].index);
}

function caseFiles(dir) {
    return readdirSync(dir, { recursive: true, withFileTypes: true })
        .filter(entry => entry.isFile() && entry.name.endsWith(".case"))
        .map(entry => join(entry.parentPath, entry.name))
        .sort();
}

// One .java file per case, named after it, so that the jar's diagnostics name the case too.
rmSync(workDir, { recursive: true, force: true });
const inputs = caseFiles(casesRoot).map(caseFile => {
    const name = relative(casesRoot, caseFile).replaceAll(/[\\/]/g, "__").replace(/\.case$/, ".java");
    const path = join(workDir, name);
    const input = inputOf(readFileSync(caseFile, "utf8"));
    mkdirSync(workDir, { recursive: true });
    writeFileSync(path, input);
    return { name, path, input };
});

const jvm = spawnSync(java, ["-jar", jar, "--replace", ...inputs.map(input => input.path)], {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
});
if (jvm.error) {
    throw jvm.error;
}
// What the jar could not format, by file: its diagnostics with the file name taken off, as the module reports them.
const jvmErrors = new Map();
for (const line of jvm.stderr.split("\n").filter(line => line.length > 0)) {
    const input = inputs.find(input => line.startsWith(`${input.path}:`));
    if (input === undefined) {
        throw new Error(`The jar printed a line that names no input: ${line}`);
    }
    const diagnostic = line.substring(input.path.length + 1);
    jvmErrors.set(input.name, [...(jvmErrors.get(input.name) ?? []), diagnostic]);
}

const openJavaFormat = await load(wrapperPath);
const differences = [];
let failedOnBoth = 0;
const started = performance.now();
for (const input of inputs) {
    const result = openJavaFormat.format(input.input);
    const jvmError = jvmErrors.get(input.name);
    if (jvmError !== undefined) {
        if (result.ok) {
            differences.push(`${input.name}: the jar failed (${jvmError.join("; ")}), the module formatted it`);
        } else if (result.error !== jvmError.join("\n")) {
            differences.push(`${input.name}: the jar says ${jvmError.join("; ")}, the module ${result.error}`);
        } else {
            failedOnBoth++;
        }
    } else if (!result.ok) {
        differences.push(`${input.name}: the jar formatted it, the module failed: ${result.error}`);
    } else if (result.output !== readFileSync(input.path, "utf8")) {
        differences.push(`${input.name}: the outputs differ`);
    }
}
const millis = performance.now() - started;

console.log(`${inputs.length} inputs in ${Math.round(millis)} ms, ${failedOnBoth} of them not formattable on both`);
if (differences.length > 0) {
    console.error(differences.join("\n"));
    console.error(`${differences.length} of ${inputs.length} inputs differ between the module and the jar`);
    process.exit(1);
}
console.log("The module and the jar agree on every input");
