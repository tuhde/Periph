'use strict';
const { scan, scanDetailed, discover } = require('../../packages/periph/src/discovery/discovery');
const { CHIPS } = require('../../packages/periph/src/discovery/registry');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

function err(code) {
    const e = new Error(code);
    e.code = code;
    return e;
}

// Fake i2c-bus handle. devices: address -> { register: byte } (missing registers read 0xFF).
class FakeBus {
    constructor(devices = {}, { busy = [], noZeroLen = false, broken = false, readError = false } = {}) {
        this.devices = devices;
        this.busy = new Set(busy);
        this.noZeroLen = noZeroLen;
        this.broken = broken;
        this.readError = readError;
        this.calls = [];   // [kind, addr]
        this.writes = [];  // [addr, Buffer]
        this.pointer = {};
    }
    _probe(kind, addr) {
        this.calls.push([kind, addr]);
        if (this.broken) throw err('EIO');
        if (this.busy.has(addr)) throw err('EBUSY');
        if (!(addr in this.devices)) throw err('ENXIO');
    }
    i2cWriteSync(addr, length, buf) {
        if (length === 0) {
            if (this.noZeroLen) throw err('EOPNOTSUPP');
            this._probe('quick', addr);
            return length;
        }
        if (this.readError) throw err('EIO');
        if (!(addr in this.devices)) throw err('ENXIO');
        this.writes.push([addr, Buffer.from(buf)]);
        this.pointer[addr] = [...buf].reduce((a, b) => a * 256 + b, 0);
        return length;
    }
    receiveByteSync(addr) {
        this._probe('read', addr);
        return 0;
    }
    i2cReadSync(addr, length, buf) {
        if (this.readError) throw err('EIO');
        const regs = this.devices[addr];
        for (let i = 0; i < length; i++) buf[i] = regs[this.pointer[addr] + i] ?? 0xff;
        return length;
    }
    closeSync() {}
}

const addrs = list => Object.fromEntries(list.map(a => [a, {}]));
const only = async (bus, opts) => (await discover(bus, opts))[0];

async function main() {
    // --- scan: method per address ---
    let bus = new FakeBus({ 0x76: {}, 0x50: {}, 0x1b: {} });
    checkTrue('scan_finds_all', same(await scan(bus), [0x1b, 0x50, 0x76]));
    const kinds = Object.fromEntries(bus.calls.map(([k, a]) => [a, k]));
    checkTrue('scan_zero_length_write_default', kinds[0x76] === 'quick' && kinds[0x08] === 'quick');
    checkTrue('scan_read_byte_eeprom_ranges', kinds[0x50] === 'read' && kinds[0x30] === 'read' && kinds[0x5f] === 'read');
    checkTrue('scan_skips_reserved', Math.min(...bus.calls.map(c => c[1])) === 0x08 && Math.max(...bus.calls.map(c => c[1])) === 0x77);

    bus = new FakeBus({ 0x76: {} }, { noZeroLen: true });
    await scan(bus);
    checkTrue('scan_fallback_read_byte_when_zero_length_rejected', bus.calls.every(([k]) => k === 'read'));

    bus = new FakeBus({ 0x40: {} }, { busy: [0x42] });
    const detail = await scanDetailed(bus);
    checkTrue('scan_ebusy_is_present', detail.get(0x40) === false && detail.get(0x42) === true && detail.size === 2);

    let threw = false;
    try { await scan(new FakeBus({}, { broken: true })); } catch (e) { threw = true; }
    checkTrue('scan_raises_on_bus_failure', threw);
    checkTrue('scan_empty_bus', same(await scan(new FakeBus()), []));

    // --- identity probes ---
    const bme = { 0xd0: 0x60 };
    let d = await only(new FakeBus({ 0x76: bme }));
    checkTrue('bme280_identified', d.identified === 'bme280' && same(d.candidates, ['bme280']) && d.driver === 'bme280');

    const table = [['bmp280', 0xd0, 0x58, 0x76], ['bme680', 0xd0, 0x61, 0x77], ['bmp384', 0x00, 0x50, 0x76],
        ['mpu6050', 0x75, 0x68, 0x68], ['mpu9250', 0x75, 0x71, 0x68], ['mpu9255', 0x75, 0x73, 0x69],
        ['l3g4200d', 0x0f, 0xd3, 0x68], ['lps33hw', 0x0f, 0xb1, 0x5c], ['adxl345', 0x00, 0xe5, 0x53],
        ['vl53l0x', 0xc0, 0xee, 0x29], ['mfrc522', 0x37, 0x92, 0x28]];
    for (const [id, reg, value, addr] of table) {
        d = await only(new FakeBus({ [addr]: { [reg]: value } }));
        checkTrue('identify_' + id, d.identified === id);
    }
    d = await only(new FakeBus({ 0x52: { 0x00: 0x60, 0x01: 0x01 } }));
    checkTrue('identify_ens160_little_endian', d.identified === 'ens160');
    d = await only(new FakeBus({ 0x40: { 0xff: 0x22, 0x100: 0x60 } }));
    checkTrue('identify_ina226_die_id', d.identified === 'ina226');
    d = await only(new FakeBus({ 0x40: { 0xff: 0x32, 0x100: 0x20 } }));
    checkTrue('identify_ina3221_die_id', d.identified === 'ina3221');
    bus = new FakeBus({ 0x48: { 0x0f: 0x01, 0x10: 0x17 } });
    d = await only(bus);
    checkTrue('tmp117_skipped_pcf8591_write_sensitive', d.identified === null && d.probeSkippedReason === 'write_sensitive_candidate' && bus.writes.length === 0);
    d = await only(new FakeBus({ 0x48: { 0x0f: 0x01, 0x10: 0x17 } }), { active: true });
    checkTrue('identify_tmp117_masked', d.identified === 'tmp117');
    d = await only(new FakeBus({ 0x18: { 0x07: 0x04, 0x08: 0x01 } }));
    checkTrue('identify_mcp9808_masked', d.identified === 'mcp9808');
    bus = new FakeBus({ 0x29: { 0x010f: 0xea, 0x0110: 0xcc } });
    d = await only(bus);
    checkTrue('identify_vl53l1x_two_byte_register', d.identified === 'vl53l1x');
    checkTrue('vl53l1x_register_sent_as_two_bytes', bus.writes.some(([a, b]) => a === 0x29 && b.equals(Buffer.from([0x01, 0x0f]))));
    d = await only(new FakeBus({ 0x1e: { 0x0a: 0x48, 0x0b: 0x34, 0x0c: 0x33 } }));
    checkTrue('hmc5883l_three_byte_id', d.identified === 'hmc5883l');
    for (const [id, value] of [['apds9960', 0xab], ['apds-9930', 0x39]]) {
        d = await only(new FakeBus({ 0x39: { 0x92: value } }), { active: true });
        checkTrue('identify_' + id + '_active', d.identified === id);
    }
    d = await only(new FakeBus({ 0x39: { 0x92: 0xab } }));
    checkTrue('apds_skipped_without_active', d.identified === null && d.probeSkippedReason === 'write_sensitive_candidate');

    // --- ambiguity is a final answer ---
    d = await only(new FakeBus({ 0x5c: { 0x0f: 0xb4 } }));
    checkTrue('lps22df_lps28dfw_ambiguous', d.identified === null && same(d.candidates, ['lps22df', 'lps28dfw']));
    d = await only(new FakeBus({ 0x77: { 0xd0: 0x55 } }));
    checkTrue('bmp085_bmp180_ambiguous', d.identified === null && same(d.candidates, ['bmp085', 'bmp180']));

    // --- no ID match falls back to ID-less candidates ---
    d = await only(new FakeBus({ 0x68: {} }));
    checkTrue('ds3231_pcf8523_remain', d.identified === null && same(d.candidates, ['drv8830', 'ds3231', 'pcf8523']));
    d = await only(new FakeBus({ 0x40: {} }));
    checkTrue('ina219_by_elimination', d.identified === null && same(d.candidates, ['ina219']));
    d = await only(new FakeBus({ 0x36: {} }));
    checkTrue('as5600_sole_candidate_unconfirmed', d.identified === null && same(d.candidates, ['as5600']));
    d = await only(new FakeBus({ 0x0b: {} }));
    checkTrue('unknown_device', d.candidates.length === 0 && d.identified === null);

    // --- write-sensitive gating ---
    bus = new FakeBus({ 0x38: {} });
    d = await only(bus);
    checkTrue('aht21_cands', same(d.candidates, ['ade7953', 'aht21', 'pcf8574', 'pcf8576']));
    checkTrue('no_probe_when_nothing_to_probe', d.probeSkippedReason === null && bus.writes.length === 0);

    const custom = [
        { id: 'pcf-like', driver: null, writeSensitive: true, aliased: false, addresses: [0x20], probe: null },
        { id: 'idchip', driver: null, writeSensitive: false, aliased: false, addresses: [0x20],
            probe: { register: 0x10, regBytes: 1, length: 1, order: 'big', mask: 0xff, expected: [0x42] } },
    ];
    bus = new FakeBus({ 0x20: { 0x10: 0x42 } });
    d = await only(bus, { registry: custom });
    checkTrue('write_sensitive_skips_probe', d.probeSkippedReason === 'write_sensitive_candidate' && bus.writes.length === 0
        && same(d.candidates, ['idchip', 'pcf-like']) && d.identified === null);
    bus = new FakeBus({ 0x20: { 0x10: 0x42 } });
    d = await only(bus, { registry: custom, active: true });
    checkTrue('active_probes_anyway', d.identified === 'idchip' && bus.writes.length > 0);

    // --- kernel-bound ---
    bus = new FakeBus({ 0x76: bme }, { busy: [0x77] });
    const devs = Object.fromEntries((await discover(bus)).map(x => [x.address, x]));
    checkTrue('kernel_bound_reported', devs[0x77].inUseByKernel && devs[0x77].probeSkippedReason === 'kernel_bound' && devs[0x77].identified === null);
    checkTrue('kernel_bound_not_probed', bus.writes.every(([a]) => a !== 0x77));

    // --- failed identity read ---
    d = await only(new FakeBus({ 0x76: bme }, { readError: true }));
    checkTrue('failed_read_is_no_match', d.identified === null && d.candidates.length === 0);

    // --- aliased 24AA02UID ---
    let list = await discover(new FakeBus(addrs([0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57])));
    checkTrue('alias_block_merged', list.length === 1 && list[0].address === 0x50 && same(list[0].candidates, ['24aa02uid'])
        && same(list[0].aliases, [0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57]));
    list = await discover(new FakeBus(addrs([0x50, 0x51])));
    checkTrue('partial_alias_reported_individually', same(list.map(x => x.address), [0x50, 0x51]) && list.every(x => x.aliases.length === 0));

    // --- registry sanity ---
    checkTrue('registry_nonempty', CHIPS.length >= 45);
    checkTrue('registry_ids_unique', new Set(CHIPS.map(c => c.id)).size === CHIPS.length);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
