'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LTC4151Minimal, LTC4151Full } = require('../../packages/periph/src/chips/power/ltc4151');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const close = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;

async function throws(fn) {
    try { await fn(); return false; } catch (e) { return true; }
}

async function main() {
    const connection = new I2CConnectionMock();

    // Construction makes no bus access
    const sensor = new LTC4151Full(connection);
    checkTrue('init_no_writes', connection.writes.length === 0);

    // VIN code 0x800 = 2048 -> 51.2 V
    connection.setRegister(0x02, [0x80, 0x00]);
    checkTrue('voltage', close(await sensor.voltage(), 51.2));

    // SENSE code 0x400 = 1024 -> 20.48 mV -> 1.024 A at 20 mOhm
    connection.setRegister(0x00, [0x40, 0x00]);
    checkTrue('sense_voltage', close(await sensor.senseVoltage(), 0.02048));
    checkTrue('current', close(await sensor.current(), 1.024));
    checkTrue('power', close(await sensor.power(), 51.2 * 1.024));

    // The four LSBs sit in bits 7:4 of the low register; BUSY / reserved bits are ignored
    connection.setRegister(0x02, [0x00, 0xF8]);
    checkTrue('voltage_low_nibble', close(await sensor.voltage(), 15 * 0.025));

    // ADIN code 0x7D0 = 2000 -> 1.0 V
    connection.setRegister(0x04, [0x7D, 0x00]);
    checkTrue('adin_voltage', close(await sensor.adinVoltage(), 1.0));

    // readAll
    connection.setRegister(0x00, [0x40, 0x00, 0x80, 0x00, 0x7D, 0x00]);
    const d = await sensor.readAll();
    checkTrue('read_all', close(d.vin, 51.2) && close(d.senseV, 0.02048) && close(d.current, 1.024) &&
        close(d.power, 51.2 * 1.024) && close(d.adin, 1.0));

    // readRaw
    checkTrue('read_raw_sense', (await sensor.readRaw(LTC4151Full.SENSE)) === 0x400);
    checkTrue('read_raw_vin', (await sensor.readRaw(LTC4151Full.VIN)) === 0x800);
    checkTrue('read_raw_adin', (await sensor.readRaw(LTC4151Full.ADIN)) === 0x7D0);
    checkTrue('read_raw_bad_channel', await throws(() => sensor.readRaw(3)));

    // Sense resistor
    sensor.setSenseResistor(0.01);
    checkTrue('set_sense_resistor', close(sensor.getSenseResistor(), 0.01) && close(await sensor.current(), 2.048));
    checkTrue('set_sense_resistor_zero', await throws(async () => sensor.setSenseResistor(0)));
    sensor.setSenseResistor(0.02);

    // Minimal with a custom resistor
    const minimal = new LTC4151Minimal(connection, 0.005);
    checkTrue('minimal', close(await minimal.current(), 4.096) && close(await minimal.power(), 51.2 * 4.096) &&
        close(await minimal.voltage(), 51.2));

    // CONTROL read-modify-write
    const ctrlWrites = () => connection.writes.filter((w) => w.length === 2 && w[0] === 0x06);
    const lastCtrl = () => { const c = ctrlWrites(); return c[c.length - 1][1]; };
    connection.setRegister(0x06, [0x0C]);
    checkTrue('read_control', (await sensor.readControl()) === 0x0C);
    await sensor.setStuckBusTimer(false);
    checkTrue('stuck_bus_off', lastCtrl() === 0x08);
    connection.setRegister(0x06, [0x08]);
    await sensor.setStuckBusTimer(true);
    checkTrue('stuck_bus_on', lastCtrl() === 0x0C);
    await sensor.setPageRw(false);
    checkTrue('page_rw_off', lastCtrl() === 0x04);
    connection.setRegister(0x06, [0x04]);
    await sensor.setPageRw(true);
    checkTrue('page_rw_on', lastCtrl() === 0x0C);

    // writeControl masks test mode
    await sensor.writeControl(0xFF);
    checkTrue('write_control_masks_test', lastCtrl() === 0xEF);
    await sensor.writeControl(0x0C);

    // isSnapshotMode / setContinuous
    connection.setRegister(0x06, [0xA8]);
    checkTrue('is_snapshot_mode_true', (await sensor.isSnapshotMode()) === true);
    await sensor.setContinuous();
    checkTrue('set_continuous', lastCtrl() === 0x28);
    connection.setRegister(0x06, [0x0C]);
    checkTrue('is_snapshot_mode_false', (await sensor.isSnapshotMode()) === false);

    // isBusy
    connection.setRegister(0x01, [0x00]);
    connection.setRegister(0x03, [0x08]);
    checkTrue('is_busy_sense_false', (await sensor.isBusy(LTC4151Full.SENSE)) === false);
    checkTrue('is_busy_vin_true', (await sensor.isBusy(LTC4151Full.VIN)) === true);
    connection.setRegister(0x03, [0x00]);

    // snapshot(VIN)
    connection.setRegister(0x06, [0x0C]);
    connection.setRegister(0x02, [0x80, 0x00]);
    connection.writes.length = 0;
    const value = await sensor.snapshot(LTC4151Full.VIN);
    const c = ctrlWrites();
    checkTrue('snapshot_value', close(value, 51.2));
    checkTrue('snapshot_clear_first', c[0][1] === 0x0C);
    checkTrue('snapshot_start', c[1][1] === 0xAC);
    checkTrue('snapshot_restore', c[c.length - 1][1] === 0x0C);

    // snapshot timeout
    connection.setRegister(0x01, [0x08]);
    checkTrue('snapshot_timeout', await throws(() => sensor.snapshot(LTC4151Full.SENSE)));
    connection.setRegister(0x01, [0x00]);

    // Page R/W disabled: single-register reads
    connection.setRegister(0x00, [0x40, 0x00, 0x80, 0x00, 0x7D, 0x00]);
    await sensor.setPageRw(false);
    checkTrue('page_off_read_all', close((await sensor.readAll()).power, 51.2 * 1.024));
    await sensor.setPageRw(true);

    checkTrue('temperature_from_adin', close(LTC4151Full.temperatureFromAdin(900, 1200), 58.82 * (0.75 - 0.1066)));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
