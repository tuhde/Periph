'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MPL3115A2Minimal, MPL3115A2Full } = require('../../packages/periph/src/chips/pressure/mpl3115a2');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-6) => Math.abs(a - b) <= tol;

async function rejects(fn) {
    try { await fn(); } catch (e) { return true; }
    return false;
}

// RST (0x04) and OST (0x02) are self-clearing in CTRL_REG1 on the real chip.
class Mock extends I2CConnectionMock {
    async write(data) {
        const r = await super.write(data);
        const b = Buffer.from(data);
        if (b.length === 2 && b[0] === 0x26) this.registers.set(0x26, b[1] & ~0x06 & 0xFF);
        return r;
    }
}

function make() {
    const c = new Mock();
    c.setRegister(0x0C, [0xC4]);
    c.setRegister(0x06, [0x0E]);
    c.setRegister(0x01, [0x62, 0xF3, 0x80, 0x19, 0x80]);   // 101326.0 Pa, 25.5 C
    return [c, new MPL3115A2Full(c)];
}

function last(c, reg, idx = 1) {
    let v = -1;
    for (const w of c.writes) if (w.length > idx && w[0] === reg) v = w[idx];
    return v;
}

async function main() {
    let [c, s] = make();
    checkTrue('constructor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_reset_written', c.writes.find((w) => w[0] === 0x26)[1] === 0x04);
    checkTrue('init_pt_data_cfg', last(c, 0x13) === 0x07);
    checkTrue('init_ctrl1_default', last(c, 0x26) === 0x38);

    const m = await s.read();
    checkTrue('read_pressure', close(m.pressure_pa, 101326.0));
    checkTrue('read_temperature', close(m.temperature_c, 25.5));
    checkTrue('read_sets_ost', last(c, 0x26) === 0x3A);
    c.setRegister(0x04, [0xE7, 0x00]);
    checkTrue('negative_temperature', close(await s.readTemperature(), -25.0));
    c.setRegister(0x04, [0x19, 0x80]);

    c.setRegister(0x01, [0x00, 0x7B, 0x30]);
    checkTrue('altitude_value', close(await s.readAltitude(), 123.1875));
    checkTrue('altitude_sets_alt_bit', last(c, 0x26) === 0xBA);
    c.setRegister(0x01, [0xFF, 0x9C, 0x00]);
    checkTrue('altitude_negative', close(await s.readAltitude(), -100.0));
    c.setRegister(0x01, [0x62, 0xF3, 0x80]);
    checkTrue('read_switches_back_to_barometer', close((await s.read()).pressure_pa, 101326.0));

    await s.setOversample(16);
    checkTrue('oversample', s.getOversample() === 16 && last(c, 0x26) === 0x20);
    checkTrue('oversample_invalid', await rejects(() => s.setOversample(3)));
    await s.setOversample(128);

    await s.setSeaLevelPressure(101326);
    checkTrue('bar_in', last(c, 0x14, 1) === 0xC5 && last(c, 0x14, 2) === 0xE7);

    await s.setOffsets(-8, 1, -3);
    checkTrue('offsets', last(c, 0x2B) === 0xFE && last(c, 0x2C) === 16 && last(c, 0x2D) === 0xFD);
    checkTrue('offset_range', await rejects(() => s.setOffsets(1000)));

    await s.setTarget(100000, 200, -5, 3);
    checkTrue('p_tgt', last(c, 0x16, 1) === 0xC3 && last(c, 0x16, 2) === 0x50);
    checkTrue('t_tgt', last(c, 0x18) === 0xFB);
    checkTrue('p_wnd', last(c, 0x19, 1) === 0x00 && last(c, 0x19, 2) === 0x64);
    checkTrue('t_wnd', last(c, 0x1B) === 3);

    await s.startContinuous(2);
    checkTrue('continuous_st', (last(c, 0x27) & 0x0F) === 2);
    checkTrue('continuous_sbyb', last(c, 0x26) === 0x39);
    checkTrue('continuous_range', await rejects(() => s.startContinuous(16)));
    checkTrue('data_ready', await s.dataReady());
    const rc = await s.readContinuous();
    checkTrue('read_continuous', close(rc.value, 101326.0) && close(rc.temperature_c, 25.5));
    await s.stopContinuous();
    checkTrue('continuous_stop', last(c, 0x26) === 0x38);

    c.setRegister(0x07, [0x00, 0x00, 0x40, 0x00, 0x10]);
    const [dv, dt] = await s.readDelta();
    checkTrue('delta', close(dv, 1.0) && close(dt, 0.0625));

    c.setRegister(0x1C, [0x62, 0xF3, 0x80, 0x19, 0x80]);
    const mm = await s.readMinMax();
    checkTrue('min_max', close(mm.p_min, 101326.0));
    await s.clearMinMax();
    checkTrue('clear_min_max', last(c, 0x1C, 3) === 0);

    await s.fifoSetup(1, 16);
    checkTrue('fifo_setup', last(c, 0x0F) === 0x50);
    checkTrue('fifo_switch_rejected', await rejects(() => s.fifoSetup(2)));
    c.setRegister(0x0D, [0xC2]);
    checkTrue('fifo_status', JSON.stringify(await s.fifoStatus()) === '[true,true,2]');
    c.setRegister(0x0E, [0x62, 0xF3, 0x80, 0x19, 0x80]);
    const samples = await s.fifoRead();
    checkTrue('fifo_read', samples.length === 2 && close(samples[0][0], 101326.0));
    await s.fifoSetup(0);

    await s.setInterruptPins(true, true);
    checkTrue('int_pins', last(c, 0x28) === 0x30);
    await s.enableInterrupt(MPL3115A2Full.SOURCE_TTH, 1);
    checkTrue('int_enable', (last(c, 0x29) & 0x04) !== 0);
    checkTrue('int_route_int1', (last(c, 0x2A) & 0x04) !== 0);
    await s.enableInterrupt(MPL3115A2Full.SOURCE_DRDY, 2);
    checkTrue('int_route_int2', (last(c, 0x2A) & 0x80) === 0);
    await s.disableInterrupt(MPL3115A2Full.SOURCE_TTH);
    checkTrue('int_disable', (last(c, 0x29) & 0x04) === 0);
    c.setRegister(0x12, [0x84]);
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 0x84);
    checkTrue('int_pin_invalid', await rejects(() => s.enableInterrupt(1, 3)));

    // on_interrupt via the polling timer: INT1 routing = TTH only
    c.setRegister(0x2A, [0x04]);
    let got = -1;
    await s.onInterrupt((status) => { got = status; }, { pin: 1 });
    await new Promise((r) => setTimeout(r, 250));
    await s.offInterrupt();
    checkTrue('on_interrupt_filters_by_route', got === 0x04);

    checkTrue('who_am_i', (await s.whoAmI()) === 0xC4);
    c.setRegister(0x11, [0x01]);
    checkTrue('is_active', await s.isActive());
    await s.reset();
    checkTrue('reset_restores_config', last(c, 0x26) === 0x38);

    const bad = new Mock();
    bad.setRegister(0x0C, [0x00]);
    checkTrue('init_bad_id', await rejects(() => new MPL3115A2Minimal(bad).init()));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
