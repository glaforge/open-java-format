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

// Starts the module in Node the way a page does: the wrapper as a classic script, then GraalVM.run, whose promise
// settles once WasmMain has published globalThis.openJavaFormat.

import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import vm from "node:vm";

export async function load(wrapperPath) {
    // The wrapper finds Node's fs through a global require, which an ES module does not have.
    globalThis.require = createRequire(import.meta.url);
    vm.runInThisContext(readFileSync(wrapperPath, "utf8"), { filename: wrapperPath });
    const config = new GraalVM.Config();
    config.wasm_path = `${wrapperPath}.wasm`;
    await GraalVM.run([], config);
    return globalThis.openJavaFormat;
}
