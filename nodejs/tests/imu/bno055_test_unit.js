'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { BNO055Full, BNO055Minimal, BNO055Source, BNO055Mode } = require('../../packages/periph/src/chips/imu/bno055');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-4) => Math.abs(a - b) <= tol;
const same = (a, b) => a.length === b.length && a.every((v, i) => close(v, b[i]));

// Encode signed 16-bit values as little-endian bytes.
function le(...vals) {
    const out = [];
    for (const v of vals) { const u = v & 0xFFFF; out.push(u & 0xFF, u >> 8); }
    return out;
}

function regWrites(conn, reg) {
    return conn.writes.filter((w) => w.length === 2 && w[0] === reg).map((w) => w[1]);
}

async function make(full = true) {
    const conn = new I2CConnectionMock();
    conn.setRegister(0x00, [0xA0, 0xFB, 0x32, 0x0F, 0x11, 0x03, 0x15]);
    const chip = full ? new BNO055Full(conn) : new BNO055Minimal(conn);
    await chip.init();
    return [conn, chip];
}

async function main() {
    // init sequence
    const [c0, m] = await make(false);
    const w2 = c0.writes.filter((w) => w.length === 2).map((w) => Buffer.from(w).toString('hex'));
    checkTrue('init_pwr_mode', w2.includes('3e00'));
    checkTrue('init_page0', w2.includes('0700'));
    checkTrue('init_unit_sel', w2.includes('3b86'));
    checkTrue('init_opr_ndof', w2[w2.length - 1] === '3d0c');

    // wrong chip id
    const bad = new I2CConnectionMock();
    bad.setRegister(0x00, [0x55]);
    let threw = false;
    try { await new BNO055Minimal(bad).init(); } catch (e) { threw = true; }
    checkTrue('bad_chip_id_rejects', threw);

    // euler
    c0.setRegister(0x1A, le(1414, -900, 450));
    const [h, r, p] = await m.euler();
    checkTrue('euler', close(h, 1414 / 900) && close(r, -1) && close(p, 0.5));
    checkTrue('minimal_no_full_api', typeof m.quaternion === 'undefined');

    const [c, s] = await make();

    c.setRegister(0x20, le(16384, -8192, 0, 4096));
    checkTrue('quaternion', same(await s.quaternion(), [1, -0.5, 0, 0.25]));
    c.setRegister(0x28, le(100, -250, 981));
    checkTrue('linear_acceleration', same(await s.linearAcceleration(), [1, -2.5, 9.81]));
    c.setRegister(0x2E, le(0, 0, 981));
    checkTrue('gravity', same(await s.gravity(), [0, 0, 9.81]));
    c.setRegister(0x08, le(-100, 200, 300));
    checkTrue('acceleration', same(await s.acceleration(), [-1, 2, 3]));
    c.setRegister(0x0E, le(160, -32, 16));
    checkTrue('magnetic_field', same(await s.magneticField(), [10, -2, 1]));
    c.setRegister(0x14, le(900, 0, -450));
    checkTrue('angular_rate', same(await s.angularRate(), [1, 0, -0.5]));
    c.setRegister(0x34, [0xE7]);
    checkTrue('temperature_negative', (await s.temperature()) === -25);
    c.setRegister(0x34, [23]);
    checkTrue('temperature', (await s.temperature()) === 23);

    // calibration status
    c.setRegister(0x35, [0xE4]);
    checkTrue('calibration_status', same(await s.calibrationStatus(), [3, 2, 1, 0]));
    checkTrue('not_calibrated', !(await s.isCalibrated()));
    c.setRegister(0x35, [0xFF]);
    checkTrue('is_calibrated', await s.isCalibrated());

    // modes
    c.writes.length = 0;
    await s.setMode(BNO055Mode.IMU);
    checkTrue('set_mode_sequence', c.writes.length === 2 && c.writes[0][1] === 0x00 && c.writes[1][1] === 0x08);
    c.setRegister(0x3D, [0x08]);
    checkTrue('get_mode', (await s.getMode()) === 8);
    let threw2 = false;
    try { await s.setMode(13); } catch (e) { threw2 = true; }
    checkTrue('set_mode_range', threw2);
    await s.setMode(BNO055Mode.NDOF);

    // calibration blob
    const blob = Buffer.from(Array.from({ length: 22 }, (_, i) => i + 1));
    c.setRegister(0x55, [...blob]);
    c.writes.length = 0;
    checkTrue('get_calibration', (await s.getCalibration()).equals(blob));
    checkTrue('get_calibration_config', c.writes[0][1] === 0x00 && c.writes[c.writes.length - 1][1] === 0x0C);
    c.writes.length = 0;
    await s.setCalibration(blob);
    const dw = c.writes.filter((w) => w[0] >= 0x55 && w[0] <= 0x6A);
    checkTrue('set_calibration', dw.length === 22 && dw.every((w, i) => w[0] === 0x55 + i && w[1] === blob[i]));
    checkTrue('set_calibration_restores_mode', c.writes[c.writes.length - 1][0] === 0x3D && c.writes[c.writes.length - 1][1] === 0x0C);
    let threw3 = false;
    try { await s.setCalibration(Buffer.alloc(21)); } catch (e) { threw3 = true; }
    checkTrue('set_calibration_len', threw3);

    // configuration
    c.writes.length = 0;
    await s.setAxisRemap(0x21, 0x02);
    checkTrue('axis_remap', regWrites(c, 0x41)[0] === 0x21 && regWrites(c, 0x42)[0] === 0x02);
    checkTrue('axis_remap_restores', same(regWrites(c, 0x3D), [0x00, 0x0C]));
    c.writes.length = 0;
    await s.setPowerMode('low_power');
    checkTrue('power_mode', same(regWrites(c, 0x3E), [1]));
    await s.setPowerMode('suspend');
    checkTrue('power_mode_suspend', same(regWrites(c, 0x3E), [1, 2]));
    let threw4 = false;
    try { await s.setPowerMode('bogus'); } catch (e) { threw4 = true; }
    checkTrue('power_mode_invalid', threw4);
    c.writes.length = 0;
    await s.setTemperatureSource('gyroscope');
    checkTrue('temp_source', same(regWrites(c, 0x40), [1]));
    c.writes.length = 0;
    await s.useExternalCrystal(true);
    await s.useExternalCrystal(false);
    checkTrue('crystal', same(regWrites(c, 0x3F), [0x80, 0x00]));

    // self-test
    c.setRegister(0x36, [0x0D]);
    c.writes.length = 0;
    const st = await s.selfTest();
    checkTrue('self_test_trigger', same(regWrites(c, 0x3F), [0x01]));
    checkTrue('self_test_result', st.acc && !st.mag && st.gyr && st.mcu);
    checkTrue('self_test_restores_mode', same(regWrites(c, 0x3D), [0x00, 0x0C]));

    // status, revisions
    c.setRegister(0x39, [1, 3]);
    checkTrue('system_status', same(await s.systemStatus(), [1, 3]));
    const rev = await s.revisions();
    checkTrue('revisions', rev.chipId === 0xA0 && rev.accId === 0xFB && rev.magId === 0x32 && rev.gyrId === 0x0F &&
        rev.swRevision === 0x0311 && rev.bootloader === 0x15);

    // unique id
    c.setRegister(0x50, Array.from({ length: 16 }, (_, i) => i));
    c.writes.length = 0;
    const uid = await s.uniqueId();
    checkTrue('unique_id', uid.length === 16 && uid[15] === 15);
    checkTrue('unique_id_pages', same(regWrites(c, 0x07), [1, 0]));

    // interrupts
    c.setRegister(0x10, [0x00]);
    c.setRegister(0x0F, [0x00]);
    c.writes.length = 0;
    await s.enableInterrupt(BNO055Source.ACC_BSX_DRDY | BNO055Source.GYR_DRDY);
    checkTrue('enable_int_pages', same(regWrites(c, 0x07), [1, 0]));
    checkTrue('enable_int_regs', same(regWrites(c, 0x10), [0x11]) && same(regWrites(c, 0x0F), [0x11]));
    c.setRegister(0x10, [0x11]);
    c.setRegister(0x0F, [0x11]);
    c.writes.length = 0;
    await s.disableInterrupt(BNO055Source.GYR_DRDY);
    checkTrue('disable_int', same(regWrites(c, 0x10), [0x01]) && same(regWrites(c, 0x0F), [0x01]));
    c.setRegister(0x37, [0x41]);
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === 0x41);

    let edgeHandler = null;
    const fakePin = {
        onEdge: async (h) => { edgeHandler = h; },
        offEdge: async (h) => { if (edgeHandler === h) edgeHandler = null; },
    };
    let seen = 0;
    await s.onInterrupt((v) => { seen = v; }, fakePin);
    checkTrue('on_interrupt_registers', edgeHandler !== null);
    edgeHandler();
    await new Promise((r) => setTimeout(r, 20));
    checkTrue('on_interrupt_callback', seen === 0x41);
    await s.offInterrupt();
    checkTrue('off_interrupt', edgeHandler === null);

    // reset
    c.writes.length = 0;
    await s.reset();
    checkTrue('reset_trigger', regWrites(c, 0x3F)[0] === 0x20);
    checkTrue('reset_reinit', regWrites(c, 0x3D).pop() === 0x0C && regWrites(c, 0x3B).pop() === 0x86);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
