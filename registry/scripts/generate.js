#!/usr/bin/env node
'use strict';

// Generates the native discovery tables from registry/chips.json
// (specs/feature_i2c_discovery.md section 4.5).
//
// Usage:
//   node registry/scripts/generate.js                         # write tables
//   node registry/scripts/generate.js --check                 # exit 1 if any table is stale
//   node registry/scripts/generate.js --registry <path-or-url> # use another chips.json

const fs = require('fs');
const path = require('path');
const { REPO_ROOT, loadRegistry, hex, chipAddresses } = require('./lib');

const HEADER = 'GENERATED from registry/chips.json by registry/scripts/generate.js - do not edit; run the script instead.';

function model(registry) {
    return registry.chips.map(c => ({
        id: c.id,
        name: c.name,
        driver: c.driver || null,
        writeSensitive: c.probe_safety === 'write_sensitive',
        aliased: !!c.aliased,
        addresses: chipAddresses(c),
        probe: c.id_probe && {
            register: hex(c.id_probe.register),
            regBytes: c.id_probe.reg_bytes,
            length: c.id_probe.length,
            order: c.id_probe.byte_order,
            mask: hex(c.id_probe.mask),
            expected: c.id_probe.expected.map(hex),
        },
    }));
}

const h = (n, w) => '0x' + n.toString(16).toUpperCase().padStart(w, '0');
const q = s => JSON.stringify(s);

function python(chips) {
    const out = [`# ${HEADER}`, '', 'SCHEMA_VERSION = 1', '', 'CHIPS = ('];
    for (const c of chips) {
        const p = c.probe
            ? `{"register": ${h(c.probe.register, 2)}, "reg_bytes": ${c.probe.regBytes}, "length": ${c.probe.length}, "byte_order": "${c.probe.order}", "mask": ${h(c.probe.mask, 2 * c.probe.length)}, "expected": (${c.probe.expected.map(e => h(e, 2 * c.probe.length)).join(', ')},)}`
            : 'None';
        out.push(`    {"id": ${q(c.id)}, "driver": ${c.driver ? q(c.driver) : 'None'}, "write_sensitive": ${c.writeSensitive ? 'True' : 'False'}, "aliased": ${c.aliased ? 'True' : 'False'},`);
        out.push(`     "addresses": (${c.addresses.map(a => h(a, 2)).join(', ')},),`);
        out.push(`     "probe": ${p}},`);
    }
    out.push(')', '');
    return out.join('\n');
}

function node(chips) {
    const body = chips.map(c => '    ' + JSON.stringify({
        id: c.id, driver: c.driver, writeSensitive: c.writeSensitive, aliased: c.aliased,
        addresses: c.addresses, probe: c.probe,
    })).join(',\n');
    return `'use strict';\n\n// ${HEADER}\n\nconst SCHEMA_VERSION = 1;\n\nconst CHIPS = [\n${body},\n];\n\nmodule.exports = { SCHEMA_VERSION, CHIPS };\n`;
}

function cpp(chips) {
    const out = [
        `// ${HEADER}`, '#pragma once', '#include <cstddef>', '#include <cstdint>', '',
        'namespace periph {', 'namespace discovery {', '',
        'struct IdProbe {',
        '    uint32_t reg;', '    uint8_t regBytes;', '    uint8_t length;', '    bool littleEndian;', '    uint32_t mask;',
        '    const uint32_t* expected;', '    size_t nExpected;', '};', '',
        'struct ChipEntry {',
        '    const char* id;', '    const char* driver;  // nullptr when the chip has no driver in this library',
        '    bool writeSensitive;', '    bool aliased;', '    const uint8_t* addresses;', '    size_t nAddresses;',
        '    const IdProbe* probe;  // nullptr when the chip has no identity register', '};', '',
    ];
    for (const c of chips) {
        const v = c.id.replace(/[^a-z0-9]/g, '_');
        out.push(`static constexpr uint8_t kAddr_${v}[] = {${c.addresses.map(a => h(a, 2)).join(', ')}};`);
        if (c.probe) {
            out.push(`static constexpr uint32_t kExp_${v}[] = {${c.probe.expected.map(e => h(e, 2 * c.probe.length)).join(', ')}};`);
            out.push(`static constexpr IdProbe kProbe_${v} = {${h(c.probe.register, 2)}, ${c.probe.regBytes}, ${c.probe.length}, ${c.probe.order === 'little'}, ${h(c.probe.mask, 2 * c.probe.length)}, kExp_${v}, ${c.probe.expected.length}};`);
        }
    }
    out.push('', 'static constexpr ChipEntry kChips[] = {');
    for (const c of chips) {
        const v = c.id.replace(/[^a-z0-9]/g, '_');
        out.push(`    {${q(c.id)}, ${c.driver ? q(c.driver) : 'nullptr'}, ${c.writeSensitive}, ${c.aliased}, kAddr_${v}, ${c.addresses.length}, ${c.probe ? '&kProbe_' + v : 'nullptr'}},`);
    }
    out.push('};', `static constexpr size_t kChipCount = ${chips.length};`, '', '}  // namespace discovery', '}  // namespace periph', '');
    return out.join('\n');
}

function rust(chips) {
    const out = [
        `// ${HEADER}`, '',
        '/// Identity-register read that confirms a chip.',
        '#[derive(Debug, Clone, Copy)]', 'pub struct IdProbe {',
        '    pub register: u32,', '    pub reg_bytes: usize,', '    pub length: usize,', '    pub little_endian: bool,', '    pub mask: u32,', '    pub expected: &\'static [u32],', '}', '',
        '/// One registry entry.', '#[derive(Debug, Clone, Copy)]', 'pub struct ChipEntry {',
        '    pub id: &\'static str,', '    pub driver: Option<&\'static str>,', '    pub write_sensitive: bool,', '    pub aliased: bool,',
        '    pub addresses: &\'static [u8],', '    pub probe: Option<IdProbe>,', '}', '',
        'pub const CHIPS: &[ChipEntry] = &[',
    ];
    for (const c of chips) {
        const p = c.probe
            ? `Some(IdProbe { register: ${h(c.probe.register, 2)}, reg_bytes: ${c.probe.regBytes}, length: ${c.probe.length}, little_endian: ${c.probe.order === 'little'}, mask: ${h(c.probe.mask, 2 * c.probe.length)}, expected: &[${c.probe.expected.map(e => h(e, 2 * c.probe.length)).join(', ')}] })`
            : 'None';
        out.push(`    ChipEntry { id: ${q(c.id)}, driver: ${c.driver ? `Some(${q(c.driver)})` : 'None'}, write_sensitive: ${c.writeSensitive}, aliased: ${c.aliased}, addresses: &[${c.addresses.map(a => h(a, 2)).join(', ')}], probe: ${p} },`);
    }
    out.push('];', '');
    return out.join('\n');
}

function java(chips) {
    const out = [
        `// ${HEADER}`, 'package it.uhde.periph.discovery;', '',
        '/** Generated registry table; see specs/feature_i2c_discovery.md. */',
        'public final class DiscoveryRegistry {',
        '    private DiscoveryRegistry() {}', '',
        '    /** Identity-register read that confirms a chip. */',
        '    public record IdProbe(int register, int regBytes, int length, boolean littleEndian, long mask, long[] expected) {}', '',
        '    /** One registry entry; {@code driver} and {@code probe} may be null. */',
        '    public record Chip(String id, String driver, boolean writeSensitive, boolean aliased, int[] addresses, IdProbe probe) {}', '',
        '    public static final Chip[] CHIPS = {',
    ];
    for (const c of chips) {
        const p = c.probe
            ? `new IdProbe(${h(c.probe.register, 2)}, ${c.probe.regBytes}, ${c.probe.length}, ${c.probe.order === 'little'}, ${h(c.probe.mask, 2 * c.probe.length)}L, new long[] {${c.probe.expected.map(e => h(e, 2 * c.probe.length) + 'L').join(', ')}})`
            : 'null';
        out.push(`        new Chip(${q(c.id)}, ${c.driver ? q(c.driver) : 'null'}, ${c.writeSensitive}, ${c.aliased}, new int[] {${c.addresses.map(a => h(a, 2)).join(', ')}}, ${p}),`);
    }
    out.push('    };', '}', '');
    return out.join('\n');
}

function go(chips) {
    const out = [
        `// ${HEADER}`, '', '//go:build linux', '', 'package discovery', '',
        '// IdProbe is the identity-register read that confirms a chip.',
        'type IdProbe struct {', '\tRegister     uint32', '\tRegBytes     int', '\tLength       int', '\tLittleEndian bool', '\tMask         uint32', '\tExpected     []uint32', '}', '',
        '// Chip is one registry entry. Driver is "" and Probe is nil when absent.',
        'type Chip struct {', '\tID             string', '\tDriver         string', '\tWriteSensitive bool', '\tAliased        bool', '\tAddresses      []uint8', '\tProbe          *IdProbe', '}', '',
        'var registry = []Chip{',
    ];
    for (const c of chips) {
        const p = c.probe
            ? `&IdProbe{Register: ${h(c.probe.register, 2)}, RegBytes: ${c.probe.regBytes}, Length: ${c.probe.length}, LittleEndian: ${c.probe.order === 'little'}, Mask: ${h(c.probe.mask, 2 * c.probe.length)}, Expected: []uint32{${c.probe.expected.map(e => h(e, 2 * c.probe.length)).join(', ')}}}`
            : 'nil';
        out.push(`\t{ID: ${q(c.id)}, Driver: ${q(c.driver || '')}, WriteSensitive: ${c.writeSensitive}, Aliased: ${c.aliased}, Addresses: []uint8{${c.addresses.map(a => h(a, 2)).join(', ')}}, Probe: ${p}},`);
    }
    out.push('}', '');
    return out.join('\n');
}

const TARGETS = [
    ['python/periph/discovery_registry.py', python],
    ['cpp/src/discovery/DiscoveryRegistry.h', cpp],
    ['nodejs/packages/periph/src/discovery/registry.js', node],
    ['rust/periph/src/discovery/registry.rs', rust],
    ['jvm/periph-java/src/main/java/it/uhde/periph/discovery/DiscoveryRegistry.java', java],
    ['go/periph/discovery/registry.go', go],
];

function main() {
    const argv = process.argv.slice(2);
    const check = argv.includes('--check');
    const ri = argv.indexOf('--registry');
    const src = ri >= 0 ? argv[ri + 1] : undefined;
    if (src && /^https?:/.test(src)) {
        console.error('generate.js: URL registries must be downloaded first (pin a release, then pass the local path)');
        process.exit(2);
    }
    const chips = model(loadRegistry(src));
    let stale = 0;
    for (const [rel, fn] of TARGETS) {
        const file = path.join(REPO_ROOT, rel);
        const text = fn(chips);
        const current = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
        if (current === text) continue;
        if (check) {
            console.error(`stale: ${rel}`);
            stale++;
        } else {
            fs.mkdirSync(path.dirname(file), { recursive: true });
            fs.writeFileSync(file, text);
            console.log(`wrote ${rel}`);
        }
    }
    if (stale) {
        console.error('run: node registry/scripts/generate.js');
        process.exit(1);
    }
}

main();
