#!/usr/bin/env node
"use strict";
// Prepares everything to publish for a release, after `gradlew assembleRelease`:
//   - the APKs, named by version and architecture
//   - patches from the previous versions (what an update really has to download)
//   - latest.json, the file the app reads to learn about the update
//
//   node tools/release/make-release.js [--base-url URL] [--out dist/release] [--keep 3]
//
// Every APK built is also kept in release-archive/<versionCode>/ : that is what the next
// release is compared with. Keep the archive; without it there are no patches.
const fs = require("fs");
const path = require("path");
const { createPatch, applyPatch, sha256 } = require("./apk-patch");

const repo = path.resolve(__dirname, "..", "..");
const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, all) => (a.startsWith("--") ? [...acc, [a.slice(2), all[i + 1]]] : acc), []),
);
const apkDir = path.resolve(repo, args["apk-dir"] || "app/build/outputs/apk/release");
const outDir = path.resolve(repo, args.out || "dist/release");
const archiveDir = path.resolve(repo, args.archive || "release-archive");
const notesFile = path.resolve(repo, args.notes || "WHATS-NEW.md");
const keep = Number(args.keep || 3);
const MIN_SAVING = 0.3; // a patch that does not save at least 30 % is not worth publishing

const mb = (n) => (n / 1048576).toFixed(1) + " Mo";
const hex = (buf) => sha256(buf).toString("hex");

// ---- what was built
const meta = JSON.parse(fs.readFileSync(path.join(apkDir, "output-metadata.json"), "utf8"));
if (!meta.elements?.length) throw new Error("output-metadata.json has no APK");
const versionCode = meta.elements[0].versionCode;
const versionName = meta.elements[0].versionName;
const builds = meta.elements.map((e) => ({
  abi: e.filters?.find((f) => f.filterType === "ABI")?.value || "universal",
  file: path.join(apkDir, e.outputFile),
}));

const baseUrl = (args["base-url"] || `https://github.com/DipitMoun/Shiro/releases/download/v${versionName}`).replace(/\/$/, "");
fs.mkdirSync(outDir, { recursive: true });
fs.mkdirSync(archiveDir, { recursive: true });

// ---- earlier versions available for patches (newest first)
const previous = fs.readdirSync(archiveDir)
  .filter((d) => /^\d+$/.test(d) && Number(d) < versionCode)
  .map(Number)
  .sort((a, b) => b - a)
  .slice(0, keep);

const apks = {};
const published = [];
for (const { abi, file } of builds) {
  const apk = fs.readFileSync(file);
  const apkSha = hex(apk);
  const archived = path.join(archiveDir, String(versionCode), `${abi}.apk`);
  if (fs.existsSync(archived) && hex(fs.readFileSync(archived)) !== apkSha) {
    throw new Error(`versionCode ${versionCode} was already released with another ${abi} APK: raise versionCode in app/build.gradle.kts`);
  }
  fs.mkdirSync(path.dirname(archived), { recursive: true });
  fs.copyFileSync(file, archived);

  const apkName = `shiro-${versionName}-${abi}.apk`;
  fs.copyFileSync(file, path.join(outDir, apkName));
  published.push([apkName, apk.length]);

  const patches = [];
  for (const from of previous) {
    const oldFile = path.join(archiveDir, String(from), `${abi}.apk`);
    if (!fs.existsSync(oldFile)) continue;
    const old = fs.readFileSync(oldFile);
    const patch = createPatch(old, apk);
    if (patch.length > apk.length * (1 - MIN_SAVING)) {
      console.log(`  ${abi}: patch from ${from} saves too little (${mb(patch.length)}), skipped`);
      continue;
    }
    // Never publish a patch that does not rebuild the APK exactly.
    if (!applyPatch(old, patch).equals(apk)) throw new Error(`patch ${from} -> ${versionCode} (${abi}) does not rebuild the APK`);
    const patchName = `patch-${abi}-${from}-to-${versionCode}.bin`;
    fs.writeFileSync(path.join(outDir, patchName), patch);
    published.push([patchName, patch.length]);
    patches.push({ fromSha256: hex(old), url: `${baseUrl}/${patchName}`, size: patch.length });
  }
  apks[abi] = { url: `${baseUrl}/${apkName}`, sha256: apkSha, size: apk.length, patches };
}

// ---- "what's new" shown by the app
function notes() {
  if (!fs.existsSync(notesFile)) return "";
  const lines = fs.readFileSync(notesFile, "utf8").split(/\r?\n/);
  const start = lines.findIndex((l) => l.trim() === "### Quoi de neuf");
  if (start < 0) return "";
  const body = [];
  for (const line of lines.slice(start + 1)) {
    if (line.startsWith("### ") || line.trim() === "---") break;
    body.push(line);
  }
  return body.join("\n").trim();
}

const manifest = {
  versionCode,
  versionName,
  notes: notes(),
  releasePage: `https://github.com/DipitMoun/Shiro/releases/tag/v${versionName}`,
  apks,
};
fs.writeFileSync(path.join(outDir, "latest.json"), JSON.stringify(manifest, null, 2));
published.push(["latest.json", fs.statSync(path.join(outDir, "latest.json")).size]);

console.log(`\nVersion ${versionName} (code ${versionCode}) prête dans ${path.relative(repo, outDir)}/`);
published.forEach(([name, size]) => console.log(`  ${mb(size).padStart(9)}  ${name}`));
const arm = apks["arm64-v8a"];
if (arm) {
  const best = arm.patches[0];
  console.log(`\nUn testeur en arm64 téléchargera ${best ? mb(best.size) + " (patch)" : mb(arm.size) + " (APK complet)"} au lieu de ${mb(arm.size)}.`);
}
console.log(`\nÀ publier : une release GitHub "v${versionName}" avec TOUS les fichiers ci-dessus (latest.json compris).`);
