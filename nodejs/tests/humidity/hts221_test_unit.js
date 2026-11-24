'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HTS221Full, HTS221Minimal } = require('../../packages/periph/src/chips/humidity/hts221');

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

/** Mock that clears CTRL_REG2.BOOT like the real chip. */
class ChipMock extends I2CConnectionMock {
    async write(data) {
        await super.write(data);
        if (data.length === 2 && data[0] === 0x21) this.registers.set(0x21, data[1] & ~0x80);
    }
}

function make(Cls = I2CConnectionMock) {
    const c = new Cls();
    // Calibration block at 0xB0: H0_rH_x2=40, H1_rH_x2=80, T0_x8=80, T1_x8=160, msb=0,
    // H0_T0_OUT=0x4000, H1_T0_OUT=0x6000, T0_OUT=300, T1_OUT=500
    c.setRegister(0xB0, [40, 80, 80, 160, 0, 0, 0x00, 0x40, 0, 0, 0x00, 0x60, 300 & 0xFF, 300 >> 8, 500 & 0xFF, 500 >> 8]);
    c.setRegister(0x0F, [0xBC]);
    c.setRegister(0x27, [0x03]);
    // Burst 0xA8: H_OUT = 0x5000 (30 %rH), T_OUT = 400 (15.0 degC)
    c.setRegister(0xA8, [0x00, 0x50, 400 & 0xFF, 400 >> 8]);
    return c;
}

const wrote = (c, bytes) => c.writes.some((w) => JSON.stringify([...w]) === JSON.stringify(bytes));

async function main() {
    let c = make();
    let s = new HTS221Minimal(c);
    checkTrue('ctor_no_bus_access', c.writes.length === 0);
    await s.init();
    checkTrue('init_reads_calibration', wrote(c, [0xB0]));
    checkTrue('init_writes_ctrl_reg1', JSON.stringify([...c.writes[c.writes.length - 1]]) === JSON.stringify([0x20, 0x84]));
    const m = await s.read();
    checkTrue('read_humidity', close(m.humidity_pct, 30.0));
    checkTrue('read_temperature', close(m.temperature_c, 15.0));
    checkTrue('read_starts_one_shot', wrote(c, [0x21, 0x01]));

    // clamping
    let c4 = make();
    c4.setRegister(0xB6, [0x00, 0x00, 0, 0, 0x00, 0x10]);   // H0_T0_OUT=0, H1_T0_OUT=0x1000
    let s4 = new HTS221Minimal(c4);
    await s4.init();
    c4.setRegister(0xA8, [0xFF, 0x7F, 0, 0]);
    checkTrue('humidity_clamped_100', close((await s4.read()).humidity_pct, 100.0));
    c.setRegister(0xA8, [0x00, 0x80, 0, 0]);
    checkTrue('humidity_clamped_0', close((await s.read()).humidity_pct, 0.0));

    // timeout
    let c2 = make();
    c2.setRegister(0x27, [0x00]);
    let s2 = new HTS221Minimal(c2);
    await s2.init();
    checkTrue('read_timeout', await rejects(() => s2.read()));

    // calibration T bits 9:8
    let c3 = make();
    c3.setRegister(0xB5, [0x05]);
    let f3 = new HTS221Full(c3);
    await f3.init();
    let cal3 = await f3.readCalibration();
    checkTrue('calib_t0_msb', close(cal3.T0_degC, (256 + 80) / 8.0));
    checkTrue('calib_t1_msb', close(cal3.T1_degC, (256 + 160) / 8.0));
    c3.setRegister(0xB5, [0x0C]);
    f3 = new HTS221Full(c3);
    await f3.init();
    checkTrue('calib_t1_bit9_8', close((await f3.readCalibration()).T1_degC, (0x300 + 160) / 8.0));

    c = make(ChipMock);
    const f = new HTS221Full(c);
    await f.init();
    checkTrue('inherits_minimal', f instanceof HTS221Minimal);
    checkTrue('read_humidity_full', close(await f.readHumidity(), 30.0));
    checkTrue('read_temperature_full', close(await f.readTemperature(), 15.0));
    checkTrue('read_raw_humidity', (await f.readRawHumidity()) === 0x5000);
    checkTrue('read_raw_temperature', (await f.readRawTemperature()) === 400);
    checkTrue('read_continuous', close((await f.readContinuous()).temperature_c, 15.0));
    const cal = await f.readCalibration();
    checkTrue('calib_h', close(cal.H0_rH, 20.0) && close(cal.H1_rH, 40.0));
    checkTrue('calib_t', close(cal.T0_degC, 10.0) && close(cal.T1_degC, 20.0));
    checkTrue('calib_out', cal.H0_T0_OUT === 0x4000 && cal.H1_T0_OUT === 0x6000 && cal.T0_OUT === 300 && cal.T1_OUT === 500);
    checkTrue('who_am_i', (await f.whoAmI()) === 0xBC);
    checkTrue('status', (await f.status()) === 0x03);
    checkTrue('data_ready', await f.dataReady());
    checkTrue('poll_interrupt', (await f.pollInterrupt()) === 0x03);

    c.setRegister(0xA8, [0x00, 0xF0, 0x00, 0x00]);
    checkTrue('raw_negative', (await f.readRawHumidity()) === -4096);
    c.setRegister(0xA8, [0x00, 0x50, 400 & 0xFF, 400 >> 8]);

    const reg = (r) => c.registers.get(r);
    await f.setOutputDataRate(12.5);
    checkTrue('odr_12_5', reg(0x20) === 0x87);
    checkTrue('get_odr', (await f.getOutputDataRate()) === 12.5);
    await f.setOutputDataRate(7);
    checkTrue('odr_7', reg(0x20) === 0x86);
    await f.setOutputDataRate(1);
    checkTrue('odr_1', reg(0x20) === 0x85);
    await f.setOutputDataRate(0);
    checkTrue('odr_0', reg(0x20) === 0x84);
    checkTrue('odr_invalid', await rejects(() => f.setOutputDataRate(5)));

    await f.setAveraging(256, 512);
    checkTrue('averaging_max', reg(0x10) === 0x3F);
    checkTrue('get_averaging', JSON.stringify(await f.getAveraging()) === '[256,512]');
    await f.setAveraging(2, 4);
    checkTrue('averaging_min', reg(0x10) === 0x00);
    await f.setAveraging(16, 32);
    checkTrue('averaging_default', reg(0x10) === 0x1B);
    checkTrue('get_averaging_default', JSON.stringify(await f.getAveraging()) === '[16,32]');
    for (const [t, h] of [[3, 32], [16, 2], [1, 32], [16, 1024]]) {
        checkTrue('averaging_invalid_' + t + '_' + h, await rejects(() => f.setAveraging(t, h)));
    }

    await f.setBlockDataUpdate(false);
    checkTrue('bdu_off', reg(0x20) === 0x80);
    await f.setBlockDataUpdate(true);
    checkTrue('bdu_on', reg(0x20) === 0x84);
    await f.powerDown();
    checkTrue('power_down', reg(0x20) === 0x04);
    await f.powerUp();
    checkTrue('power_up', reg(0x20) === 0x84);

    await f.setHeater(true);
    checkTrue('heater_on', (reg(0x21) & 0x02) !== 0 && (await f.isHeaterOn()));
    await f.setHeater(false);
    checkTrue('heater_off', !(await f.isHeaterOn()));

    await f.configureDrdy(true);
    checkTrue('drdy_default', reg(0x22) === 0x04);
    await f.configureDrdy(true, false, true);
    checkTrue('drdy_low_od', reg(0x22) === 0xC4);
    await f.configureDrdy(false);
    checkTrue('drdy_off', reg(0x22) === 0x00);

    c.setRegister(0xB0, [60]);
    await f.reboot();
    checkTrue('reboot_sets_boot', c.writes.some((w) => w.length === 2 && w[0] === 0x21 && (w[1] & 0x80)));
    checkTrue('reboot_rereads_calibration', close((await f.readCalibration()).H0_rH, 30.0));
    c.setRegister(0xB0, [40]);
    await f.reboot();

    const g = Math.log(0.30) + 17.62 * 15.0 / (243.12 + 15.0);
    checkTrue('dew_point', close(await f.dewPoint(), 243.12 * g / (17.62 - g), 1e-9));

    // reboot timeout with a mock that never clears BOOT
    let c5 = make();
    let f5 = new HTS221Full(c5);
    await f5.init();
    checkTrue('reboot_timeout', await rejects(() => f5.reboot()));

    // interrupt via pin
    const pin = {
        handler: null, trigger: null,
        async onEdge(h, t) { this.handler = h; this.trigger = t; },
        async offEdge(h) { if (this.handler === h) this.handler = null; },
    };
    const got = [];
    await f.onInterrupt((st) => got.push(st), pin);
    checkTrue('interrupt_drdy_enabled', reg(0x22) === 0x04);
    checkTrue('interrupt_rising', pin.trigger === 'rising');
    await pin.handler();
    await new Promise((r) => setTimeout(r, 10));
    checkTrue('interrupt_callback', got.length === 1 && got[0] === 0x03);
    await f.offInterrupt();
    checkTrue('interrupt_unsubscribed', pin.handler === null);
    checkTrue('interrupt_drdy_disabled', reg(0x22) === 0x00);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
