'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { QMI8658AFull, QMI8658AMinimal, QMI8658ASource } = require('../../packages/periph/src/chips/imu/qmi8658a');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function close(a, b, tol = 1e-3) { return Math.abs(a - b) <= tol; }
const G = 9.80665;
const D2R = Math.PI / 180;

// Encode signed 16-bit values as little-endian bytes.
function le(...vals) {
    const out = [];
    for (const v of vals) { const u = v & 0xFFFF; out.push(u & 0xFF, u >> 8); }
    return out;
}

// I2C mock that models the CTRL9 handshake, the self-test Avail flag and FIFO_DATA.
class QmiMock extends I2CConnectionMock {
    constructor() {
        super();
        this.registers.set(0x00, 0x05);
        this.fifo = [];
        this.cmds = [];   // {cmd, cal: [8 bytes]}
    }

    async write(data) {
        await super.write(data);
        const buf = Buffer.from(data);
        if (buf.length === 2 && (buf[0] === 0x03 || buf[0] === 0x04)) {
            const st = this.registers.get(0x2D) || 0;
            this.registers.set(0x2D, (buf[1] & 0x80) ? (st | 0x01) : (st & ~0x01));
        }
        if (buf.length === 2 && buf[0] === 0x0A) {
            const st = this.registers.get(0x2D) || 0;
            if (buf[1]) {
                const cal = [];
                for (let i = 0; i < 8; i++) cal.push(this.registers.get(0x0B + i) || 0);
                this.cmds.push({ cmd: buf[1], cal });
                this.registers.set(0x2D, st | 0x80);
            } else {
                this.registers.set(0x2D, st & ~0x80);
            }
        }
    }

    async writeRead(data, n) {
        if (data[0] === 0x17) {
            this.writes.push(Buffer.from(data));
            const out = Buffer.alloc(n);
            for (let i = 0; i < n; i++) out[i] = this.fifo.length ? this.fifo.shift() : 0;
            return out;
        }
        return super.writeRead(data, n);
    }

    last(reg) {
        let v = -1;
        for (const w of this.writes) if (w.length === 2 && w[0] === reg) v = w[1];
        return v;
    }
    wrote(bytes) { return this.writes.some((w) => w.equals(Buffer.from(bytes))); }
}

async function make(full = true) {
    const conn = new QmiMock();
    const s = full ? new QMI8658AFull(conn) : new QMI8658AMinimal(conn);
    await s.init();
    return [conn, s];
}

async function main() {
    const [c, s] = await make();

    // init sequence
    const init = c.writes.filter((w) => w.length === 2);
    checkTrue('init_reset', init[0].equals(Buffer.from([0x60, 0xB0])));
    checkTrue('init_ctrl1', c.wrote([0x02, 0x40]));
    checkTrue('init_ctrl8', c.wrote([0x09, 0x80]));
    checkTrue('init_ctrl2', c.wrote([0x03, 0x05]));
    checkTrue('init_ctrl3', c.wrote([0x04, 0x45]));
    checkTrue('init_ctrl5', c.wrote([0x06, 0x77]));
    checkTrue('init_ctrl7', init[init.length - 1].equals(Buffer.from([0x08, 0x03])));

    // WHO_AM_I mismatch
    const bad = new I2CConnectionMock();
    const badSensor = new QMI8658AMinimal(bad);
    let threw = false;
    try { await badSensor.init(); } catch (e) { threw = true; }
    checkTrue('who_am_i_mismatch_rejects', threw);

    // accel / gyro conversion
    c.setRegister(0x35, le(16384, -8192, 0));
    let [x, y, z] = await s.accel();
    checkTrue('accel', close(x, G) && close(y, -G / 2) && close(z, 0));
    c.setRegister(0x3B, le(128, -256, 64));
    [x, y, z] = await s.gyro();
    checkTrue('gyro', close(x, D2R, 1e-6) && close(y, -2 * D2R, 1e-6) && close(z, 0.5 * D2R, 1e-6));

    // raw / read_all / temperature
    c.setRegister(0x33, le(0x1980, 2048, 0, -2048, 16, 32, -64));
    checkTrue('accel_raw', JSON.stringify(await s.accelRaw()) === '[2048,0,-2048]');
    checkTrue('gyro_raw', JSON.stringify(await s.gyroRaw()) === '[16,32,-64]');
    const [a, g, t] = await s.readAll();
    checkTrue('read_all_temp', close(t, 25.5));
    checkTrue('read_all_values', close(a[0], 2048 / 16384 * G) && close(g[2], -64 / 128 * D2R, 1e-6));
    checkTrue('temperature', close(await s.temperature(), 25.5));

    // timestamp / data_ready
    c.setRegister(0x30, [1, 2, 3]);
    checkTrue('timestamp', (await s.timestamp()) === 0x030201);
    c.setRegister(0x2E, [0x00]);
    checkTrue('data_ready_false', !(await s.dataReady()));
    c.setRegister(0x2E, [0x02]);
    checkTrue('data_ready_true', await s.dataReady());

    // configure
    await s.configureAccel(2, 4);
    checkTrue('configure_accel', c.last(0x03) === 0x24);
    c.setRegister(0x35, le(4096, 0, 0));
    checkTrue('accel_after_range', close((await s.accel())[0], G));
    await s.configureGyro(7, 3);
    checkTrue('configure_gyro', c.last(0x04) === 0x73);
    c.setRegister(0x3B, le(16, 0, 0));
    checkTrue('gyro_after_range', close((await s.gyro())[0], D2R, 1e-6));
    let range = false;
    try { await s.configureAccel(4, 5); } catch (e) { range = e instanceof RangeError; }
    checkTrue('configure_accel_range_throws', range);
    await s.configureAccel();
    await s.configureGyro();
    await s.configureFilters(true, 3, false, 1);
    checkTrue('configure_filters', c.last(0x06) === 0x27);

    // sensors / power
    await s.setSensors(true, false);
    checkTrue('set_sensors_accel', c.last(0x08) === 0x01);
    await s.setSensors(true, true, true);
    checkTrue('set_sensors_snooze', c.last(0x08) === 0x13);
    await s.setSensors(true, true);
    await s.setPowerDown(true);
    checkTrue('power_down', c.last(0x02) === 0x41 && c.last(0x08) === 0x00);
    await s.setPowerDown(false);
    checkTrue('wake', c.last(0x02) === 0x40);
    await s.setSensors(true, true);

    // sync sample
    c.cmds.length = 0;
    await s.setSyncSample(true);
    checkTrue('sync_gating_cmd', c.cmds[c.cmds.length - 1].cmd === 0x12 && c.cmds[c.cmds.length - 1].cal[0] === 1);
    checkTrue('sync_ctrl7', c.wrote([0x08, 0x83]));
    c.registers.set(0x2D, 0x03);
    c.setRegister(0x35, le(16384, 0, 0));
    checkTrue('sync_accel', close((await s.accel())[0], G));
    await s.setSyncSample(false);
    checkTrue('sync_off_ctrl7', c.last(0x08) === 0x03);
    checkTrue('sync_gating_restore', c.cmds[c.cmds.length - 1].cmd === 0x12 && c.cmds[c.cmds.length - 1].cal[0] === 0);

    // FIFO
    await s.configureFifo(2, 1, 16);
    checkTrue('fifo_ctrl', c.wrote([0x14, 0x06]) && c.wrote([0x13, 0x10]));
    c.setRegister(0x16, [0x00]);
    c.setRegister(0x15, [12]);
    checkTrue('fifo_count', (await s.fifoCount()) === 24 && (await s.fifoStatus()) === 0);
    c.fifo = Array.from({ length: 24 }, (_, i) => i);
    c.cmds.length = 0;
    let data = await s.readFifo();
    checkTrue('read_fifo_data', data.length === 24 && data.every((v, i) => v === i));
    checkTrue('read_fifo_req', c.cmds.length === 1 && c.cmds[0].cmd === 0x05);
    checkTrue('read_fifo_ai', c.wrote([0x02, 0x00]) && c.last(0x02) === 0x40);
    checkTrue('read_fifo_rd_mode_cleared', c.last(0x14) === 0x06);
    c.fifo = Array.from({ length: 24 }, (_, i) => i);
    data = await s.readFifo(10);
    checkTrue('read_fifo_limit', data.length === 10 && data[9] === 9);
    await s.resetFifo();
    checkTrue('reset_fifo', c.cmds[c.cmds.length - 1].cmd === 0x04);
    await s.configureFifo(0, 3, 0);
    checkTrue('fifo_bypass_drdy', c.last(0x08) === 0x03);

    // motion engines
    c.cmds.length = 0;
    await s.configureAnyMotion(0.25, 3, 0b111, false);
    checkTrue('motion_two_sets', c.cmds.length === 2 && c.cmds[0].cmd === 0x0E && c.cmds[1].cmd === 0x0E);
    checkTrue('motion_set1', c.cmds[0].cal[0] === 8 && c.cmds[0].cal[3] === 4 && c.cmds[0].cal[6] === 0xF7 && c.cmds[0].cal[7] === 0x10);
    checkTrue('motion_set2', c.cmds[1].cal[0] === 3 && c.cmds[1].cal[1] === 30 && c.cmds[1].cal[2] === 150 &&
        c.cmds[1].cal[4] === (600 & 0xFF) && c.cmds[1].cal[5] === (600 >> 8) && c.cmds[1].cal[7] === 0x20);
    checkTrue('motion_sensors_restored', c.last(0x08) === 0x03 && c.wrote([0x08, 0x00]));
    await s.configureSignificantMotion(100, 300);
    const lastMotion = c.cmds[c.cmds.length - 1];
    checkTrue('sig_motion_set2', lastMotion.cal[2] === 100 && lastMotion.cal[4] === (300 & 0xFF) && lastMotion.cal[5] === 1);
    await s.enableMotion(true, true, false);
    checkTrue('enable_motion', c.last(0x09) === 0x86);
    await s.enableMotion();
    checkTrue('disable_motion', c.last(0x09) === 0x80);

    // tap
    c.cmds.length = 0;
    await s.configureTap();
    checkTrue('tap_two_sets', c.cmds.length === 2 && c.cmds[0].cmd === 0x0C);
    checkTrue('tap_set1', c.cmds[0].cal[0] === 20 && c.cmds[0].cal[1] === 0 && c.cmds[0].cal[2] === 50 &&
        c.cmds[0].cal[4] === 250 && c.cmds[0].cal[7] === 0x10);
    checkTrue('tap_set2', c.cmds[1].cal[0] === 8 && c.cmds[1].cal[1] === 32 && c.cmds[1].cal[2] === 0x33 &&
        c.cmds[1].cal[3] === 0x03 && c.cmds[1].cal[4] === 0x9A && c.cmds[1].cal[5] === 0x01 && c.cmds[1].cal[7] === 0x20);
    await s.enableTap(true);
    checkTrue('enable_tap', c.last(0x09) === 0x81);
    c.setRegister(0x59, [0xA2]);
    checkTrue('tap_status', JSON.stringify(await s.tapStatus()) === '[2,2,true]');
    await s.enableTap(false);

    // pedometer
    c.cmds.length = 0;
    await s.configurePedometer();
    checkTrue('ped_two_sets', c.cmds.length === 2 && c.cmds[0].cmd === 0x0D);
    checkTrue('ped_set1', c.cmds[0].cal[0] === 50 && c.cmds[0].cal[2] === 205 && c.cmds[0].cal[4] === 102 && c.cmds[0].cal[7] === 0x10);
    checkTrue('ped_set2', c.cmds[1].cal[0] === 200 && c.cmds[1].cal[2] === 20 && c.cmds[1].cal[3] === 10 &&
        c.cmds[1].cal[5] === 4 && c.cmds[1].cal[7] === 0x20);
    await s.enablePedometer(true);
    checkTrue('enable_pedometer', c.last(0x09) === 0x90);
    c.setRegister(0x5A, [0x34, 0x12, 0x00]);
    checkTrue('step_count', (await s.stepCount()) === 0x1234);
    await s.resetStepCount();
    checkTrue('reset_step_count', c.cmds[c.cmds.length - 1].cmd === 0x0F);
    await s.enablePedometer(false);

    // wake-on-motion
    c.cmds.length = 0;
    await s.setWakeOnMotion(100, 2, 1, 5);
    checkTrue('wom_ctrl2', c.wrote([0x03, 0x0C]));
    let cmd = c.cmds[c.cmds.length - 1];
    checkTrue('wom_cmd', cmd.cmd === 0x08 && cmd.cal[0] === 100 && cmd.cal[1] === 0xC5);
    checkTrue('wom_int2', c.wrote([0x08, 0x01]) && (c.last(0x02) & 0x10) !== 0);
    await s.setWakeOnMotion(0);
    cmd = c.cmds[c.cmds.length - 1];
    checkTrue('wom_exit', cmd.cmd === 0x08 && cmd.cal[0] === 0 && (c.last(0x02) & 0x18) === 0);
    await s.configureAccel();
    await s.setSensors(true, true);

    // offsets
    c.cmds.length = 0;
    await s.setAccelOffset(G, 0, -G);
    cmd = c.cmds[c.cmds.length - 1];
    checkTrue('accel_offset', cmd.cmd === 0x09 && cmd.cal[0] === 0x00 && cmd.cal[1] === 0x10 && cmd.cal[4] === 0x00 && cmd.cal[5] === 0xF0);
    await s.setGyroOffset(D2R, 0, 0);
    cmd = c.cmds[c.cmds.length - 1];
    checkTrue('gyro_offset', cmd.cmd === 0x0A && cmd.cal[0] === 32 && cmd.cal[1] === 0);

    // calibration-on-demand and gains
    c.setRegister(0x46, [0x00]);
    let [ok, status] = await s.calibrateGyro();
    checkTrue('calibrate_gyro', ok && status === 0 && c.cmds[c.cmds.length - 1].cmd === 0xA2);
    c.setRegister(0x46, [0x01]);
    [ok, status] = await s.calibrateGyro();
    checkTrue('calibrate_gyro_fail', !ok && status === 1);
    c.setRegister(0x51, le(1000, 2000, 3000));
    checkTrue('get_gyro_gains', JSON.stringify(await s.getGyroGains()) === '[1000,2000,3000]');
    await s.setGyroGains(1000, 2000, 3000);
    cmd = c.cmds[c.cmds.length - 1];
    checkTrue('set_gyro_gains', cmd.cmd === 0xAA && cmd.cal[0] === 0xE8 && cmd.cal[1] === 0x03 && cmd.cal[4] === 0xB8 && cmd.cal[5] === 0x0B);

    // device info
    c.setRegister(0x01, [0x7C]);
    c.setRegister(0x49, [1, 2, 0, 3]);
    c.setRegister(0x4B, [3]);
    c.setRegister(0x51, [1, 2, 3, 4, 5, 6]);
    const info = await s.deviceInfo();
    checkTrue('device_info', info.revision === 0x7C && info.firmware.equals(Buffer.from([1, 2, 3])) &&
        info.usid.equals(Buffer.from([1, 2, 3, 4, 5, 6])) && c.cmds[c.cmds.length - 1].cmd === 0x10);

    // pull-ups
    await s.setPullups(false, true, true, false);
    cmd = c.cmds[c.cmds.length - 1];
    checkTrue('set_pullups', cmd.cmd === 0x11 && cmd.cal[0] === 0x09);

    // self-test
    c.setRegister(0x2D, [0x00]);
    c.setRegister(0x51, le(2048, 2048, -2048));
    let [pass, vals] = await s.selfTestAccel();
    checkTrue('self_test_accel', pass && close(vals[0], G));
    c.setRegister(0x51, le(16 * 400, 16 * 400, 16 * 400));
    [pass, vals] = await s.selfTestGyro();
    checkTrue('self_test_gyro', pass && close(vals[0], 400 * D2R));
    c.setRegister(0x51, le(16 * 100, 16 * 400, 16 * 400));
    [pass] = await s.selfTestGyro();
    checkTrue('self_test_gyro_fail', !pass);
    c.setRegister(0x2D, [0x00]);

    // interrupts
    await s.setInterruptPins(1, 1);
    checkTrue('int_pins_ctrl8', (c.last(0x09) & 0x40) !== 0);
    await s.enableInterrupt(QMI8658ASource.TAP);
    checkTrue('int_tap_engine_int1', (c.last(0x09) & 0x01) !== 0 && (c.last(0x02) & 0x08) !== 0);
    await s.enableInterrupt(QMI8658ASource.DATA_READY);
    checkTrue('int_drdy_int2', (c.last(0x02) & 0x10) !== 0);
    await s.disableInterrupt(QMI8658ASource.DATA_READY);
    checkTrue('int_drdy_off', (c.last(0x02) & 0x10) === 0 && (c.last(0x08) & 0x20) !== 0);
    await s.disableInterrupt(QMI8658ASource.TAP);
    checkTrue('int_tap_off', (c.last(0x02) & 0x08) === 0);
    await s.setInterruptPins(2, 2);
    c.setRegister(0x2E, [0x01, 0xA2]);
    c.setRegister(0x16, [0xC0]);
    const expect = QMI8658ASource.DATA_READY | QMI8658ASource.FIFO_WATERMARK | QMI8658ASource.FIFO_FULL |
        QMI8658ASource.ANY_MOTION | QMI8658ASource.SIGNIFICANT_MOTION | QMI8658ASource.TAP;
    checkTrue('poll_interrupt', (await s.pollInterrupt()) === expect);

    // on/off interrupt via polling timer
    let seen = 0;
    await s.onInterrupt((st) => { seen = st; });
    await new Promise((r) => setTimeout(r, 30));
    await s.offInterrupt();
    checkTrue('on_interrupt_polls', seen === expect);

    // reset
    await s.reset();
    checkTrue('reset', c.wrote([0x60, 0xB0]));

    console.log('===DONE: ' + passed + ' passed, ' + failed + ' failed===');
    process.exit(failed === 0 ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
