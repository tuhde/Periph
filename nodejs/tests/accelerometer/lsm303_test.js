'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LSM303Minimal, LSM303Full, LSM303Source } =
    require('../../packages/periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

const accelConnection = new I2CConnection(I2C_BUS, 0x19);
const magConnection = new I2CConnection(I2C_BUS, 0x1E);
const compass = new LSM303Minimal(accelConnection, magConnection);
const compassFull = new LSM303Full(accelConnection, magConnection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        // --- Minimal: acceleration ---
        const accel = await compass.readAcceleration();
        checkTrue('read_acceleration_returns_numbers',
            typeof accel.x === 'number' && typeof accel.y === 'number' && typeof accel.z === 'number');
        const magnitude = Math.sqrt(accel.x ** 2 + accel.y ** 2 + accel.z ** 2);
        checkTrue('acceleration_magnitude_near_1g', magnitude >= 0.5 && magnitude <= 1.5);

        // --- Minimal: magnetic field ---
        const mag = await compass.readMagnetic();
        checkTrue('read_magnetic_returns_numbers',
            typeof mag.x === 'number' && typeof mag.y === 'number' && typeof mag.z === 'number');

        // --- Full: configuration round-trip ---
        await compassFull.setRange(4);
        const accel2 = await compassFull.readAcceleration();
        checkTrue('read_acceleration_after_set_range',
            typeof accel2.x === 'number' && typeof accel2.y === 'number' && typeof accel2.z === 'number');

        await compassFull.setDataRate(50);
        checkTrue('new_data_available', (await compassFull.newDataAvailable()) === true ||
            (await compassFull.newDataAvailable()) === false);

        const status = await compassFull.readStatus();
        checkTrue('read_status_returns_byte', typeof status === 'number' && status >= 0 && status <= 255);

        // --- Full: magnetometer ---
        const identity = await compassFull.readMagIdentity();
        checkTrue('mag_identity_matches',
            identity[0] === 0x48 && identity[1] === 0x34 && identity[2] === 0x33);

        await compassFull.setMagRange(250);
        await compassFull.setMagDataRate(30);
        const magStatus = await compassFull.readMagStatus();
        checkTrue('read_mag_status_returns_flags',
            typeof magStatus.drdy === 'boolean' && typeof magStatus.lock === 'boolean');

        await compassFull.enableTemperature(true);
        const temperature = await compassFull.readTemperatureDelta();
        checkTrue('temperature_in_range', temperature >= -50 && temperature <= 150);

        // --- Full: heading ---
        const heading = await compassFull.heading(false);
        checkTrue('heading_in_range', heading >= 0 && heading < 360);

        // --- Full: FIFO and interrupt configuration ---
        await compassFull.setFifo(LSM303Full.FIFO_STREAM, 16);
        const fifoStatus = await compassFull.readFifoStatus();
        checkTrue('read_fifo_status_returns_flags',
            typeof fifoStatus.watermark === 'boolean' && typeof fifoStatus.level === 'number');

        await compassFull.setFreeFall(1, 0.35, 30);
        await compassFull.setLatch(1, true);
        await compassFull.enableInterrupt(LSM303Source.SOURCE_IA1, 1);
        const interruptStatus = await compassFull.pollInterrupt();
        checkTrue('poll_interrupt_returns_mask', typeof interruptStatus === 'number');
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await accelConnection.close();
    await magConnection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
