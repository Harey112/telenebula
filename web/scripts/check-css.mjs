import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";

async function cssFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return cssFiles(path);
    return entry.name.endsWith(".css") ? [path] : [];
  }));
  return nested.flat();
}

let invalid = false;
for (const file of await cssFiles("src/ui")) {
  if (file.endsWith("ui/theme.css")) continue;
  const source = await readFile(file, "utf8");
  if (/#[0-9a-fA-F]{3,8}\b/.test(source)) {
    process.stderr.write(`${file}: use a colour token from ui/theme.css\n`);
    invalid = true;
  }
}
if (invalid) process.exitCode = 1;
