'use strict';

const { I2CConnection } = require('../../packages/periph/src/connection/i2c');
const { QMI8658AFull }  = require('../../packages/periph/src/chips/imu/qmi8658a');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x6B', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
    const connection = new I2CConnection(I2C_BUS, I2C_ADDR);
    const imu = new QMI8658AFull(connection);
    await imu.init();                       // rejects on a WHO_AM_I mismatch

    checkTrue('who_am_i', (await connection.readReg(0x00, 1))[0] === 0x05);

    const [ax, ay, az] = await imu.accel();
    const mag = Math.sqrt(ax * ax + ay * ay + az * az);
    checkTrue('accel magnitude ~1 g', mag > 7.0 && mag < 12.6);
    const [gx, gy, gz] = await imu.gyro();
    checkTrue('gyro finite', Math.abs(gx) < 10 && Math.abs(gy) < 10 && Math.abs(gz) < 10);

    const [, , t] = await imu.readAll();
    checkTrue('temperature range', t > -40.0 && t < 85.0);
    checkTrue('temperature matches', Math.abs((await imu.temperature()) - t) < 5.0);
    const [rax] = await imu.accelRaw();
    checkTrue('accel_raw range', rax >= -32768 && rax <= 32767);
    const [rgx] = await imu.gyroRaw();
    checkTrue('gyro_raw range', rgx >= -32768 && rgx <= 32767);

    const ts1 = await imu.timestamp();
    await sleep(100);
    checkTrue('timestamp advances', (await imu.timestamp()) !== ts1);

    await imu.configureAccel(1, 5);
    await imu.configureGyro(5, 5);
    await sleep(200);
    const [bx, by, bz] = await imu.accel();
    const mag2 = Math.sqrt(bx * bx + by * by + bz * bz);
    checkTrue('accel after reconfig', mag2 > 7.0 && mag2 < 12.6);
    await imu.configureAccel();
    await imu.configureGyro();
    await imu.configureFilters(true, 3, true, 3);
    await sleep(200);

    await imu.configureFifo(2, 1, 8);
    await sleep(300);
    checkTrue('fifo_count non-zero', (await imu.fifoCount()) > 0);
    const data = await imu.readFifo();
    checkTrue('read_fifo returns whole frames', data.length > 0 && data.length % 12 === 0);
    await imu.resetFifo();
    await imu.configureFifo(0);
    await sleep(100);

    const info = await imu.deviceInfo();
    checkTrue('device_info firmware', info.firmware.length === 3 && info.usid.length === 6);
    await imu.setSensors(true, true);

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    await connection.close();
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((err) => { console.error(err); process.exit(1); });
