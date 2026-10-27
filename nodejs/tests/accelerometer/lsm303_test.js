'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LSM303Minimal, LSM303Full } = require('../../packages/periph/src/chips/accelerometer/lsm303');

const I2C_BUS = parseInt(process.env.I2C_BUS || '1', 10);

// Two independent I²C slaves on one bus: accelerometer 0x19, magnetometer 0x1E.
const accelConnection = new I2CConnection(I2C_BUS, 0x19);
const magConnection = new I2CConnection(I2C_BUS, 0x1E);
const lsm = new LSM303Minimal(accelConnection, magConnection);
const lsmFull = new LSM303Full(accelConnection, magConnection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        // Let both drivers' fire-and-forget init sequences finish.
        await new Promise(r => setTimeout(r, 400));

        const [ax, ay, az] = await lsm.readAcceleration();
        checkTrue('read_acceleration_returns_numbers', typeof ax === 'number' && typeof ay === 'number' && typeof az === 'number');
        const magnitude = Math.sqrt(ax * ax + ay * ay + az * az);
        checkTrue('acceleration_magnitude_near_1g', magnitude >= 0.5 && magnitude <= 1.5);

        const [mx, my, mz] = await lsm.readMagnetic();
        checkTrue('read_magnetic_returns_numbers', typeof mx === 'number' && typeof my === 'number' && typeof mz === 'number');
        const field = Math.sqrt(mx * mx + my * my + mz * mz);
        checkTrue('magnetic_field_in_range', field > 0 && field < 2000);

        const identity = await lsmFull.readMagIdentity();
        checkTrue('mag_identity_483433', identity.toString('hex') === '483433');

        await lsmFull.setRange(4);
        const [ax2] = await lsmFull.readAcceleration();
        checkTrue('read_after_set_range_4g', typeof ax2 === 'number');

        await lsmFull.setMagRange(190);
        const [mx2] = await lsmFull.readMagnetic();
        checkTrue('read_after_set_mag_range_190ut', typeof mx2 === 'number');

        const heading = await lsmFull.heading();
        checkTrue('heading_in_0_360', heading >= 0.0 && heading < 360.0);
        const headingFlat = await lsmFull.heading(false);
        checkTrue('heading_flat_in_0_360', headingFlat >= 0.0 && headingFlat < 360.0);

        const tempDelta = await lsmFull.readTemperatureDelta();
        checkTrue('temperature_delta_returns_number', typeof tempDelta === 'number');

        const ready = await lsmFull.newDataAvailable();
        checkTrue('new_data_available_returns_bool', typeof ready === 'boolean');
        const magReady = await lsmFull.magDataReady();
        checkTrue('mag_data_ready_returns_bool', typeof magReady === 'boolean');

        const fifoStatus = await lsmFull.readFifoStatus();
        checkTrue('fifo_status_shape', Array.isArray(fifoStatus) && fifoStatus.length === 4);

        await lsmFull.setFifo(2, 16);
        const samples = await lsmFull.readFifo();
        checkTrue('read_fifo_returns_samples', Array.isArray(samples) && samples.every(s => s.length === 3 && typeof s[0] === 'number'));
        await lsmFull.setFifo(0);

        const intStatus = await lsmFull.pollInterrupt();
        checkTrue('poll_interrupt_returns_number', typeof intStatus === 'number');
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await accelConnection.close();
    await magConnection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
