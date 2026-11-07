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

// The native image's smoke test in ci.yml, on the module: a file that needs formatting, a formatted one, one that does
// not parse and one in Java 25 syntax. Usage: node smoke.mjs <open-java-format.js> <version>

import assert from "node:assert/strict";
import { load } from "./load.mjs";

const [wrapperPath, version] = process.argv.slice(2);

const started = performance.now();
const openJavaFormat = await load(wrapperPath);
console.log(`Started in ${Math.round(performance.now() - started)} ms`);

assert.equal(openJavaFormat.version, version);

// The result also carries a symbol of Web Image's own; the contract is ok and output, or ok and error.
function format(source) {
    const result = openJavaFormat.format(source);
    return result.ok ? { ok: true, output: result.output } : { ok: false, error: result.error };
}

assert.deepEqual(format("class A{int x;}\n"), { ok: true, output: "class A {\n    int x;\n}\n" });
assert.deepEqual(format("class A {\n    int x;\n}\n"), { ok: true, output: "class A {\n    int x;\n}\n" });

const broken = format("class B {\n");
assert.equal(broken.ok, false);
assert.match(broken.error, /^\d+:\d+: error: /);

// A compact source file with a module import and an unnamed pattern.
assert.deepEqual(
    format(
        "import module java.base;\nrecord Box(int a,int b){}\n"
            + "void main(){Object o=new Box(1,2);if(o instanceof Box(_,_)){IO.println(List.of(1));}}\n"),
    {
        ok: true,
        output: "import module java.base;\n\nrecord Box(int a, int b) {}\n\nvoid main() {\n"
            + "    Object o = new Box(1, 2);\n    if (o instanceof Box(_, _)) {\n        IO.println(List.of(1));\n"
            + "    }\n}\n",
    });

console.log(`open-java-format ${openJavaFormat.version}: smoke test passed`);
