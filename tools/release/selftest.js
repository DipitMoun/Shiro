"use strict";
// Round trip checks of the patch tool. Run: node tools/release/selftest.js
const assert = require("assert");
const crypto = require("crypto");
const { createPatch, applyPatch } = require("./apk-patch");

function randomBytes(n, seed) {
  const out = Buffer.alloc(n);
  let x = seed >>> 0;
  for (let i = 0; i < n; i++) { x = (Math.imul(x, 1664525) + 1013904223) >>> 0; out[i] = x >>> 24; }
  return out;
}

// 1. a file with a changed middle, an inserted block and a removed block
const base = randomBytes(300_000, 1);
const edited = Buffer.concat([
  base.subarray(0, 100_000),
  randomBytes(5_000, 2),
  base.subarray(100_000, 200_000),
  base.subarray(230_000),
]);
let patch = createPatch(base, edited);
assert(patch.length < edited.length / 4, "patch should be much smaller than the file");
assert(applyPatch(base, patch).equals(edited), "round trip");

// 2. identical files
patch = createPatch(base, base);
assert(applyPatch(base, patch).equals(base));
assert(patch.length < 200, "identical files give a tiny patch");

// 3. nothing in common, empty files, tiny files
for (const [o, n] of [[randomBytes(5000, 3), randomBytes(7000, 4)], [Buffer.alloc(0), randomBytes(100, 5)], [randomBytes(100, 6), Buffer.alloc(0)], [randomBytes(10, 7), randomBytes(10, 8)]]) {
  assert(applyPatch(o, createPatch(o, n)).equals(n));
}

// 4. a patch must refuse another base file and corrupted data
patch = createPatch(base, edited);
assert.throws(() => applyPatch(randomBytes(300_000, 9), patch), /another base/);
const corrupted = Buffer.from(patch);
corrupted[corrupted.length - 40] ^= 0xff;
assert.throws(() => applyPatch(base, corrupted));
assert.throws(() => applyPatch(base, patch.subarray(0, patch.length - 5)));

console.log("apk-patch: all checks passed");
