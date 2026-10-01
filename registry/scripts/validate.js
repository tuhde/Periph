#!/usr/bin/env node
'use strict';

// Validates registry/chips.json: structure (mirrors schema.json) and the
// semantic rules from specs/feature_i2c_discovery.md section 4.3.
//
// Usage:
//   node registry/scripts/validate.js [--check] [--registry <path>]
//
// --check is accepted for CI symmetry with the generators; validation always
// exits 1 on any problem.

const fs = require('fs');
const path = require('path');
const { REGISTRY_DIR, REPO_ROOT, load, loadRegistry, hex, chipAddresses } = require('./lib');

const ADDR_RE = /^0x[0-9A-Fa-f]{2}(\.\.0x[0-9A-Fa-f]{2})?$/;
const HEX_RE = /^0x[0-9A-Fa-f]+$/;
const SAFETY = ['register_pointer', 'write_sensitive'];
const CHIP_KEYS = ['id', 'name', 'category', 'addresses', 'id_probe', 'probe_safety', 'aliased', 'driver', 'datasheet'];
const PROBE_KEYS = ['register', 'reg_bytes', 'length', 'byte_order', 'mask', 'expected'];

function validate(registry, ambiguities, opts) {
    const errors = [];
    const err = (id, msg) => errors.push(`${id}: ${msg}`);

    if (registry.schema_version !== 1) {
        return [`unsupported schema_version ${registry.schema_version} (this validator understands 1)`];
    }
    if (!Array.isArray(registry.chips)) return ['chips must be an array'];

    const ids = new Set();
    for (const chip of registry.chips) {
        const id = chip.id || '<no id>';
        for (const k of Object.keys(chip)) if (!CHIP_KEYS.includes(k)) err(id, `unknown field "${k}"`);
        if (typeof chip.id !== 'string' || !/^[a-z0-9][a-z0-9-]*$/.test(chip.id)) err(id, 'id must be a lower-case slug');
        if (ids.has(chip.id)) err(id, 'duplicate id');
        ids.add(chip.id);
        if (typeof chip.name !== 'string' || !chip.name) err(id, 'name required');
        if (typeof chip.category !== 'string' || !/^[a-z_]+$/.test(chip.category)) err(id, 'category must match [a-z_]+');
        if (typeof chip.datasheet !== 'string' || !chip.datasheet) err(id, 'datasheet required');
        if (!SAFETY.includes(chip.probe_safety)) err(id, `probe_safety must be one of ${SAFETY.join(', ')}`);
        if (chip.aliased !== undefined && typeof chip.aliased !== 'boolean') err(id, 'aliased must be boolean');
        if (chip.driver !== undefined && chip.driver !== null && typeof chip.driver !== 'string') err(id, 'driver must be a string or null');

        if (!Array.isArray(chip.addresses) || chip.addresses.length === 0) {
            err(id, 'addresses must be a non-empty array');
        } else if (chip.addresses.every(a => typeof a === 'string' && ADDR_RE.test(a))) {
            for (const a of chip.addresses) {
                const [lo, hi] = a.split('..').map(hex);
                if (hi !== undefined && hi < lo) err(id, `range ${a} is reversed`);
                if (lo < 0x08 || (hi === undefined ? lo : hi) > 0x77) err(id, `address ${a} outside 0x08-0x77`);
            }
            if (chip.aliased) {
                const addrs = chipAddresses(chip);
                if (addrs.length < 2 || addrs[addrs.length - 1] - addrs[0] !== addrs.length - 1) {
                    err(id, 'aliased chip needs a contiguous block of at least two addresses');
                }
            }
        } else {
            err(id, 'addresses must be "0xNN" or "0xNN..0xMM" strings');
        }

        const p = chip.id_probe;
        if (p === undefined) {
            err(id, 'id_probe required (use null for chips with no identity register)');
        } else if (p !== null) {
            for (const k of Object.keys(p)) if (!PROBE_KEYS.includes(k)) err(id, `id_probe: unknown field "${k}"`);
            for (const k of ['register', 'mask']) if (typeof p[k] !== 'string' || !HEX_RE.test(p[k])) err(id, `id_probe.${k} must be a hex string`);
            for (const k of ['reg_bytes', 'length']) if (!Number.isInteger(p[k]) || p[k] < 1 || p[k] > 4) err(id, `id_probe.${k} must be an integer 1-4`);
            if (!['big', 'little'].includes(p.byte_order)) err(id, 'id_probe.byte_order must be big or little');
            if (!Array.isArray(p.expected) || p.expected.length === 0 || !p.expected.every(e => typeof e === 'string' && HEX_RE.test(e))) {
                err(id, 'id_probe.expected must be a non-empty array of hex strings');
            } else if (HEX_RE.test(p.mask || '')) {
                for (const e of p.expected) if ((hex(e) & ~hex(p.mask)) !== 0) err(id, `expected ${e} has bits outside mask ${p.mask}`);
            }
            if (Number.isInteger(p.reg_bytes) && HEX_RE.test(p.register || '') && hex(p.register) >= 2 ** (8 * p.reg_bytes)) {
                err(id, `register ${p.register} does not fit in reg_bytes=${p.reg_bytes}`);
            }
        }

        if (opts.repoRoot && chip.driver) {
            const spec = path.join(opts.repoRoot, 'specs', chip.category, `${chip.driver}.md`);
            if (!fs.existsSync(spec)) err(id, `driver "${chip.driver}" has no spec at ${path.relative(opts.repoRoot, spec)}`);
        }
    }
    if (errors.length) return errors;

    // Overlapping addresses + the same identity register + a raw value both
    // chips can return => the pair cannot be told apart by an ID read.
    const listed = new Set((ambiguities.pairs || []).map(p => [...p].sort().join('|')));
    const seen = new Set();
    const chips = registry.chips;
    for (let i = 0; i < chips.length; i++) {
        for (let j = i + 1; j < chips.length; j++) {
            const a = chips[i], b = chips[j];
            if (!a.id_probe || !b.id_probe) continue;
            const pa = a.id_probe, pb = b.id_probe;
            if (hex(pa.register) !== hex(pb.register) || pa.reg_bytes !== pb.reg_bytes ||
                pa.length !== pb.length || pa.byte_order !== pb.byte_order) continue;
            const addrsB = new Set(chipAddresses(b));
            if (!chipAddresses(a).some(x => addrsB.has(x))) continue;
            const clash = pa.expected.some(ea => pb.expected.some(eb => ((hex(ea) ^ hex(eb)) & hex(pa.mask) & hex(pb.mask)) === 0));
            if (!clash) continue;
            const key = [a.id, b.id].sort().join('|');
            seen.add(key);
            if (!listed.has(key)) err(`${a.id}/${b.id}`, 'identity values can collide at an overlapping address; add the pair to known_ambiguities.json if intended');
        }
    }
    for (const key of listed) if (!seen.has(key)) errors.push(`known_ambiguities.json: pair ${key.replace('|', '/')} no longer collides; remove it`);
    return errors;
}

function main() {
    const argv = process.argv.slice(2);
    const ri = argv.indexOf('--registry');
    const regFile = ri >= 0 ? argv[ri + 1] : undefined;
    const registry = loadRegistry(regFile);
    const ambFile = path.join(regFile ? path.dirname(regFile) : REGISTRY_DIR, 'known_ambiguities.json');
    const ambiguities = fs.existsSync(ambFile) ? load(ambFile) : { pairs: [] };
    // Driver-file checks only make sense inside this repo.
    const inRepo = !regFile && fs.existsSync(path.join(REPO_ROOT, 'specs'));
    const errors = validate(registry, ambiguities, { repoRoot: inRepo ? REPO_ROOT : null });
    if (errors.length) {
        for (const e of errors) console.error(`registry: ${e}`);
        process.exit(1);
    }
    console.log(`registry OK (${registry.chips.length} chips)`);
}

if (require.main === module) main();
module.exports = { validate };
