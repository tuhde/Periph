'use strict';

const { I2CTransport } = require('../../packages/periph/src/transport/i2c');
const { MPU6050Full }   = require('../../packages/periph/src/chips/imu/mpu6050');

const I2C_BUS  = parseInt(process.env.I2C_BUS  || '1',  10);
const I2C_ADDR = parseInt(process.env.I2C_ADDR  || '0x68', 16);

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else           { console.log('FAIL', label); failed++; }
}

const transport = new I2CTransport(I2C_BUS, I2C_ADDR);
const imu = new MPU6050Full(transport);

checkTrue('who_am_i', imu._readReg(0x75) === 0x68);

const [ax, ay, az] = imu.accel();
checkTrue('accel_x finite', ax > -200.0 && ax < 200.0);
checkTrue('accel_y finite', ay > -200.0 && ay < 200.0);
checkTrue('accel_z finite', az > -200.0 && az < 200.0);

const [gx, gy, gz] = imu.gyro();
checkTrue('gyro_x finite', gx > -100.0 && gx < 100.0);
checkTrue('gyro_y finite', gy > -100.0 && gy < 100.0);
checkTrue('gyro_z finite', gz > -100.0 && gz < 100.0);

const t = imu.temperature();
checkTrue('temperature range', t > -40.0 && t < 85.0);

const [rax, ray, raz] = imu.accelRaw();
checkTrue('accel_raw_x range', rax >= -32768 && rax <= 32767);

const [rgx, rgy, rgz] = imu.gyroRaw();
checkTrue('gyro_raw_x range', rgx >= -32768 && rgx <= 32767);

imu.configureGyro(1);
imu.configureAccel(1);
const [ax2, ay2, az2] = imu.accel();
checkTrue('accel after reconfig', ax2 > -200.0 && ax2 < 200.0);

imu.configureDlpf(4);
imu.configureSampleRate(9);

imu.setSleep(true);
const end1 = Date.now() + 10;
while (Date.now() < end1) {}
imu.setSleep(false);
const end2 = Date.now() + 50;
while (Date.now() < end2) {}
const [ax3, ay3, az3] = imu.accel();
checkTrue('accel after wake', ax3 > -200.0 && ax3 < 200.0);

imu.setStandby(true, false, false, false, false, false);
imu.setStandby();

imu.resetFifo();
imu.enableFifo(true, true, false);
const end3 = Date.now() + 50;
while (Date.now() < end3) {}
const count = imu.fifoCount();
checkTrue('fifo_count > 0', count > 0);
const data = imu.readFifo();
checkTrue('read_fifo matches count', data.length === count);

imu.resetFifo();

transport.close();

console.log(`===DONE: ${passed} passed, ${failed} failed===`);
process.exit(failed === 0 ? 0 : 1);
