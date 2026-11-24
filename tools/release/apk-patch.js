"use strict";
// Binary patch between two APKs: lets an update download only what changed.
//
// Format ("SHPT" version 1), read by app/.../data/updater/ApkPatch.kt:
//   6 bytes   magic  53 48 50 54 01 0A
//   8 bytes   size of the old file (big endian)      32 bytes  sha256 of the old file
//   8 bytes   size of the new file (big endian)      32 bytes  sha256 of the new file
//   then operations until END:
//     00                    END
//     01 offset len         COPY len bytes of the old file starting at offset (varints)
//     02 len <len bytes>    INSERT literal bytes
// varint = unsigned LEB128.
//
// The new file is rebuilt byte for byte, so its signature stays valid.
const crypto = require("crypto");

const MAGIC = Buffer.from([0x53, 0x48, 0x50, 0x54, 0x01, 0x0a]);
const OP_END = 0, OP_COPY = 1, OP_INSERT = 2;
const WINDOW = 1024;

const sha256 = (buf) => crypto.createHash("sha256").update(buf).digest();

function varint(n) {
  const out = [];
  while (n >= 128) { out.push((n % 128) | 128); n = Math.floor(n / 128); }
  out.push(n);
  return Buffer.from(out);
}

function u64(n) {
  const b = Buffer.alloc(8);
  b.writeBigUInt64BE(BigInt(n));
  return b;
}

// Weak rolling checksum over a WINDOW-byte block (same idea as rsync).
function weak(buf, pos) {
  let a = 0, b = 0;
  for (let i = 0; i < WINDOW; i++) { const v = buf[pos + i]; a += v; b += (WINDOW - i) * v; }
  return [a & 0xffff, b & 0xffff];
}

function createPatch(oldBuf, newBuf) {
  const index = new Map();
  for (let p = 0; p + WINDOW <= oldBuf.length; p += WINDOW) {
    const [a, b] = weak(oldBuf, p);
    const key = ((b << 16) | a) >>> 0;
    const list = index.get(key);
    if (!list) index.set(key, [p]); else if (list.length < 4) list.push(p);
  }

  const parts = [MAGIC, u64(oldBuf.length), sha256(oldBuf), u64(newBuf.length), sha256(newBuf)];
  let literalStart = 0;
  const flushLiteral = (end) => {
    if (end > literalStart) parts.push(Buffer.from([OP_INSERT]), varint(end - literalStart), newBuf.subarray(literalStart, end));
  };

  let pos = 0, a = 0, b = 0, rolled = false;
  while (pos + WINDOW <= newBuf.length) {
    if (!rolled) { [a, b] = weak(newBuf, pos); rolled = true; }
    const cands = index.get(((b << 16) | a) >>> 0);
    let matched = false;
    if (cands) {
      for (let c of cands) {
        if (Buffer.compare(oldBuf.subarray(c, c + WINDOW), newBuf.subarray(pos, pos + WINDOW)) !== 0) continue;
        let start = pos, from = c, len = WINDOW;
        // grow the match backwards over bytes that would otherwise be literal, then forwards
        while (start > literalStart && from > 0 && newBuf[start - 1] === oldBuf[from - 1]) { start--; from--; len++; }
        while (start + len < newBuf.length && from + len < oldBuf.length && newBuf[start + len] === oldBuf[from + len]) len++;
        flushLiteral(start);
        parts.push(Buffer.from([OP_COPY]), varint(from), varint(len));
        pos = start + len;
        literalStart = pos;
        rolled = false;
        matched = true;
        break;
      }
    }
    if (!matched) {
      const out = newBuf[pos], inn = newBuf[pos + WINDOW] ?? 0;
      a = (a - out + inn) & 0xffff;
      b = (b - WINDOW * out + a) & 0xffff;
      pos++;
    }
  }
  flushLiteral(newBuf.length);
  parts.push(Buffer.from([OP_END]));
  return Buffer.concat(parts);
}

function readVarint(buf, state) {
  let result = 0, mul = 1;
  for (;;) {
    const byte = buf[state.pos++];
    result += (byte & 0x7f) * mul;
    if (!(byte & 0x80)) return result;
    mul *= 128;
  }
}

/** Rebuilds the new file from the old one; throws when anything does not add up. */
function applyPatch(oldBuf, patch) {
  if (!patch.subarray(0, 6).equals(MAGIC)) throw new Error("not a Shiro patch");
  const st = { pos: 6 };
  const baseSize = Number(patch.readBigUInt64BE(st.pos)); st.pos += 8;
  const baseSha = patch.subarray(st.pos, st.pos + 32); st.pos += 32;
  const newSize = Number(patch.readBigUInt64BE(st.pos)); st.pos += 8;
  const newSha = patch.subarray(st.pos, st.pos + 32); st.pos += 32;
  if (oldBuf.length !== baseSize || !sha256(oldBuf).equals(baseSha)) throw new Error("patch is for another base file");

  const out = Buffer.alloc(newSize);
  let written = 0;
  for (;;) {
    const op = patch[st.pos++];
    if (op === OP_END) break;
    if (op === OP_COPY) {
      const offset = readVarint(patch, st), len = readVarint(patch, st);
      if (offset + len > oldBuf.length || written + len > newSize) throw new Error("copy out of range");
      oldBuf.copy(out, written, offset, offset + len);
      written += len;
    } else if (op === OP_INSERT) {
      const len = readVarint(patch, st);
      if (written + len > newSize || st.pos + len > patch.length) throw new Error("insert out of range");
      patch.copy(out, written, st.pos, st.pos + len);
      st.pos += len;
      written += len;
    } else {
      throw new Error("unknown operation " + op);
    }
  }
  if (written !== newSize || !sha256(out).equals(newSha)) throw new Error("rebuilt file does not match");
  return out;
}

module.exports = { createPatch, applyPatch, sha256 };
