'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { ICM20948Full } = require('../../packages/periph/src/chips/imu/icm20948');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1', 10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR || '0x68', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) {
        console.log('PASS', label);
        passed++;
    } else {
        console.log('FAIL', label);
        failed++;
    }
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const norm = (v) => Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new ICM20948Full(connection);
    await imu.init();   // WHO_AM_I is checked during initialization and rejects on a mismatch
    checkTrue('who_am_i', (await connection.readReg(0x00, 1))[0] === 0xEA);

    const accel = await imu.accel();
    checkTrue('accel finite', accel.every((v) => v > -200 && v < 200));
    checkTrue('gravity magnitude', norm(accel) > 5 && norm(accel) < 15);

    const gyro = await imu.gyro();
    checkTrue('gyro finite', gyro.every((v) => v > -100 && v < 100));

    const t = await imu.temperature();
    checkTrue('temperature range', t > -40 && t < 85);

    const [, , tc] = await imu.readAll();
    checkTrue('read_all temp range', tc > -40 && tc < 85);

    const raw = await imu.accelRaw();
    checkTrue('accel_raw range', raw.every((v) => v >= -32768 && v <= 32767));
    const graw = await imu.gyroRaw();
    checkTrue('gyro_raw range', graw.every((v) => v >= -32768 && v <= 32767));

    await imu.configureGyro(1);
    await imu.configureAccel(1);
    const accel2 = await imu.accel();
    checkTrue('accel after reconfig', accel2.every((v) => v > -200 && v < 200));

    await imu.configureFilters(4, 4);
    await imu.configureSampleRate(9, 9);
    checkTrue('bank 0 after config', (await connection.readReg(0x00, 1))[0] === 0xEA);

    const off = await imu.getAccelOffset();
    checkTrue('accel offset range', off.every((v) => v >= -16384 && v <= 16383));

    await imu.enableMag();
    await sleep(150);
    const mag = await imu.mag();
    checkTrue('mag magnitude', norm(mag) > 5 && norm(mag) < 150);
    checkTrue('aux_read WIA2', (await imu.auxReadByte(0x0C, 0x01)) === 0x09);

    await imu.setSleep(true);
    await sleep(10);
    await imu.setSleep(false);
    await sleep(50);
    const accel3 = await imu.accel();
    checkTrue('accel after wake', accel3.every((v) => v > -200 && v < 200));

    await imu.enableFifo(true, true);
    await sleep(50);
    const count = await imu.fifoCount();
    checkTrue('fifo_count > 0', count > 0);
    const data = await imu.readFifo();
    checkTrue('read_fifo returns bytes', data.length > 0);
    await imu.resetFifo();

    await imu.setWakeOnMotion(200);
    const status = await imu.pollInterrupt();
    checkTrue('poll_interrupt int', Number.isInteger(status));

    await connection.close();
    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => { console.error(err); process.exit(1); });
