#!/usr/bin/env node
'use strict';

// Unit tests for validate.js: the shipped registry passes, and each failure
// class from specs/feature_i2c_discovery.md section 4.3 is rejected.
//
// Usage: node registry/scripts/validate_test.js

const { loadRegistry, load } = require('./lib');
const path = require('path');
const { validate } = require('./validate');

let passed = 0;
let failed = 0;

function check(label, cond) {
    if (cond) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const clone = o => JSON.parse(JSON.stringify(o));
const base = loadRegistry();
const amb = load(path.join(__dirname, '..', 'known_ambiguities.json'));

function errorsFor(mutate, ambiguities = amb) {
    const r = clone(base);
    mutate(r);
    return validate(r, ambiguities, { repoRoot: null });
}
const chip = (r, id) => r.chips.find(c => c.id === id);
const has = (errs, text) => errs.some(e => e.includes(text));

check('shipped registry is valid', validate(base, amb, { repoRoot: path.join(__dirname, '..', '..') }).length === 0);
check('unknown schema_version', has(errorsFor(r => { r.schema_version = 2; }), 'unsupported schema_version'));
check('duplicate id', has(errorsFor(r => { r.chips[1].id = r.chips[0].id; }), 'duplicate id'));
check('bad id slug', has(errorsFor(r => { r.chips[0].id = 'Bad Id'; }), 'lower-case slug'));
check('address below 0x08', has(errorsFor(r => { chip(r, 'bme280').addresses = ['0x03']; }), 'outside 0x08-0x77'));
check('address above 0x77', has(errorsFor(r => { chip(r, 'bme280').addresses = ['0x76..0x79']; }), 'outside 0x08-0x77'));
check('reversed range', has(errorsFor(r => { chip(r, 'bme280').addresses = ['0x77..0x76']; }), 'reversed'));
check('malformed address', has(errorsFor(r => { chip(r, 'bme280').addresses = ['118']; }), 'must be "0xNN"'));
check('bad probe_safety', has(errorsFor(r => { chip(r, 'bme280').probe_safety = 'maybe'; }), 'probe_safety'));
check('missing id_probe', has(errorsFor(r => { delete chip(r, 'bme280').id_probe; }), 'id_probe required'));
check('unknown field', has(errorsFor(r => { chip(r, 'bme280').colour = 'red'; }), 'unknown field'));
check('expected outside mask', has(errorsFor(r => { chip(r, 'mpu6050').id_probe.expected = ['0x01']; }), 'outside mask'));
check('register wider than reg_bytes', has(errorsFor(r => { chip(r, 'bme280').id_probe.register = '0x1D0'; }), 'does not fit'));
check('aliased needs contiguous block', has(errorsFor(r => { chip(r, '24aa02uid').addresses = ['0x50', '0x52']; }), 'contiguous'));
check('missing spec for driver (in-repo check)', validate(
    (() => { const r = clone(base); chip(r, 'bme280').driver = 'no-such-chip'; return r; })(), amb,
    { repoRoot: path.join(__dirname, '..', '..') }).some(e => e.includes('no spec')));
check('new colliding pair must be listed', has(errorsFor(r => { chip(r, 'bme280').id_probe.expected = ['0x58']; }), 'bme280/bmp280'));
check('stale ambiguity entry rejected', has(errorsFor(() => {}, { pairs: [...amb.pairs, ['bme280', 'bmp280']] }), 'no longer collides'));
check('listed pair is accepted', !has(errorsFor(() => {}), 'collide'));

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
