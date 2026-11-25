'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { ICM20948Full, ICM20948Minimal } = require('../../packages/periph/src/chips/imu/icm20948');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

const near = (a, b, tol = 1e-9) => Math.abs(a - b) < tol;
const eq = (a, b) => Buffer.from(a).equals(Buffer.from(b));

function s16(value) {
    const buf = Buffer.alloc(2);
    buf.writeInt16BE(value, 0);
    return [buf[0], buf[1]];
}

function s16le(value) {
    const buf = Buffer.alloc(2);
    buf.writeInt16LE(value, 0);
    return [buf[0], buf[1]];
}

// I2C mock that models the ICM-20948 user banks (REG_BANK_SEL = 0x7F).
// `registers` is keyed (bank << 8) | reg; `log` records [bank, bytes] per write.
class BankedMock extends I2CConnectionMock {
    constructor() {
        super();
        this.bank = 0;
        this.log = [];
    }

    setBankRegister(bank, reg, values) {
        values.forEach((v, i) => this.registers.set((bank << 8) | (reg + i), v));
    }

    async write(data) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        this.log.push([this.bank, buf]);
        if (buf[0] === 0x7F) { this.bank = buf[1] >> 4; return; }
        for (let i = 1; i < buf.length; i++) {
            this.registers.set((this.bank << 8) | (buf[0] + i - 1), buf[i]);
        }
    }

    async writeRead(data, n) {
        const buf = Buffer.from(data);
        this.writes.push(buf);
        this.log.push([this.bank, buf]);
        const out = Buffer.alloc(n);
        for (let i = 0; i < n; i++) out[i] = this.registers.get((this.bank << 8) | (buf[0] + i)) || 0;
        return out;
    }

    writesIn(bank) {
        return this.log.filter(([b, d]) => b === bank && d.length > 1 && d[0] !== 0x7F).map(([, d]) => d);
    }

    wrote(bank, bytes) {
        return this.writesIn(bank).some((d) => eq(d, bytes));
    }

    get(bank, reg) { return this.registers.get((bank << 8) | reg) || 0; }
}

async function make(setup) {
    const c = new BankedMock();
    c.setBankRegister(0, 0x00, [0xEA]);
    c.setBankRegister(0, 0x17, [0x40]);   // I2C_MST_STATUS: SLV4_DONE
    if (setup) setup(c);
    const sensor = new ICM20948Full(c);
    await sensor.init();
    return [c, sensor];
}

async function rejects(promise) {
    try { await promise; return false; } catch (e) { return true; }
}

async function main() {
    const [c, sensor] = await make();
    checkTrue('init', true);

    const expectedInit = [
        [0x7F, 0x00], [0x00], [0x06, 0x80], [0x06, 0x01], [0x07, 0x00],
        [0x7F, 0x20], [0x01, 0x19], [0x7F, 0x00],
        [0x7F, 0x20], [0x14, 0x19], [0x7F, 0x00],
        [0x7F, 0x20], [0x00, 0x0A], [0x7F, 0x00],
        [0x7F, 0x20], [0x10, 0x00], [0x7F, 0x00],
        [0x7F, 0x20], [0x11, 0x0A], [0x7F, 0x00],
    ];
    checkTrue('init_writes', c.writes.length === expectedInit.length &&
        c.writes.every((w, i) => eq(w, expectedInit[i])));
    checkTrue('init_bank_zero', c.bank === 0);

    c.setBankRegister(0, 0x2D, [...s16(16384), ...s16(-8192), ...s16(4096)]);
    let [ax, ay, az] = await sensor.accel();
    checkTrue('accel_x', near(ax, 9.80665));
    checkTrue('accel_y', near(ay, -4.903325));
    checkTrue('accel_z', near(az, 2.4516625));

    c.setBankRegister(0, 0x33, [...s16(131), ...s16(-131), ...s16(262)]);
    const [gx, gy, gz] = await sensor.gyro();
    checkTrue('gyro_x', near(gx, Math.PI / 180));
    checkTrue('gyro_y', near(gy, -Math.PI / 180));
    checkTrue('gyro_z', near(gz, 2 * Math.PI / 180));

    await sensor.configureGyro(2);
    checkTrue('configure_gyro_writes', eq(c.writesIn(2).slice(-1)[0], [0x01, 0x1D]));
    c.setBankRegister(0, 0x33, [...s16(328), 0, 0, 0, 0]);
    checkTrue('configure_gyro_sensitivity', near((await sensor.gyro())[0], 10 * Math.PI / 180));

    await sensor.configureAccel(1);
    checkTrue('configure_accel_writes', eq(c.writesIn(2).slice(-1)[0], [0x14, 0x1B]));
    c.setBankRegister(0, 0x2D, [...s16(8192), 0, 0, 0, 0]);
    checkTrue('configure_accel_sensitivity', near((await sensor.accel())[0], 9.80665));

    await sensor.configureFilters(5, 2, false, true);
    let w2 = c.writesIn(2);
    checkTrue('configure_filters', eq(w2[w2.length - 2], [0x01, (5 << 3) | (2 << 1)]) &&
        eq(w2[w2.length - 1], [0x14, (2 << 3) | (1 << 1) | 1]));
    await sensor.configureFilters(3, 3, true, true);

    await sensor.configureSampleRate(9, 0x123);
    w2 = c.writesIn(2);
    checkTrue('configure_sample_rate', eq(w2[w2.length - 3], [0x00, 9]) && eq(w2[w2.length - 2], [0x10, 0x01]) &&
        eq(w2[w2.length - 1], [0x11, 0x23]));
    checkTrue('bank_zero_after_config', c.bank === 0);

    c.setBankRegister(0, 0x2D, [...s16(8192), ...s16(0), ...s16(0), ...s16(262), ...s16(0), ...s16(0), ...s16(340)]);
    const [ra, rg, rt] = await sensor.readAll();
    checkTrue('read_all_accel', near(ra[0], 9.80665));
    checkTrue('read_all_gyro', near(rg[0], (262 / 32.8) * Math.PI / 180));
    checkTrue('read_all_temp', near(rt, 340 / 333.87 + 21.0));

    c.setBankRegister(0, 0x39, s16(340));
    checkTrue('temperature', near(await sensor.temperature(), 340 / 333.87 + 21.0));

    c.setBankRegister(0, 0x2D, [...s16(100), ...s16(-200), ...s16(300)]);
    checkTrue('accel_raw', (await sensor.accelRaw()).join() === '100,-200,300');
    c.setBankRegister(0, 0x33, [...s16(-50), ...s16(60), ...s16(-70)]);
    checkTrue('gyro_raw', (await sensor.gyroRaw()).join() === '-50,60,-70');

    c.setBankRegister(0, 0x1A, [0x01]);
    checkTrue('data_ready_true', (await sensor.dataReady()) === true);
    c.setBankRegister(0, 0x1A, [0x00]);
    checkTrue('data_ready_false', (await sensor.dataReady()) === false);

    await sensor.setSensors(false, true);
    checkTrue('set_sensors_accel_off', eq(c.writes[c.writes.length - 1], [0x07, 0x38]));
    await sensor.setSensors(true, false);
    checkTrue('set_sensors_gyro_off', eq(c.writes[c.writes.length - 1], [0x07, 0x07]));
    await sensor.setSensors(true, true);
    checkTrue('set_sensors_on', eq(c.writes[c.writes.length - 1], [0x07, 0x00]));

    await sensor.setSleep(true);
    checkTrue('set_sleep_true', eq(c.writes[c.writes.length - 1], [0x06, 0x41]));
    await sensor.setSleep(false);
    checkTrue('set_sleep_false', eq(c.writes[c.writes.length - 1], [0x06, 0x01]));

    await sensor.setGyroOffset(Math.PI / 180, 0, -Math.PI / 180);
    checkTrue('set_gyro_offset', eq(c.writesIn(2).slice(-1)[0], [0x03, 0x00, 0x21, 0x00, 0x00, 0xFF, 0xDF]));

    await sensor.setAccelOffset(0x1234, -1, 0);
    const w1 = c.writesIn(1);
    checkTrue('set_accel_offset_x', eq(w1[w1.length - 3], [0x14, 0x24, 0x68]));
    checkTrue('set_accel_offset_y', eq(w1[w1.length - 2], [0x17, 0xFF, 0xFE]));
    c.setBankRegister(1, 0x14, [0x24, 0x68, 0, 0xFF, 0xFE, 0, 0x00, 0x02]);
    checkTrue('get_accel_offset', (await sensor.getAccelOffset()).join() === '4660,-1,1');
    checkTrue('bank_zero_after_offsets', c.bank === 0);

    c.setBankRegister(1, 0x28, [0xF6]);
    checkTrue('read_timebase_correction', near(await sensor.readTimebaseCorrection(), -10 * 0.079));

    c.setBankRegister(0, 0x70, [0x03, 0x45]);
    checkTrue('fifo_count', (await sensor.fifoCount()) === 0x345);
    c.setBankRegister(0, 0x70, [0x00, 0x02]);
    c.setBankRegister(0, 0x72, [0xAA, 0xBB]);
    checkTrue('read_fifo', eq(await sensor.readFifo(), [0xAA, 0xBB]));
    checkTrue('read_fifo_count', eq(await sensor.readFifo(1), [0xAA]));
    c.setBankRegister(0, 0x70, [0x00, 0x00]);
    checkTrue('read_fifo_empty', (await sensor.readFifo()).length === 0);

    await sensor.enableFifo(true, true, false);
    checkTrue('enable_fifo_sources', c.wrote(0, [0x67, 0x1E]));
    checkTrue('enable_fifo_user_ctrl', c.wrote(0, [0x03, 0x40]));
    let w0 = c.writesIn(0);
    checkTrue('enable_fifo_reset', eq(w0[w0.length - 2], [0x68, 0x1F]) && eq(w0[w0.length - 1], [0x68, 0x00]));
    await sensor.setFifoMode(true);
    checkTrue('set_fifo_mode_snapshot', eq(c.writes[c.writes.length - 1], [0x69, 0x1F]));
    await sensor.setFifoMode(false);
    checkTrue('set_fifo_mode_stream', eq(c.writes[c.writes.length - 1], [0x69, 0x00]));

    await sensor.setInterruptPin(true, false, true, true);
    checkTrue('set_interrupt_pin', eq(c.writes[c.writes.length - 1], [0x0F, 0xB0]));
    await sensor.enableInterrupt(ICM20948Full.SOURCE_DATA_READY);
    checkTrue('enable_data_ready', eq(c.writes[c.writes.length - 1], [0x11, 0x01]));
    await sensor.enableInterrupt(ICM20948Full.SOURCE_PLL_READY);
    checkTrue('enable_pll', eq(c.writes[c.writes.length - 1], [0x10, 0x04]));
    await sensor.disableInterrupt(ICM20948Full.SOURCE_PLL_READY);
    checkTrue('disable_pll', eq(c.writes[c.writes.length - 1], [0x10, 0x00]));
    await sensor.enableInterrupt(ICM20948Full.SOURCE_FIFO_OVERFLOW);
    checkTrue('enable_fifo_overflow', eq(c.writes[c.writes.length - 1], [0x12, 0x1F]));

    c.setBankRegister(0, 0x19, [0x09, 0x01, 0x02]);
    checkTrue('poll_interrupt', (await sensor.pollInterrupt()) === (0x01 | 0x02 | 0x04 | 0x10));
    c.setBankRegister(0, 0x19, [0x04, 0x00, 0x00]);
    checkTrue('poll_interrupt_pll', (await sensor.pollInterrupt()) === 0x08);

    await sensor.setWakeOnMotion(200);
    checkTrue('wom_thr', c.wrote(2, [0x13, 50]));
    checkTrue('wom_ctrl', c.wrote(2, [0x12, 0x03]));
    checkTrue('wom_int_enable', eq(c.writesIn(0).slice(-1)[0], [0x10, 0x08]));

    c.setBankRegister(0, 0x06, [0x01]);
    await sensor.setLowPower(true, true, 2, 3);
    w0 = c.writesIn(0);
    checkTrue('low_power_lp_config', c.wrote(0, [0x05, 0x70]));
    checkTrue('low_power_lp_en_last', eq(w0[w0.length - 1], [0x06, 0x21]));
    checkTrue('low_power_averaging', c.wrote(2, [0x15, 0x02]) && c.wrote(2, [0x02, 0x03]));
    c.log.length = 0;
    await sensor.configureGyro(0);
    w0 = c.writesIn(0);
    checkTrue('lp_guard', eq(w0[0], [0x06, 0x01]) && eq(w0[w0.length - 1], [0x06, 0x21]));
    await sensor.setLowPower(false, false);
    checkTrue('low_power_off', (c.get(0, 0x06) & 0x20) === 0);

    // Magnetometer.
    c.setBankRegister(3, 0x17, [0x09]);   // slave-4 read returns WIA2 = 0x09
    await sensor.enableMag();
    checkTrue('enable_mag_bank_zero', c.bank === 0);
    checkTrue('enable_mag_i2c_mst_ctrl', c.wrote(3, [0x01, 0x07]));
    checkTrue('enable_mag_reset', c.wrote(3, [0x16, 0x01]) && c.wrote(3, [0x14, 0x32]));
    checkTrue('enable_mag_mode', c.wrote(3, [0x16, 0x08]) && c.wrote(3, [0x14, 0x31]));
    const w3 = c.writesIn(3);
    checkTrue('enable_mag_slv0', eq(w3[w3.length - 3], [0x03, 0x8C]) && eq(w3[w3.length - 2], [0x04, 0x10]) &&
        eq(w3[w3.length - 1], [0x05, 0x89]));
    checkTrue('enable_mag_user_ctrl', (c.get(0, 0x03) & 0x20) === 0x20);

    c.setBankRegister(0, 0x3B, [0x01, ...s16le(1000), ...s16le(-500), ...s16le(250), 0x00, 0x00]);
    const [mx, my, mz] = await sensor.mag();
    checkTrue('mag_x', near(mx, 150.0));
    checkTrue('mag_y', near(my, -75.0));
    checkTrue('mag_z', near(mz, 37.5));
    checkTrue('mag_raw', (await sensor.magRaw()).join() === '1000,-500,250');
    checkTrue('mag_data_ready', (await sensor.magDataReady()) === true);
    c.setBankRegister(0, 0x3B, [0x00]);
    checkTrue('mag_data_not_ready', (await sensor.magDataReady()) === false);
    c.setBankRegister(0, 0x3B, [0x01, ...s16le(1), ...s16le(2), ...s16le(3), 0x00, 0x08]);
    checkTrue('mag_overflow_raises', await rejects(sensor.mag()));
    checkTrue('mag_raw_ignores_overflow', (await sensor.magRaw()).join() === '1,2,3');

    await sensor.magSetMode(2);
    checkTrue('mag_set_mode', c.wrote(3, [0x16, 0x02]));

    c.setBankRegister(3, 0x17, [0x5A]);
    checkTrue('aux_read_byte', (await sensor.auxReadByte(0x1E, 0x0A)) === 0x5A);
    checkTrue('aux_read_addr', c.wrote(3, [0x13, 0x9E]));
    await sensor.auxWriteByte(0x1E, 0x0B, 0xA5);
    checkTrue('aux_write', c.wrote(3, [0x13, 0x1E]) && c.wrote(3, [0x16, 0xA5]));
    checkTrue('bank_zero_after_aux', c.bank === 0);

    const [, bad] = await make((m) => m.setBankRegister(3, 0x17, [0x00]));
    checkTrue('wia2_mismatch_raises', await rejects(bad.enableMag()));

    const [, nack] = await make((m) => m.setBankRegister(0, 0x17, [0x10]));
    checkTrue('aux_nack_raises', await rejects(nack.auxReadByte(0x0C, 0x01)));

    const [fresh, freshSensor] = await make();
    checkTrue('mag_not_enabled_raises', await rejects(freshSensor.mag()));
    checkTrue('mag_raw_not_enabled_raises', await rejects(freshSensor.magRaw()));
    checkTrue('mag_data_ready_not_enabled_raises', await rejects(freshSensor.magDataReady()));

    const [st0, st1] = await freshSensor.selfTest();
    checkTrue('self_test_zero_on_constant', st0.every((v) => v === 0) && st1.every((v) => v === 0));
    checkTrue('self_test_bank_zero', fresh.bank === 0);

    await freshSensor.reset();
    checkTrue('reset', eq(fresh.writes[fresh.writes.length - 1], [0x06, 0x80]));

    // WHO_AM_I mismatch rejects the first call.
    const wrong = new BankedMock();
    wrong.setBankRegister(0, 0x00, [0x00]);
    const wrongSensor = new ICM20948Minimal(wrong);
    checkTrue('who_am_i_mismatch_raises', await rejects(wrongSensor.init()));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main();
