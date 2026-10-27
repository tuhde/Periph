'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LSM303Minimal, LSM303Full, LSM303Source } = require('../../packages/periph/src/chips/accelerometer/lsm303');

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function lastValue(mock, reg) {
    const writes = mock.writes.filter(w => w.length === 2 && w[0] === reg);
    return writes.length ? writes[writes.length - 1][1] : undefined;
}

async function expectError(label, fn) {
    let err = null;
    try { await fn(); } catch (e) { err = e; }
    checkTrue(label, err instanceof Error);
}

function makeMocks() {
    const accel = new I2CConnectionMock();
    const mag = new I2CConnectionMock();
    mag.setRegister(0x0A, [0x48, 0x34, 0x33]);
    accel.setRegister(0x28 | 0x80, [0x10, 0x00, 0x20, 0x00, 0x30, 0x00]);
    mag.setRegister(0x03, [0x04, 0x4C, 0x03, 0xD4, 0xFB, 0xB4]);
    return {accel, mag};
}

(async () => {
    {
        const {accel, mag} = makeMocks();
        const chip = new LSM303Minimal(accel, mag);
        await chip.init();
        checkTrue('construct_minimal', true);
        checkTrue('init_ctrl_reg4_default', lastValue(accel, 0x23) === 0x88);
        checkTrue('init_ctrl_reg1_default', lastValue(accel, 0x20) === 0x57);
        checkTrue('init_mag_gain_130ut', lastValue(mag, 0x01) === 0x20);
        checkTrue('init_mag_continuous', lastValue(mag, 0x02) === 0x00);

        const a = await chip.readAcceleration();
        checkTrue('acceleration_x', Math.abs(a.x - 0.001) < 1e-9);
        checkTrue('acceleration_y', Math.abs(a.y - 0.002) < 1e-9);
        checkTrue('acceleration_z', Math.abs(a.z - 0.003) < 1e-9);

        const m = await chip.readMagnetic();
        checkTrue('magnetic_x', Math.abs(m.x - 100) < 1e-6);
        checkTrue('magnetic_y', Math.abs(m.y + 100) < 1e-6);
        checkTrue('magnetic_z', Math.abs(m.z - 100) < 1e-6);

        let err = null;
        try { const bad = new LSM303Minimal(new I2CConnectionMock(), new I2CConnectionMock()); await bad.init(); }
        catch (e) { err = e; }
        checkTrue('identity_mismatch_throws', err instanceof Error && /identity/.test(err.message));
    }

    {
        const {accel, mag} = makeMocks();
        const chip = new LSM303Full(accel, mag);
        await chip.init();

        await chip.setRange(4);
        let a = await chip.readAcceleration();
        checkTrue('set_range_4g_sensitivity', Math.abs(a.x - 0.002) < 1e-9);
        checkTrue('set_range_4g_fs_bits', (lastValue(accel, 0x23) & 0x30) === 0x10);

        await chip.setDataRate(400);
        checkTrue('set_data_rate_400hz', (lastValue(accel, 0x20) >> 4) === 0x07);
        await expectError('set_data_rate_5376_rejected_in_hr', () => chip.setDataRate(5376));

        await chip.setMode(1);
        checkTrue('set_mode_normal_hr_clear', (lastValue(accel, 0x23) & 0x08) === 0);
        await chip.setRange(2);

        await chip.setMotionInterrupt(1, 0x3F, 0.5, 0, true);
        checkTrue('motion_threshold_code', lastValue(accel, 0x32) === 32);
        checkTrue('motion_and_mode', (lastValue(accel, 0x30) & 0x80) === 0x80);

        await chip.setFreeFall(1, 0.35, 30);
        checkTrue('free_fall_cfg', lastValue(accel, 0x30) === 0x95);

        await chip.setClick(0, 0x04, 0.5, 10, 20, 50);
        checkTrue('click_double_z', (lastValue(accel, 0x38) & 0x20) === 0x20);

        await chip.enableInterrupt(LSM303Source.SOURCE_DATA_READY, 1);
        checkTrue('route_data_ready_int1', (lastValue(accel, 0x22) & 0x10) === 0x10);
        await chip.enableInterrupt(LSM303Source.SOURCE_CLICK, 2);
        checkTrue('route_click_int2', (lastValue(accel, 0x25) & 0x80) === 0x80);
        await expectError('mag_drdy_not_routable', () => chip.enableInterrupt(LSM303Source.SOURCE_MAG_DATA_READY, 1));

        await chip.setMagRange(470);
        checkTrue('set_mag_range_470ut', lastValue(mag, 0x01) === 0xA0);

        mag.setRegister(0x03, [0x01, 0x90, 0x01, 0x8F, 0x01, 0x8C]);
        const m = await chip.readMagnetic();
        checkTrue('mag_gain_470ut_x', Math.abs(m.x - 100) < 1e-6);

        await chip.enableTemperature(true);
        await chip.setMagDataRate(30);
        checkTrue('set_mag_data_rate_30hz', (lastValue(mag, 0x00) & 0x1C) === 0x14);
        checkTrue('set_mag_data_rate_keeps_temp', (lastValue(mag, 0x00) & 0x80) === 0x80);

        const id = await chip.readMagIdentity();
        checkTrue('mag_identity', id[0] === 0x48 && id[1] === 0x34 && id[2] === 0x33);

        mag.setRegister(0x31, [0x00, 0x80]);
        checkTrue('temperature_delta', Math.abs((await chip.readTemperatureDelta()) - 1.0) < 1e-9);

        const raw = await chip.readAccelerationRaw();
        checkTrue('read_acceleration_raw_ints', Number.isInteger(raw.x) && Number.isInteger(raw.y) && Number.isInteger(raw.z));
        checkTrue('read_status_zero', (await chip.readStatus()) === 0);
        checkTrue('poll_mag_not_ready', ((await chip.pollInterrupt()) & LSM303Source.SOURCE_MAG_DATA_READY) === 0);

        await chip.reboot();
        checkTrue('reboot', true);
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed ? 1 : 0);
})();
