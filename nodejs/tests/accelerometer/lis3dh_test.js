'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { LIS3DHMinimal, LIS3DHFull, LIS3DHSource } = require('../../packages/periph/src/chips/accelerometer/lis3dh');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x18', 16);

const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
const accel = new LIS3DHMinimal(connection);
const accelFull = new LIS3DHFull(connection);

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

(async () => {
    try {
        const { x, y, z } = await accel.read();
        checkTrue('read_returns_numbers', typeof x === 'number' && typeof y === 'number' && typeof z === 'number');
        const mag = Math.sqrt(x * x + y * y + z * z);
        checkTrue('magnitude_near_1g', mag >= 0.5 && mag <= 1.5);

        const raw = await accelFull.readRaw();
        checkTrue('read_raw_returns_integers',
            Number.isInteger(raw.x) && Number.isInteger(raw.y) && Number.isInteger(raw.z));
        checkTrue('read_raw_x_matches_read', Math.abs(raw.x * 0.001 - x) < 0.01);

        const status = await accelFull.readStatus();
        checkTrue('read_status_is_byte', status >= 0 && status <= 0xFF);
        const ready = await accelFull.newDataAvailable();
        checkTrue('new_data_available_is_bool', typeof ready === 'boolean');

        await accelFull.setRange(4);
        const acc4 = await accelFull.read();
        checkTrue('read_after_set_range_4g',
            typeof acc4.x === 'number' && typeof acc4.y === 'number' && typeof acc4.z === 'number');
        await accelFull.setRange(2);

        await accelFull.setDataRate(50);
        checkTrue('set_data_rate_50hz', true);

        await accelFull.setMode(1);
        const accNormal = await accelFull.read();
        checkTrue('read_after_set_mode_normal', typeof accNormal.x === 'number');
        await accelFull.setMode(2);

        const temp = await accelFull.readTemperatureDelta();
        checkTrue('temperature_delta_in_range', temp >= -50 && temp <= 50);

        const adc = await accelFull.readAdc(1);
        checkTrue('aux_adc_voltage_in_range', adc >= 0.7 && adc <= 1.7);

        await accelFull.setFifo(2, 16);
        await new Promise(r => setTimeout(r, 200));
        const level = await accelFull.fifoLevel();
        checkTrue('fifo_level_is_valid', level >= 0 && level <= 32);
        const samples = await accelFull.readFifo();
        checkTrue('read_fifo_returns_samples', Array.isArray(samples));
        const fifo = await accelFull.readFifoStatus();
        checkTrue('read_fifo_status_has_flags',
            typeof fifo.watermark === 'boolean' && typeof fifo.overrun === 'boolean' &&
            typeof fifo.empty === 'boolean' && fifo.level >= 0);
        await accelFull.setFifo(0);

        await accelFull.setMotionInterrupt(1, 0x05, 0.5, 20);
        await accelFull.setFreeFall(1, 0.35, 60);
        await accelFull.setLatch(1, true);
        await accelFull.enableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
        await accelFull.enableInterrupt(LIS3DHSource.SOURCE_IA1, 1);
        const poll = await accelFull.pollInterrupt();
        checkTrue('poll_interrupt_is_bitmask', poll >= 0 && poll <= 0xFFFF);
        await accelFull.disableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
        await accelFull.disableInterrupt(LIS3DHSource.SOURCE_IA1, 1);

        const st = await accelFull.selfTest(1);
        checkTrue('self_test_passes', st === true);

        await accelFull.setSdoPullup(true);
        await accelFull.powerDown();
        await accelFull.setDataRate(100);
    } catch (e) {
        console.log('FAIL exception:', e.message);
        failed++;
    }
    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
})();
