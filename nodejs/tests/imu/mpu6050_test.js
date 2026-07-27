'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { MPU6050Full }   = require('../../packages/periph/src/chips/imu/mpu6050');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x68', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new MPU6050Full(connection);

    checkTrue('who_am_i', (await imu._readReg(0x75)) === 0x68);

    const [ax, ay, az] = await imu.accel();
    checkTrue('accel_x finite', ax > -200.0 && ax < 200.0);
    checkTrue('accel_y finite', ay > -200.0 && ay < 200.0);
    checkTrue('accel_z finite', az > -200.0 && az < 200.0);

    const [gx, gy, gz] = await imu.gyro();
    checkTrue('gyro_x finite', gx > -100.0 && gx < 100.0);
    checkTrue('gyro_y finite', gy > -100.0 && gy < 100.0);
    checkTrue('gyro_z finite', gz > -100.0 && gz < 100.0);

    const t = await imu.temperature();
    checkTrue('temperature range', t > -40.0 && t < 85.0);

    const [rax, ray, raz] = await imu.accelRaw();
    checkTrue('accel_raw_x range', rax >= -32768 && rax <= 32767);

    const [rgx, rgy, rgz] = await imu.gyroRaw();
    checkTrue('gyro_raw_x range', rgx >= -32768 && rgx <= 32767);

    await imu.configureGyro(1);
    await imu.configureAccel(1);
    const [ax2, ay2, az2] = await imu.accel();
    checkTrue('accel after reconfig', ax2 > -200.0 && ax2 < 200.0);

    await imu.configureDlpf(4);
    await imu.configureSampleRate(9);

    await imu.setSleep(true);
    const end1 = Date.now() + 10;
    while (Date.now() < end1) {}
    await imu.setSleep(false);
    const end2 = Date.now() + 50;
    while (Date.now() < end2) {}
    const [ax3, ay3, az3] = await imu.accel();
    checkTrue('accel after wake', ax3 > -200.0 && ax3 < 200.0);

    await imu.setStandby(true, false, false, false, false, false);
    await imu.setStandby();

    await imu.resetFifo();
    await imu.enableFifo(true, true, false);
    const end3 = Date.now() + 50;
    while (Date.now() < end3) {}
    const count = await imu.fifoCount();
    checkTrue('fifo_count > 0', count > 0);
    const data = await imu.readFifo();
    checkTrue('read_fifo matches count', data.length === count);

    await imu.resetFifo();

    await connection.close();

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
