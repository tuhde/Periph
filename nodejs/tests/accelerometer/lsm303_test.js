'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LSM303Full } = require('../../packages/periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x19', 16);

const accelConnection = new I2CConnection(I2C_BUS, I2C_ADDR);
const magConnection = new I2CConnection(I2C_BUS, 0x1E);
const compass = new LSM303Full(accelConnection, magConnection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    await compass.init();
    const id = await compass.readMagIdentity();
    checkTrue('mag_identity', id[0] === 0x48 && id[1] === 0x34 && id[2] === 0x33);

    const a = await compass.readAcceleration();
    checkTrue('read_acceleration_floats', [a.x, a.y, a.z].every(Number.isFinite));
    checkTrue('acceleration_plausible', Math.abs(a.x) < 18 && Math.abs(a.y) < 18 && Math.abs(a.z) < 18);

    const m = await compass.readMagnetic();
    checkTrue('read_magnetic_floats', [m.x, m.y, m.z].every(Number.isFinite));
    checkTrue('magnetic_plausible', Math.abs(m.x) < 1000 && Math.abs(m.y) < 1000 && Math.abs(m.z) < 1000);

    const h = await compass.heading(true);
    checkTrue('heading_range', h >= 0 && h <= 360);

    await compass.setRange(4);
    const a4 = await compass.readAcceleration();
    checkTrue('read_after_set_range_4g', Number.isFinite(a4.x));

    const raw = await compass.readAccelerationRaw();
    checkTrue('read_acceleration_raw_ints', Number.isInteger(raw.x) && Number.isInteger(raw.y) && Number.isInteger(raw.z));

    await compass.setFifo(1, 8, false);
    const level = await compass.fifoLevel();
    checkTrue('fifo_level_in_range', level >= 0 && level <= 32);
    const samples = await compass.readFifo();
    checkTrue('read_fifo_count', samples.length <= 32);
    await compass.setFifo(0);

    await compass.setHighPass(0, 0, false, false, false, false);
    const ref = await compass.resetHighPass();
    checkTrue('reset_high_pass_stable', (await compass.resetHighPass()) === ref);

    await compass.setMagRange(470);
    await compass.setMagDataRate(30);
    const m2 = await compass.readMagnetic();
    checkTrue('read_after_set_mag_range_470ut', Number.isFinite(m2.x));
    const mraw = await compass.readMagneticRaw();
    checkTrue('read_magnetic_raw_ints', Number.isInteger(mraw.x) && Number.isInteger(mraw.y) && Number.isInteger(mraw.z));

    await compass.enableTemperature(true);
    const temp = await compass.readTemperatureDelta();
    checkTrue('temperature_plausible', Math.abs(temp) < 100);

    await compass.reboot();
    const a2 = await compass.readAcceleration();
    checkTrue('read_after_reboot', Number.isFinite(a2.x));

    await accelConnection.close();
    await magConnection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
})().catch(err => { console.error('FAIL', err.message); console.log(`===DONE: ${passed} passed, ${failed + 1} failed===`); process.exit(1); });
