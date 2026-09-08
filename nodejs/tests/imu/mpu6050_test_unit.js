'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { MPU6050Full } = require('../../packages/periph/src/chips/imu/mpu6050');

const _REG_SMPLRT_DIV   = 0x19;
const _REG_CONFIG       = 0x1A;
const _REG_GYRO_CONFIG  = 0x1B;
const _REG_ACCEL_CONFIG = 0x1C;
const _REG_FIFO_EN      = 0x23;
const _REG_INT_STATUS   = 0x3A;
const _REG_ACCEL_XOUT_H = 0x3B;
const _REG_TEMP_OUT_H   = 0x41;
const _REG_GYRO_XOUT_H  = 0x43;
const _REG_USER_CTRL    = 0x6A;
const _REG_PWR_MGMT_1   = 0x6B;
const _REG_PWR_MGMT_2   = 0x6C;
const _REG_FIFO_COUNTH  = 0x72;
const _REG_FIFO_R_W     = 0x74;
const _REG_WHO_AM_I     = 0x75;

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function flushMicrotasks() {
    return new Promise((resolve) => setImmediate(resolve));
}

// Encode a signed 16-bit value as its two big-endian bytes.
function s16(value) {
    const buf = Buffer.alloc(2);
    buf.writeInt16BE(value, 0);
    return [buf[0], buf[1]];
}

async function main() {
    const connection = new I2CConnectionMock();
    connection.setRegister(_REG_WHO_AM_I, [0x68]);

    const sensor = new MPU6050Full(connection);
    await flushMicrotasks(); // let the fire-and-forget _init() finish (includes two busy-wait delays)
    checkTrue('init', true);

    // init sequence: reset, wait, wake, WHO_AM_I check, default config writes.
    const expectedInitWrites = [
        Buffer.from([_REG_PWR_MGMT_1, 0x80]),
        Buffer.from([_REG_PWR_MGMT_1, 0x01]),
        Buffer.from([_REG_WHO_AM_I]),
        Buffer.from([_REG_GYRO_CONFIG, 0x00]),
        Buffer.from([_REG_ACCEL_CONFIG, 0x00]),
        Buffer.from([_REG_CONFIG, 0x03]),
        Buffer.from([_REG_SMPLRT_DIV, 0x04]),
    ];
    checkTrue('init_writes', connection.writes.length === expectedInitWrites.length &&
        connection.writes.every((w, i) => w.equals(expectedInitWrites[i])));

    // accel(): raw (16384, -8192, 4096) at default AFS_SEL=0 (16384 LSB/g).
    connection.setRegister(_REG_ACCEL_XOUT_H, [...s16(16384), ...s16(-8192), ...s16(4096)]);
    const [ax, ay, az] = await sensor.accel();
    checkTrue('accel_x', Math.abs(ax - 9.80665) < 1e-9);
    checkTrue('accel_y', Math.abs(ay - (-4.903325)) < 1e-9);
    checkTrue('accel_z', Math.abs(az - 2.4516625) < 1e-9);

    // gyro(): raw (131, -131, 262) at default FS_SEL=0 (131.0 LSB/(deg/s)) -> (1, -1, 2) dps.
    connection.setRegister(_REG_GYRO_XOUT_H, [...s16(131), ...s16(-131), ...s16(262)]);
    const [gx, gy, gz] = await sensor.gyro();
    const deg2rad = (d) => d * Math.PI / 180;
    checkTrue('gyro_x', Math.abs(gx - deg2rad(1)) < 1e-9);
    checkTrue('gyro_y', Math.abs(gy - deg2rad(-1)) < 1e-9);
    checkTrue('gyro_z', Math.abs(gz - deg2rad(2)) < 1e-9);

    await sensor.configureGyro(2);
    checkTrue('configure_gyro_writes', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_GYRO_CONFIG, 2 << 3])));
    // Sensitivity for FS_SEL=2 is 32.8 LSB/(deg/s); raw=328 -> 10 dps.
    connection.setRegister(_REG_GYRO_XOUT_H, [...s16(328), ...s16(0), ...s16(0)]);
    const [gx2] = await sensor.gyro();
    checkTrue('configure_gyro_changes_sensitivity', Math.abs(gx2 - deg2rad(10)) < 1e-9);

    await sensor.configureAccel(1);
    checkTrue('configure_accel_writes', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_ACCEL_CONFIG, 1 << 3])));
    // Sensitivity for AFS_SEL=1 is 8192 LSB/g; raw=8192 -> 1g.
    connection.setRegister(_REG_ACCEL_XOUT_H, [...s16(8192), ...s16(0), ...s16(0)]);
    const [ax2] = await sensor.accel();
    checkTrue('configure_accel_changes_sensitivity', Math.abs(ax2 - 9.80665) < 1e-9);

    await sensor.configureDlpf(5);
    checkTrue('configure_dlpf', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_CONFIG, 5])));

    await sensor.configureSampleRate(9);
    checkTrue('configure_sample_rate', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_SMPLRT_DIV, 9])));

    // temperature(): raw=340 -> 340/340 + 36.53 = 37.53 degC.
    connection.setRegister(_REG_TEMP_OUT_H, s16(340));
    checkTrue('temperature', Math.abs((await sensor.temperature()) - 37.53) < 1e-9);

    // accelRaw() / gyroRaw()
    connection.setRegister(_REG_ACCEL_XOUT_H, [...s16(100), ...s16(-200), ...s16(300)]);
    const accelRaw = await sensor.accelRaw();
    checkTrue('accel_raw', accelRaw[0] === 100 && accelRaw[1] === -200 && accelRaw[2] === 300);
    connection.setRegister(_REG_GYRO_XOUT_H, [...s16(-50), ...s16(60), ...s16(-70)]);
    const gyroRaw = await sensor.gyroRaw();
    checkTrue('gyro_raw', gyroRaw[0] === -50 && gyroRaw[1] === 60 && gyroRaw[2] === -70);

    // dataReady()
    connection.setRegister(_REG_INT_STATUS, [0x01]);
    checkTrue('data_ready_true', (await sensor.dataReady()) === true);
    connection.setRegister(_REG_INT_STATUS, [0x00]);
    checkTrue('data_ready_false', (await sensor.dataReady()) === false);

    // setSleep(): PWR_MGMT_1 is 0x01 in the register map after init.
    await sensor.setSleep(true);
    checkTrue('set_sleep_true', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_PWR_MGMT_1, 0x41])));
    await sensor.setSleep(false);
    checkTrue('set_sleep_false', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_PWR_MGMT_1, 0x01])));

    // setStandby(xa=true, zg=true)
    await sensor.setStandby(true, false, false, false, false, true);
    checkTrue('set_standby', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_PWR_MGMT_2, 0x21])));

    // fifoCount()
    connection.setRegister(_REG_FIFO_COUNTH, [0x03, 0x45]);
    checkTrue('fifo_count', (await sensor.fifoCount()) === (((0x03 & 0x1F) << 8) | 0x45));

    // readFifo()
    connection.setRegister(_REG_FIFO_COUNTH, [0x00, 0x02]);
    connection.setRegister(_REG_FIFO_R_W, [0xAA, 0xBB]);
    const fifoData = await sensor.readFifo();
    checkTrue('read_fifo', fifoData.equals(Buffer.from([0xAA, 0xBB])));

    connection.setRegister(_REG_FIFO_COUNTH, [0x00, 0x00]);
    const fifoEmpty = await sensor.readFifo();
    checkTrue('read_fifo_empty', fifoEmpty.length === 0);

    // enableFifo(gyro=true, accel=true, temp=false): FIFO_EN write, then a
    // USER_CTRL read (whose writeRead phase also appends a Buffer([reg])
    // entry to connection.writes), then the USER_CTRL write.
    await sensor.enableFifo(true, true, false);
    const w = connection.writes;
    checkTrue('enable_fifo_writes',
        w[w.length - 3].equals(Buffer.from([_REG_FIFO_EN, (1 << 3) | (1 << 4)])) &&
        w[w.length - 2].equals(Buffer.from([_REG_USER_CTRL])) &&
        w[w.length - 1].equals(Buffer.from([_REG_USER_CTRL, 0x40])));

    // resetFifo(): USER_CTRL is 0x40 in the register map after enableFifo().
    await sensor.resetFifo();
    checkTrue('reset_fifo', connection.writes[connection.writes.length - 1].equals(Buffer.from([_REG_USER_CTRL, 0x44])));

    // Note: a WHO_AM_I mismatch is not tested here - the driver's constructor
    // fires off _init() unawaited (JS constructors can't be async), so a
    // mismatch surfaces as an unhandled promise rejection with no way for
    // this test to catch it (see the driver's own doc comment on
    // MPU6050Minimal), unlike the synchronous ValueError/abort() paths the
    // other language ports expose.

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
