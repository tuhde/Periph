'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LM25066Minimal, LM25066Full } = require('../../packages/periph/src/chips/power/lm25066');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-3) => Math.abs(a - b) < tol;

// One byte string per PMBus command; every write is logged as [cmd, data].
class CmdMock extends I2CConnectionMock {
    constructor() { super(); this.cmds = new Map(); this.log = []; }
    word(cmd, v) { this.cmds.set(cmd, Buffer.from([v & 0xFF, (v >> 8) & 0xFF])); }
    byte(cmd, v) { this.cmds.set(cmd, Buffer.from([v])); }
    block(cmd, words) {
        const d = [12];
        for (const w of words) d.push(w & 0xFF, (w >> 8) & 0xFF);
        this.cmds.set(cmd, Buffer.from(d));
    }
    async readReg(reg, length) {
        const out = Buffer.alloc(length);
        const d = this.cmds.get(reg);
        if (d) d.copy(out, 0, 0, Math.min(length, d.length));
        return out;
    }
    async writeReg(reg, data) {
        const buf = Buffer.isBuffer(data) ? data : Buffer.from(typeof data === 'number' ? [data] : data);
        this.log.push([reg, Array.from(buf)]);
        if (buf.length) this.cmds.set(reg, Buffer.from(buf));
    }
}

const lastIs = (c, cmd, bytes) => {
    const l = c.log[c.log.length - 1];
    return !!l && l[0] === cmd && JSON.stringify(l[1]) === JSON.stringify(bytes);
};

async function throws(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}

async function main() {
    const conn = new CmdMock();
    const m = new LM25066Minimal(conn, 5.0, false);
    checkTrue('init_no_writes', conn.log.length === 0);

    conn.word(0x88, 2000);
    checkTrue('vin', near(await m.voltageIn(), 201800 / 22070));
    conn.word(0x8B, 0xF000 | 1500);
    checkTrue('vout', near(await m.voltageOut(), 151800 / 22070));
    conn.word(0xD1, 648);
    checkTrue('current_cl_gnd', near(await m.current(), 70000 / 68305));
    conn.word(0xD2, 1000);
    checkTrue('power_cl_gnd', near(await m.power(), 103300 / (736 * 5)));
    conn.word(0x8D, 0x0190);
    checkTrue('temp_25', near(await m.temperature(), 25));
    conn.word(0x8D, 0xFF00);
    checkTrue('temp_negative', near(await m.temperature(), -16));

    const mh = new LM25066Minimal(conn, 5.0, true);
    conn.word(0xD1, 648);
    checkTrue('current_cl_vdd', near(await mh.current(), (64800 + 3100) / (6854 * 5)));
    conn.word(0xD2, 1000);
    checkTrue('power_cl_vdd', near(await mh.power(), (100000 + 1900) / (369 * 5)));

    const c2 = new CmdMock();
    const f = new LM25066Full(c2, 1.0, false);
    c2.word(0xD0, 3546 - 3);
    checkTrue('vaux', near(await f.voltageAux(), 1.0));

    c2.block(0xDA, [0x0460, 648, 1500, 2000, 1000, 0x0190]);
    const b = await f.readBlock();
    checkTrue('block_diag', b.diagnostic === 0x0460);
    checkTrue('block_current', near(b.current, 70000 / 13661));
    checkTrue('block_vout', near(b.voltageOut, 151800 / 22070));
    checkTrue('block_vin', near(b.voltageIn, 201800 / 22070));
    checkTrue('block_power', near(b.power, 103300 / 736));
    checkTrue('block_temp', near(b.temperature, 25));
    c2.block(0xE2, [0, 0, 0, 0, 0, 0]);
    checkTrue('avg_block', near((await f.readAverage()).voltageIn, 1800 / 22070));
    c2.block(0xE0, [1, 0, 0, 0, 0, 0]);
    checkTrue('black_box', (await f.blackBox()).diagnostic === 1);
    c2.cmds.set(0xDA, Buffer.concat([Buffer.from([5]), Buffer.alloc(12)]));
    checkTrue('block_count_checked', await throws(() => f.readBlock()));

    c2.word(0xDC, 2000);
    checkTrue('vin_avg', near(await f.voltageInAvg(), 201800 / 22070));
    c2.word(0xD5, 1000);
    checkTrue('power_peak', near(await f.powerPeak(), 103300 / 736));
    c2.log.length = 0;
    await f.clearPowerPeak();
    checkTrue('clear_peak', lastIs(c2, 0xD6, []));

    await f.setAveraging(6);
    checkTrue('set_avg', lastIs(c2, 0xDB, [6]));
    checkTrue('avg_range', await throws(() => f.setAveraging(13)));
    checkTrue('get_avg', (await f.getAveraging()) === 6);

    await f.setVinUvWarn(10.0);
    checkTrue('vin_uv_warn_write', lastIs(c2, 0x58, [0x8D, 0x08]));
    await f.setVinUvWarn(null);
    checkTrue('vin_uv_disable', lastIs(c2, 0x58, [0, 0]));
    await f.setVinOvWarn(null);
    checkTrue('vin_ov_disable', lastIs(c2, 0x57, [0xFF, 0x0F]));
    await f.setOtFault(150);
    checkTrue('ot_fault_150', lastIs(c2, 0x4F, [0x60, 0x09]));
    await f.setOtWarn(125);
    checkTrue('ot_warn_125', lastIs(c2, 0x51, [0xD0, 0x07]));
    await f.setPinWarn(1e9);
    checkTrue('clamp', lastIs(c2, 0xD4, [0xFE, 0x0F]));
    await f.setIinWarn(1.02);
    const y = Math.round((13661 * 1.02 - 5200) / 100);
    checkTrue('iin_warn', lastIs(c2, 0xD3, [y & 0xFF, y >> 8]));

    for (const [cmd, v] of [[0x57, 0x0FFF], [0x58, 0], [0x43, 0], [0x4F, 0x0960], [0x51, 0x07D0], [0xD3, 0x0FFF], [0xD4, 0x0FFF]]) c2.word(cmd, v);
    const lim = await f.getLimits();
    checkTrue('limits_disabled', lim.vinOvWarn === null && lim.vinUvWarn === null && lim.iinWarn === null &&
        lim.pinWarn === null && lim.voutUvWarn === null);
    checkTrue('limits_ot', near(lim.otFault, 150) && near(lim.otWarn, 125));

    c2.byte(0x78, 0x49); c2.word(0x79, 0x3849); c2.byte(0x7A, 0x20); c2.byte(0x7C, 0x10);
    c2.byte(0x7D, 0x80); c2.byte(0x7E, 0x02); c2.byte(0x80, 0x10); c2.word(0xE1, 0x0460);
    checkTrue('status', (await f.statusByte()) === 0x49 && (await f.statusWord()) === 0x3849);
    checkTrue('status_groups', (await f.statusVout()) === 0x20 && (await f.statusInput()) === 0x10 &&
        (await f.statusTemperature()) === 0x80 && (await f.statusCml()) === 0x02 && (await f.statusMfr()) === 0x10);
    checkTrue('diagnostic', (await f.diagnosticWord()) === 0x0460 && (await f.pollInterrupt()) === 0x0460);

    c2.log.length = 0;
    await f.clearFaults();
    checkTrue('clear_faults', lastIs(c2, 0x03, []));
    await f.operationOn();
    checkTrue('operation_on', lastIs(c2, 0x01, [0x80]));
    await f.operationOff();
    checkTrue('operation_off', lastIs(c2, 0x01, [0x00]));
    c2.byte(0x01, 0x80); c2.byte(0x78, 0x00);
    checkTrue('is_on', await f.isOn());
    c2.byte(0x78, 0x40);
    checkTrue('is_off_status', !(await f.isOn()));

    await f.configureDevice({ retryCode: 7, currentLimitHigh: true, cbRatioHigh: false });
    checkTrue('device_setup', lastIs(c2, 0xD9, [0xE0 | 0x04 | 0x10 | 0x02]));
    c2.word(0xD1, 648);
    checkTrue('coeff_updated', near(await f.current(), 67900 / 6854));
    checkTrue('retry_range', await throws(() => f.configureDevice({ retryCode: 8 })));
    c2.byte(0xD9, 0x16);
    checkTrue('get_device_setup', (await f.deviceSetup()) === 0x16);

    await f.setGateMask(0x04);
    checkTrue('gate_mask', lastIs(c2, 0xD7, [0x04]));
    checkTrue('gate_mask_invalid', await throws(() => f.setGateMask(0x01)));
    checkTrue('get_gate_mask', (await f.getGateMask()) === 0x04);

    c2.byte(0x19, 0xB0);
    checkTrue('capability', (await f.capability()) === 0xB0);
    c2.cmds.set(0x99, Buffer.from([3, 0x4E, 0x53, 0x43]));
    c2.cmds.set(0x9A, Buffer.from([8, ...Buffer.from('LM25066'), 0]));
    c2.cmds.set(0x9B, Buffer.from([2, 0x41, 0x41]));
    checkTrue('mfr_id', (await f.manufacturerId()) === 'NSC');
    checkTrue('model', (await f.model()) === 'LM25066');
    checkTrue('revision', (await f.revision()) === 'AA');

    c2.word(0xD8, 0x0820);
    await f.enableInterrupt(LM25066Full.SOURCE_VIN_UV_FAULT);
    checkTrue('enable_irq', lastIs(c2, 0xD8, [0x00, 0x08]));
    await f.disableInterrupt(LM25066Full.SOURCE_VIN_UV_WARN);
    checkTrue('disable_irq', lastIs(c2, 0xD8, [0x00, 0x28]));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
