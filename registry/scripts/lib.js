'use strict';

// Shared helpers for validate.js and generate.js.

const fs = require('fs');
const path = require('path');

const REGISTRY_DIR = path.join(__dirname, '..');
const REPO_ROOT = path.join(REGISTRY_DIR, '..');

function load(file) {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function loadRegistry(file) {
    return load(file || path.join(REGISTRY_DIR, 'chips.json'));
}

function hex(s) {
    return parseInt(s, 16);
}

// "0x76" -> [0x76]; "0x40..0x4F" -> [0x40 .. 0x4F]
function expandAddress(s) {
    const [lo, hi] = s.split('..').map(hex);
    const out = [];
    for (let a = lo; a <= (hi === undefined ? lo : hi); a++) out.push(a);
    return out;
}

function chipAddresses(chip) {
    const set = new Set();
    for (const a of chip.addresses) for (const v of expandAddress(a)) set.add(v);
    return [...set].sort((a, b) => a - b);
}

module.exports = { REGISTRY_DIR, REPO_ROOT, load, loadRegistry, hex, expandAddress, chipAddresses };
