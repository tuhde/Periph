'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { HMC5883LMinimal, HMC5883LFull } = require('../../packages/periph/src/chips/magnetometer/hmc5883l');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function close(a, b, eps = 1e-9) {
    return a !== null && b !== null && Math.abs(a - b) < eps;
}

function lastWriteTo(connection, reg) {
    for (let i = connection.writes.length - 1; i >= 0; i--) {
        const w = connection.writes[i];
        if (w.length === 2 && w[0] === reg) return w[1];
    }
    return null;
}

async function main() {
    // --- Minimal constructor: writes Config A, Config B, Mode ---
    const connection = new I2CConnectionMock();
    const chip = new HMC5883LMinimal(connection);
    await new Promise((r) => setImmediate(r)); // let the fire-and-forget _initMinimal() finish
    checkTrue('init_writes_config_a', lastWriteTo(connection, 0x00) === 0x70);
    checkTrue('init_writes_config_b', lastWriteTo(connection, 0x01) === 0x20);
    checkTrue('init_writes_mode', lastWriteTo(connection, 0x02) === 0x00);

    // --- magneticField(): X,Z,Y wire order -> (x,y,z), gain=1 (1090 LSb/Gauss) ---
    connection.setRegister(0x03, [0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0]); // x=1000,z=-500,y=2000
    let { x, y, z } = await chip.magneticField();
    checkTrue('magnetic_field_x', close(x, (1000 / 1090) * 1e-4));
    checkTrue('magnetic_field_y', close(y, (2000 / 1090) * 1e-4));
    checkTrue('magnetic_field_z', close(z, (-500 / 1090) * 1e-4));

    // --- magneticField(): overflow sentinel returns null ---
    connection.setRegister(0x03, [0xF0, 0x00, 0x03, 0xE8, 0x03, 0xE8]); // x overflow, z/y=1000
    ({ x, y } = await chip.magneticField());
    checkTrue('magnetic_field_overflow_x_null', x === null);
    checkTrue('magnetic_field_overflow_y_not_null', y !== null);

    // --- Full: configure() writes Config A/B, updates cached gain ---
    const fullConn = new I2CConnectionMock();
    const full = new HMC5883LFull(fullConn);
    await new Promise((r) => setImmediate(r));
    await full.configure(30, 4, 5);
    checkTrue('configure_config_a', lastWriteTo(fullConn, 0x00) === 0x54); // MA=10,DO=101
    checkTrue('configure_config_b', lastWriteTo(fullConn, 0x01) === 0xA0); // GN=101
    checkTrue('configure_updates_gain', full._gain === 5);

    // --- Regression: averaging=1 and odr=0.75 map to 0, must not be
    // rejected by a truthiness check on the mapped value. ---
    await full.configure(0.75, 1, 0);
    checkTrue('configure_averaging_1_accepted', lastWriteTo(fullConn, 0x00) === 0x00);
    checkTrue('configure_odr_0_75_accepted', full._gain === 0);

    let threw = false;
    try { await full.configure(30, 3, 1); } catch (e) { threw = true; }
    checkTrue('configure_bad_averaging_throws', threw);
    threw = false;
    try { await full.configure(100, 4, 1); } catch (e) { threw = true; }
    checkTrue('configure_bad_odr_throws', threw);
    threw = false;
    try { await full.configure(30, 4, 8); } catch (e) { threw = true; }
    checkTrue('configure_bad_gain_throws', threw);

    // --- setGain() ---
    await full.setGain(2);
    checkTrue('set_gain_writes_config_b', lastWriteTo(fullConn, 0x01) === (2 << 5));
    checkTrue('set_gain_updates_cache', full._gain === 2);
    threw = false;
    try { await full.setGain(9); } catch (e) { threw = true; }
    checkTrue('set_gain_invalid_throws', threw);

    // --- setMode(): regression -- 'continuous' maps to 0, must not be rejected ---
    await full.setMode('continuous');
    checkTrue('set_mode_continuous_accepted', lastWriteTo(fullConn, 0x02) === 0b00);
    await full.setMode('single');
    checkTrue('set_mode_single', lastWriteTo(fullConn, 0x02) === 0b01);
    await full.setMode('idle');
    checkTrue('set_mode_idle', lastWriteTo(fullConn, 0x02) === 0b10);
    threw = false;
    try { await full.setMode('bogus'); } catch (e) { threw = true; }
    checkTrue('set_mode_invalid_throws', threw);

    // --- dataReady()/status() ---
    fullConn.setRegister(0x09, [0x01]);
    checkTrue('data_ready_true', (await full.dataReady()) === true);
    checkTrue('status_raw', (await full.status()) === 0x01);
    fullConn.setRegister(0x09, [0x02]); // LOCK set, RDY clear
    checkTrue('data_ready_false', (await full.dataReady()) === false);

    // --- singleMeasurement(): writes mode=0x01, then reads data ---
    fullConn.setRegister(0x03, [0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0]); // x=1000,z=-500,y=2000
    const s = await full.singleMeasurement();
    checkTrue('single_measurement_writes_mode', lastWriteTo(fullConn, 0x02) === 0x01);
    checkTrue('single_measurement_x', close(s.x, (1000 / full._gainLsbPerGauss) * 1e-4));

    // --- identify() ---
    fullConn.setRegister(0x0A, [0x48, 0x34, 0x33]);
    const id = await full.identify();
    checkTrue('identify', id[0] === 0x48 && id[1] === 0x34 && id[2] === 0x33);

    // --- selfTest(): sets MS bias bits, restores normal mode afterward ---
    const stConn = new I2CConnectionMock();
    const selftest = new HMC5883LFull(stConn);
    await new Promise((r) => setImmediate(r));
    stConn.setRegister(0x00, [0x70]); // current Config A (post-init)
    stConn.setRegister(0x03, [0x03, 0xE8, 0xFE, 0x0C, 0x07, 0xD0]);
    const result = await selftest.selfTest(true);
    const writesA = stConn.writes.filter((w) => w.length === 2 && w[0] === 0x00).map((w) => w[1]);
    checkTrue('self_test_sets_positive_bias', writesA.includes(0x71)); // 0x70|0b01
    checkTrue('self_test_restores_normal', writesA[writesA.length - 1] === 0x70);
    checkTrue('self_test_result', close(result.x, (1000 / selftest._gainLsbPerGauss) * 1e-4));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
