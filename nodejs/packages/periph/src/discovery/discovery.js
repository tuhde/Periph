'use strict';

// I²C bus auto-discovery for Node.js on Linux (specs/feature_i2c_discovery.md).
//
// scan() enumerates responding addresses; discover() maps them to the chips in
// the generated registry (registry.js, built from registry/chips.json) and
// confirms a chip only when an identity-register read matches exactly one
// candidate. Everything else is reported as candidates.

const i2c = require('i2c-bus');
const { CHIPS } = require('./registry');

const FIRST_ADDRESS = 0x08;
const LAST_ADDRESS = 0x77;

// i2cdetect-compatible policy: EEPROM-class ranges are probed with a read byte.
const READ_BYTE_RANGES = [[0x30, 0x37], [0x50, 0x5f]];

const ABSENT = new Set(['ENXIO', 'EREMOTEIO']);
const UNSUPPORTED = new Set(['EOPNOTSUPP', 'EINVAL', 'ENOTSUP']);
const NO_BUFFER = Buffer.alloc(0);

/**
 * @typedef {Object} DiscoveredDevice
 * @property {number} address - 7-bit address (lowest address of an alias block).
 * @property {string[]} candidates - Registry chip ids that could be here; [] if unknown to the registry.
 * @property {string|null} identified - Chip id confirmed by an identity read, else null.
 * @property {string|null} driver - Driver name of the identified chip, if it has one.
 * @property {boolean} inUseByKernel - True when a kernel driver owns the address (EBUSY).
 * @property {string|null} probeSkippedReason - "kernel_bound", "write_sensitive_candidate" or null.
 * @property {number[]} aliases - Other addresses merged into this device (24AA02UID), else [].
 */

function errorCode(e) {
    if (e && typeof e.code === 'string') return e.code;
    const n = e && Math.abs(Number(e.errno));
    return { 6: 'ENXIO', 16: 'EBUSY', 121: 'EREMOTEIO', 95: 'EOPNOTSUPP', 22: 'EINVAL' }[n] || 'EIO';
}

function openBus(bus) {
    if (typeof bus === 'number') return { handle: i2c.openSync(bus), owned: true };
    return { handle: bus, owned: false };
}

function usesReadByte(addr) {
    return READ_BYTE_RANGES.some(([lo, hi]) => addr >= lo && addr <= hi);
}

// A zero-length write is the closest i2c-bus offers to a quick write; adapters
// that reject it fall back to a read byte (always acceptable, spec section 3.2).
function probeAddress(handle, addr) {
    if (!usesReadByte(addr)) {
        try {
            handle.i2cWriteSync(addr, 0, NO_BUFFER);
            return;
        } catch (e) {
            if (!UNSUPPORTED.has(errorCode(e))) throw e;
        }
    }
    handle.receiveByteSync(addr);
}

/**
 * Scan a bus and report which responding addresses are kernel-bound.
 * @param {number|object} bus - Bus number (opens /dev/i2c-N) or an open i2c-bus handle.
 * @param {number} [first=0x08]
 * @param {number} [last=0x77]
 * @returns {Promise<Map<number, boolean>>} address -> inUseByKernel for every address that responded.
 * @throws {Error} If every probed address failed with something other than a NACK.
 */
async function scanDetailed(bus, first = FIRST_ADDRESS, last = LAST_ADDRESS) {
    const { handle, owned } = openBus(bus);
    try {
        const found = new Map();
        let errors = 0;
        let lastError = null;
        for (let addr = first; addr <= last; addr++) {
            try {
                probeAddress(handle, addr);
                found.set(addr, false);
            } catch (e) {
                const code = errorCode(e);
                if (code === 'EBUSY') found.set(addr, true);
                else if (!ABSENT.has(code)) {
                    errors++;
                    lastError = e;
                }
            }
        }
        if (errors > 0 && errors === last - first + 1) throw lastError;
        return found;
    } finally {
        if (owned) handle.closeSync();
    }
}

/**
 * Return the sorted 7-bit addresses that respond on the bus.
 * @param {number|object} bus - Bus number or an open i2c-bus handle.
 * @param {number} [first=0x08]
 * @param {number} [last=0x77]
 * @returns {Promise<number[]>}
 */
async function scan(bus, first = FIRST_ADDRESS, last = LAST_ADDRESS) {
    return [...(await scanDetailed(bus, first, last)).keys()].sort((a, b) => a - b);
}

// Writes the register address (big-endian, probe.regBytes bytes), then reads.
// Uses STOP between the two phases: i2c-bus has no synchronous repeated-start
// transfer, and identity registers are readable that way on every chip in the registry.
function readIdentity(handle, addr, probe, cache) {
    const key = `${probe.register}:${probe.regBytes}:${probe.length}:${probe.order}`;
    if (!cache.has(key)) {
        let value = null;
        try {
            const reg = Buffer.alloc(probe.regBytes);
            for (let i = probe.regBytes - 1, r = probe.register; i >= 0; i--, r = Math.floor(r / 256)) reg[i] = r & 0xff;
            handle.i2cWriteSync(addr, reg.length, reg);
            const data = Buffer.alloc(probe.length);
            handle.i2cReadSync(addr, probe.length, data);
            value = 0;
            for (let i = 0; i < probe.length; i++) {
                const b = data[probe.order === 'little' ? probe.length - 1 - i : i];
                value = value * 256 + b;
            }
        } catch (e) {
            value = null;
        }
        cache.set(key, value);
    }
    return { key, value: cache.get(key) };
}

function sortedIds(chips) {
    return chips.map(c => c.id).sort();
}

function device(address, props) {
    return Object.assign({
        address, candidates: [], identified: null, driver: null,
        inUseByKernel: false, probeSkippedReason: null, aliases: [],
    }, props);
}

function classify(handle, addr, cands, inUse, active) {
    if (cands.length === 0) return device(addr, { inUseByKernel: inUse });
    const ids = sortedIds(cands);
    if (inUse) return device(addr, { candidates: ids, inUseByKernel: true, probeSkippedReason: 'kernel_bound' });
    const probed = cands.filter(c => c.probe);
    if (probed.length === 0) return device(addr, { candidates: ids });
    if (!active && cands.some(c => c.writeSensitive)) {
        return device(addr, { candidates: ids, probeSkippedReason: 'write_sensitive_candidate' });
    }

    const cache = new Map();
    const matched = [];
    for (const c of probed) {
        const { value } = readIdentity(handle, addr, c.probe, cache);
        // >>> 0 keeps the AND unsigned for 32-bit masks.
        if (value !== null && c.probe.expected.includes((value & c.probe.mask) >>> 0)) matched.push(c);
    }
    if (matched.length === 1) {
        return device(addr, { candidates: [matched[0].id], identified: matched[0].id, driver: matched[0].driver });
    }
    if (matched.length > 1) return device(addr, { candidates: sortedIds(matched) });
    return device(addr, { candidates: sortedIds(cands.filter(c => !c.probe)) });
}

/**
 * Scan the bus and name the chips that are connected.
 *
 * identified is set only when an identity-register read matches exactly one
 * chip; otherwise candidates lists what the address could be. Addresses with a
 * write-sensitive candidate are not probed unless active is true.
 *
 * @param {number|object} bus - Bus number or an open i2c-bus handle.
 * @param {Object} [options]
 * @param {boolean} [options.active=false] - Also probe addresses that have write-sensitive candidates.
 * @param {Array} [options.registry] - Chip table in the registry.js CHIPS format (default: built in).
 * @returns {Promise<DiscoveredDevice[]>} Sorted by address.
 */
async function discover(bus, { active = false, registry = CHIPS } = {}) {
    const { handle, owned } = openBus(bus);
    try {
        const present = await scanDetailed(handle);
        const byAddr = new Map();
        for (const chip of registry) {
            for (const a of chip.addresses) {
                if (!byAddr.has(a)) byAddr.set(a, []);
                byAddr.get(a).push(chip);
            }
        }

        const devices = [];
        const merged = new Set();
        for (const chip of registry) {
            if (!chip.aliased) continue;
            if (chip.addresses.every(a => present.has(a))) {
                devices.push(device(chip.addresses[0], {
                    candidates: [chip.id],
                    inUseByKernel: chip.addresses.some(a => present.get(a)),
                    aliases: chip.addresses.slice(1),
                }));
                chip.addresses.forEach(a => merged.add(a));
            }
        }
        for (const addr of [...present.keys()].sort((a, b) => a - b)) {
            if (!merged.has(addr)) devices.push(classify(handle, addr, byAddr.get(addr) || [], present.get(addr), active));
        }
        return devices.sort((a, b) => a.address - b.address);
    } finally {
        if (owned) handle.closeSync();
    }
}

module.exports = { scan, scanDetailed, discover, FIRST_ADDRESS, LAST_ADDRESS };
