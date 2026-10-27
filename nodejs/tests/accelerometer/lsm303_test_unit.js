'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LSM303Minimal, LSM303Full } = require('../../packages/periph/src/chips/accelerometer/lsm303');

// Accel register map (mirror of the driver's, for test vectors).
const _REG_CTRL1_A     = 0x20;
const _REG_CTRL2_A     = 0x21;
const _REG_CTRL3_A     = 0x22;
const _REG_CTRL4_A     = 0x23;
const _REG_CTRL5_A     = 0x24;
const _REG_CTRL6_A     = 0x25;
const _REG_REFERENCE_A = 0x26;
const _REG_STATUS_A    = 0x27;
const _REG_OUT_BURST_A = 0xA8;          // 0x28 | auto-increment bit
const _REG_FIFO_CTRL_A = 0x2E;
const _REG_FIFO_SRC_A  = 0x2F;
const _REG_INT1_CFG_A  = 0x30;
const _REG_INT1_SRC_A  = 0x31;
const _REG_INT1_THS_A  = 0x32;
const _REG_INT1_DUR_A  = 0x33;
const _REG_INT2_CFG_A  = 0x34;
const _REG_INT2_SRC_A  = 0x35;
const _REG_INT2_THS_A  = 0x36;
const _REG_INT2_DUR_A  = 0x37;
const _REG_CLICK_CFG_A = 0x38;
const _REG_CLICK_SRC_A = 0x39;
const _REG_CLICK_THS_A = 0x3A;
const _REG_TIME_LIMIT_A = 0x3B;
const _REG_TIME_LAT_A   = 0x3C;
const _REG_TIME_WIN_A   = 0x3D;

// Mag register map.
const _REG_CRA_M     = 0x00;
const _REG_CRB_M     = 0x01;
const _REG_MR_M      = 0x02;
const _REG_OUT_M     = 0x03;           // 6-byte burst, X/Z/Y high byte first
const _REG_SR_M      = 0x09;
const _REG_IRA_M     = 0x0A;
const _REG_TEMP_H_M  = 0x31;

// Mock writes accumulate; "off" checks must look at the most recent write to a register.
function lastWriteTo(mock, reg) {
    const hits = mock.writes.filter(w => w.length >= 2 && w[0] === reg);
    return hits.length ? hits[hits.length - 1] : null;
}

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}
function checkNear(label, value, expected, eps = 1e-9) {
    checkTrue(label, Math.abs(value - expected) < eps);
}

function flushMicrotasks() {
    return new Promise(resolve => setImmediate(resolve));
}

// signed 16-bit -> [high, low] byte pair (big-endian, magnetometer order).
function s16be(value) {
    const buf = Buffer.alloc(2);
    buf.writeInt16BE(value, 0);
    return [buf[0], buf[1]];
}

// signed 16-bit -> [low, high] byte pair (little-endian, accelerometer order).
function s16le(value) {
    const buf = Buffer.alloc(2);
    buf.writeInt16LE(value, 0);
    return [buf[0], buf[1]];
}

// Preload the accelerometer burst registers with high-resolution counts.
function setAccelCounts(mock, cx, cy, cz) {
    mock.setRegister(_REG_OUT_BURST_A, [...s16le(cx << 4), ...s16le(cy << 4), ...s16le(cz << 4)]);
}

// Preload the magnetometer burst registers (X, Z, Y order on the wire).
function setMagXYZ(mock, mx, mz, my) {
    mock.setRegister(_REG_OUT_M, [...s16be(mx), ...s16be(mz), ...s16be(my)]);
}

function makeMocksWithIdentity() {
    const accel = new I2CConnectionMock();
    const mag = new I2CConnectionMock();
    mag.setRegister(_REG_IRA_M, [0x48, 0x34, 0x33]);
    return [accel, mag];
}

async function main() {
    // ------------------------------------------------------------------
    // Minimal: init sequence and primary reads
    // ------------------------------------------------------------------
    const [mock, mockMag] = makeMocksWithIdentity();
    const lsm = new LSM303Minimal(mock, mockMag);
    await flushMicrotasks();   // let the fire-and-forget _init() finish (includes busy-wait delays)
    checkTrue('construct_minimal', true);

    // Init: identity read on mag, then known-state control writes (accelerometer),
    // then CRA/CRB/MR (magnetometer). Accel write order per the spec's init sequence.
    const initWrites = mock.writes.filter(w => w.length === 2);
    const expectedInit = [
        [_REG_CTRL2_A, 0x00], [_REG_CTRL3_A, 0x00], [_REG_CTRL5_A, 0x00],
        [_REG_CTRL6_A, 0x00], [_REG_FIFO_CTRL_A, 0x00],
        [_REG_CTRL4_A, 0x88], [_REG_CTRL1_A, 0x57],
    ];
    checkTrue('init_accel_sequence', initWrites.length === expectedInit.length &&
        expectedInit.every(([reg, val], i) => initWrites[i][0] === reg && initWrites[i][1] === val));

    const magWrites = mockMag.writes.filter(w => w.length === 2);
    checkTrue('init_mag_sequence', magWrites.length === 3 &&
        magWrites[0][0] === _REG_CRA_M && magWrites[0][1] === 0x10 &&
        magWrites[1][0] === _REG_CRB_M && magWrites[1][1] === 0x20 &&
        magWrites[2][0] === _REG_MR_M && magWrites[2][1] === 0x00);
    checkTrue('init_mag_identity_read', mockMag.writes[0].length === 1 && mockMag.writes[0][0] === _REG_IRA_M);

    // readAcceleration(): high-res ±2 g, counts 1 mg/LSB. x=-2000 (-2 g), y=0, z=1000 (1 g).
    setAccelCounts(mock, -2000, 0, 1000);
    const [ax, ay, az] = await lsm.readAcceleration();
    checkNear('read_acceleration_x_minus_2g', ax, -2.0);
    checkNear('read_acceleration_y_zero', ay, 0.0);
    checkNear('read_acceleration_z_plus_1g', az, 1.0);

    // readMagnetic(): ±1.3 gauss, gains 1100 (X/Y) and 980 (Z) LSB/gauss.
    // Wire order X, Z, Y; mx=1100 -> 100 µT, mz=-980 -> -100 µT, my=550 -> 50 µT.
    setMagXYZ(mockMag, 1100, -980, 550);
    const [mx, my, mz] = await lsm.readMagnetic();
    checkNear('read_magnetic_x', mx, 100.0);
    checkNear('read_magnetic_y', my, 50.0);
    checkNear('read_magnetic_z_uses_z_gain', mz, -100.0);

    // ------------------------------------------------------------------
    // Full: accelerometer configuration
    // ------------------------------------------------------------------
    const [mock2, mock2Mag] = makeMocksWithIdentity();
    const full = new LSM303Full(mock2, mock2Mag);
    await flushMicrotasks();
    checkTrue('construct_full', true);

    await full.setRange(4);
    checkTrue('set_range_4g', lastWriteTo(mock2, _REG_CTRL4_A)[1] === 0x98);   // 0x88 | FS=01

    try { await full.setRange(5); checkTrue('set_range_invalid_throws', false); }
    catch (e) { checkTrue('set_range_invalid_throws', true); }

    await full.setDataRate(50);
    checkTrue('set_data_rate_50hz', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x47);  // 0x57 | ODR=0100

    try { await full.setDataRate(1620); checkTrue('set_data_rate_1620_highres_throws', false); }
    catch (e) { checkTrue('set_data_rate_1620_highres_throws', true); }
    try { await full.setDataRate(5376); checkTrue('set_data_rate_5376_highres_throws', false); }
    catch (e) { checkTrue('set_data_rate_5376_highres_throws', true); }
    try { await full.setDataRate(123); checkTrue('set_data_rate_invalid_throws', false); }
    catch (e) { checkTrue('set_data_rate_invalid_throws', true); }

    await full.setMode(0);
    checkTrue('set_mode_0_lpen', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x4F);    // 0x47 | LPen
    checkTrue('set_mode_0_hr_clear', lastWriteTo(mock2, _REG_CTRL4_A)[1] === 0x90);

    try { await full.setDataRate(1344); checkTrue('set_data_rate_1344_lowpower_throws', false); }
    catch (e) { checkTrue('set_data_rate_1344_lowpower_throws', true); }

    await full.setDataRate(1620);
    checkTrue('set_data_rate_1620_lowpower_ok', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x8F);

    await full.setMode(2);
    checkTrue('set_mode_2_hr', lastWriteTo(mock2, _REG_CTRL4_A)[1] === 0x98);

    // Restore 100 Hz for the duration-encoding tests below.
    await full.setDataRate(100);
    checkTrue('set_data_rate_100hz_restored', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x57);

    await full.setAxes(false, false, true);
    checkTrue('set_axes_z_only', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x54);
    await full.setAxes(true, true, true);
    checkTrue('set_axes_all_on', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x57);

    await full.powerDown();
    checkTrue('power_down_odr_zero', lastWriteTo(mock2, _REG_CTRL1_A)[1] === 0x07);
    await full.setDataRate(100);

    // Raw and scaled reads at the current range (±4 g since setRange(4)): 2 mg/LSB.
    setAccelCounts(mock2, -2000, 0, 1000);
    const [rx, ry, rz] = await full.readAccelerationRaw();
    checkTrue('read_acceleration_raw', rx === -2000 && ry === 0 && rz === 1000);
    const [fx, fy, fz] = await full.readAcceleration();
    checkNear('read_acceleration_scaled_4g', fx + fy + fz, -2.0);   // (-2000, 0, 1000) x 2 mg/LSB -> (-4, 0, 2) g

    mock2.setRegister(_REG_STATUS_A, [0x08]);
    checkTrue('new_data_available_true', (await full.newDataAvailable()) === true);
    mock2.setRegister(_REG_STATUS_A, [0x00]);
    checkTrue('new_data_available_false', (await full.newDataAvailable()) === false);

    mock2.setRegister(_REG_STATUS_A, [0x53]);
    checkTrue('read_status', (await full.readStatus()) === 0x53);

    await full.setHighPass(1, 2, true, true, false, true);
    checkTrue('set_high_pass', lastWriteTo(mock2, _REG_CTRL2_A)[1] === 0x6D);
    await full.setHighPass();
    checkTrue('set_high_pass_defaults', lastWriteTo(mock2, _REG_CTRL2_A)[1] === 0x00);

    mock2.setRegister(_REG_REFERENCE_A, [0x33]);
    checkTrue('reset_high_pass_returns_ref', (await full.resetHighPass()) === 0x33);

    await full.setReference(0xAB);
    checkTrue('set_reference', lastWriteTo(mock2, _REG_REFERENCE_A)[1] === 0xAB);

    // ------------------------------------------------------------------
    // Full: FIFO
    // ------------------------------------------------------------------
    await full.setFifo(2, 16);
    checkTrue('set_fifo_stream_wtm16', lastWriteTo(mock2, _REG_FIFO_CTRL_A)[1] === 0x90);
    checkTrue('set_fifo_enables_fifo_en', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x40) === 0x40);

    mock2.setRegister(_REG_FIFO_SRC_A, [0x85]);
    checkTrue('fifo_level', (await full.fifoLevel()) === 5);

    mock2.setRegister(_REG_FIFO_SRC_A, [0xC3]);
    const [wtm, ovrn, empty, level] = await full.readFifoStatus();
    checkTrue('read_fifo_status', wtm === true && ovrn === true && empty === false && level === 3);

    mock2.setRegister(_REG_FIFO_SRC_A, [0x02]);
    const two = await full.readFifo(2);
    checkTrue('read_fifo_count_two', two.length === 2 &&
        Math.abs(two[0][0] - (-4.0)) < 1e-9 && Math.abs(two[1][2] - 2.0) < 1e-9);
    mock2.setRegister(_REG_FIFO_SRC_A, [0x03]);
    const all = await full.readFifo();
    checkTrue('read_fifo_default_drains_fss', all.length === 3);

    await full.setFifo(0);
    checkTrue('set_fifo_bypass_clears_en', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x40) === 0x00);

    // ------------------------------------------------------------------
    // Full: inertial generators, orientation, latch, click
    // ------------------------------------------------------------------
    // Range is ±4 g and ODR 100 Hz here.
    await full.setMotionInterrupt(1, 0x2A, 1.0, 20, false);
    checkTrue('set_motion_interrupt_cfg', lastWriteTo(mock2, _REG_INT1_CFG_A)[1] === 0x2A);
    checkTrue('set_motion_interrupt_ths', lastWriteTo(mock2, _REG_INT1_THS_A)[1] === 32);   // round(1.0*128/4)
    checkTrue('set_motion_interrupt_dur', lastWriteTo(mock2, _REG_INT1_DUR_A)[1] === 2);    // round(20*100/1000)

    try { await full.setMotionInterrupt(3, 0x2A, 1.0); checkTrue('set_motion_interrupt_bad_gen_throws', false); }
    catch (e) { checkTrue('set_motion_interrupt_bad_gen_throws', true); }

    await full.setFreeFall(2, 0.35, 30);
    checkTrue('set_free_fall_cfg_0x95', lastWriteTo(mock2, _REG_INT2_CFG_A)[1] === 0x95);
    checkTrue('set_free_fall_ths', lastWriteTo(mock2, _REG_INT2_THS_A)[1] === 11);          // round(0.35*128/4)
    checkTrue('set_free_fall_dur', lastWriteTo(mock2, _REG_INT2_DUR_A)[1] === 3);

    await full.setOrientation(1, true, true);
    checkTrue('set_orientation_cfg_6d_position', lastWriteTo(mock2, _REG_INT1_CFG_A)[1] === 0xFF);
    checkTrue('set_orientation_4d_gen1', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x04) === 0x04);

    await full.setOrientation(2, false, false);
    checkTrue('set_orientation_cfg_6d_movement', lastWriteTo(mock2, _REG_INT2_CFG_A)[1] === 0x7F);
    checkTrue('set_orientation_4d_gen2_clear', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x01) === 0x00);

    await full.setLatch(1, true);
    checkTrue('set_latch_gen1', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x08) === 0x08);
    await full.setLatch(2, true);
    checkTrue('set_latch_gen2', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x02) === 0x02);
    await full.setLatch(1, false);
    checkTrue('set_latch_gen1_off', (lastWriteTo(mock2, _REG_CTRL5_A)[1] & 0x08) === 0x00);

    try { await full.setLatch(5, true); checkTrue('set_latch_bad_gen_throws', false); }
    catch (e) { checkTrue('set_latch_bad_gen_throws', true); }

    mock2.setRegister(_REG_INT1_SRC_A, [0x55]);
    checkTrue('read_interrupt_source_1', (await full.readInterruptSource(1)) === 0x55);
    mock2.setRegister(_REG_INT2_SRC_A, [0x66]);
    checkTrue('read_interrupt_source_2', (await full.readInterruptSource(2)) === 0x66);

    await full.setClick(1, 4, 0.5, 10, 20, 50);
    checkTrue('set_click_cfg_masks', lastWriteTo(mock2, _REG_CLICK_CFG_A)[1] === 0x12);     // XS=0x02 | ZD=0x10
    checkTrue('set_click_ths', lastWriteTo(mock2, _REG_CLICK_THS_A)[1] === 16);             // round(0.5*128/4)
    checkTrue('set_click_time_limit', lastWriteTo(mock2, _REG_TIME_LIMIT_A)[1] === 1);
    checkTrue('set_click_latency', lastWriteTo(mock2, _REG_TIME_LAT_A)[1] === 2);
    checkTrue('set_click_window', lastWriteTo(mock2, _REG_TIME_WIN_A)[1] === 5);

    try { await full.setClick(8); checkTrue('set_click_bad_mask_throws', false); }
    catch (e) { checkTrue('set_click_bad_mask_throws', true); }

    mock2.setRegister(_REG_CLICK_SRC_A, [0x42]);
    checkTrue('read_click_source', (await full.readClickSource()) === 0x42);

    await full.setInterruptPolarity(true);
    checkTrue('set_interrupt_polarity_active_low', (lastWriteTo(mock2, _REG_CTRL6_A)[1] & 0x02) === 0x02);
    await full.setInterruptPolarity(false);
    checkTrue('set_interrupt_polarity_active_high', (lastWriteTo(mock2, _REG_CTRL6_A)[1] & 0x02) === 0x00);

    // ------------------------------------------------------------------
    // Full: magnetometer configuration
    // ------------------------------------------------------------------
    await full.setMagRange(400);
    checkTrue('set_mag_range_400ut', lastWriteTo(mock2Mag, _REG_CRB_M)[1] === 0x80);        // GN=100

    // Gains now 450/400 LSB/gauss: mx=450 -> 100 µT, mz=400 -> 100 µT, my=-450 -> -100 µT.
    setMagXYZ(mock2Mag, 450, 400, -450);
    const [mx2, my2, mz2] = await full.readMagnetic();
    checkNear('read_magnetic_400ut_x', mx2, 100.0);
    checkNear('read_magnetic_400ut_y', my2, -100.0);
    checkNear('read_magnetic_400ut_z', mz2, 100.0);

    try { await full.setMagRange(300); checkTrue('set_mag_range_invalid_throws', false); }
    catch (e) { checkTrue('set_mag_range_invalid_throws', true); }

    await full.setMagDataRate(30);
    checkTrue('set_mag_data_rate_30hz', lastWriteTo(mock2Mag, _REG_CRA_M)[1] === 0x14);

    await full.enableTemperature(true);
    checkTrue('enable_temperature_sets_temp_en', (lastWriteTo(mock2Mag, _REG_CRA_M)[1] & 0x80) === 0x80);
    await full.setMagDataRate(15);
    checkTrue('set_mag_data_rate_preserves_temp_en', lastWriteTo(mock2Mag, _REG_CRA_M)[1] === 0x90);

    try { await full.setMagDataRate(10); checkTrue('set_mag_data_rate_invalid_throws', false); }
    catch (e) { checkTrue('set_mag_data_rate_invalid_throws', true); }

    await full.setMagMode(1);
    checkTrue('set_mag_mode_single', lastWriteTo(mock2Mag, _REG_MR_M)[1] === 0x01);
    try { await full.setMagMode(3); checkTrue('set_mag_mode_invalid_throws', false); }
    catch (e) { checkTrue('set_mag_mode_invalid_throws', true); }
    await full.setMagMode(0);

    setMagXYZ(mock2Mag, 450, 400, -450);
    const [mrx, mry, mrz] = await full.readMagneticRaw();
    checkTrue('read_magnetic_raw_reordered', mrx === 450 && mry === -450 && mrz === 400);

    mock2Mag.setRegister(_REG_SR_M, [0x01]);
    checkTrue('mag_data_ready_true', (await full.magDataReady()) === true);
    mock2Mag.setRegister(_REG_SR_M, [0x00]);
    checkTrue('mag_data_ready_false', (await full.magDataReady()) === false);

    mock2Mag.setRegister(_REG_SR_M, [0x03]);
    const [drdy, lock] = await full.readMagStatus();
    checkTrue('read_mag_status', drdy === true && lock === true);

    const identity = await full.readMagIdentity();
    checkTrue('read_mag_identity', identity.toString('hex') === '483433');

    await full.enableTemperature(false);
    checkTrue('disable_temperature', (lastWriteTo(mock2Mag, _REG_CRA_M)[1] & 0x80) === 0x00);

    // readTemperatureDelta() enables TEMP_EN on first use, then converts
    // 8 LSB/°C with a 4-bit right shift of the left-justified 12-bit value.
    mock2Mag.setRegister(_REG_TEMP_H_M, [0x01, 0x80]);       // raw16 = 384 -> 384>>4 = 24 -> 3.0 °C
    checkNear('read_temperature_delta_positive', await full.readTemperatureDelta(), 3.0);
    checkTrue('read_temperature_delta_auto_enables', (lastWriteTo(mock2Mag, _REG_CRA_M)[1] & 0x80) === 0x80);
    mock2Mag.setRegister(_REG_TEMP_H_M, [0xFF, 0x70]);       // raw16 = -144 -> -9 -> -1.125 °C
    checkNear('read_temperature_delta_negative', await full.readTemperatureDelta(), -1.125);

    // ------------------------------------------------------------------
    // Full: heading
    // ------------------------------------------------------------------
    // Flat compass: mx=100 µT, my=-100 µT -> atan2(-100, 100) = -45° -> 315°.
    setMagXYZ(mock2Mag, 450, 400, -450);
    checkNear('heading_flat_wraps_to_315', await full.heading(false), 315.0, 1e-6);
    // Tilt-compensated with a level device (az > 0): roll = pitch = 0, same result.
    setAccelCounts(mock2, 0, 0, 1000);
    checkNear('heading_tilt_compensated_level', await full.heading(), 315.0, 1e-6);
    // Wrap check: atan2(-100, -100) = -135° -> 225°.
    setMagXYZ(mock2Mag, -450, 400, -450);
    checkNear('heading_flat_wraps_to_225', await full.heading(false), 225.0, 1e-6);

    // ------------------------------------------------------------------
    // Full: interrupt routing and polling (fresh driver, empty source set)
    // ------------------------------------------------------------------
    const [mock3, mock3Mag] = makeMocksWithIdentity();
    const full3 = new LSM303Full(mock3, mock3Mag);
    await flushMicrotasks();

    await full3.enableInterrupt(LSM303Full.SOURCE_IA1, 1);
    checkTrue('enable_ia1_pin1_ctrl3', (lastWriteTo(mock3, _REG_CTRL3_A)[1] & 0x40) === 0x40);

    await full3.enableInterrupt(LSM303Full.SOURCE_IA1, 2);
    checkTrue('enable_ia1_pin2_ctrl6', (lastWriteTo(mock3, _REG_CTRL6_A)[1] & 0x40) === 0x40);

    try { await full3.enableInterrupt(LSM303Full.SOURCE_MAG_DATA_READY); checkTrue('enable_mag_drdy_rejected', false); }
    catch (e) { checkTrue('enable_mag_drdy_rejected', true); }
    try { await full3.enableInterrupt(LSM303Full.SOURCE_BOOT, 1); checkTrue('enable_boot_pin1_rejected', false); }
    catch (e) { checkTrue('enable_boot_pin1_rejected', true); }

    await full3.enableInterrupt(LSM303Full.SOURCE_BOOT, 2);
    checkTrue('enable_boot_pin2_ctrl6', (lastWriteTo(mock3, _REG_CTRL6_A)[1] & 0x10) === 0x10);
    await full3.disableInterrupt(LSM303Full.SOURCE_BOOT, 2);
    checkTrue('disable_boot_clears_ctrl6', (lastWriteTo(mock3, _REG_CTRL6_A)[1] & 0x10) === 0x00);

    try { await full3.disableInterrupt(0x0100); checkTrue('disable_unknown_source_throws', false); }
    catch (e) { checkTrue('disable_unknown_source_throws', true); }

    // Enable every register-reported source, then poll with all flags set.
    await full3.enableInterrupt(LSM303Full.SOURCE_DATA_READY, 1);
    await full3.enableInterrupt(LSM303Full.SOURCE_WATERMARK, 1);
    await full3.enableInterrupt(LSM303Full.SOURCE_OVERRUN, 1);
    await full3.enableInterrupt(LSM303Full.SOURCE_IA1, 1);
    await full3.enableInterrupt(LSM303Full.SOURCE_IA2, 1);
    await full3.enableInterrupt(LSM303Full.SOURCE_CLICK, 1);
    mock3.setRegister(_REG_STATUS_A, [0x08]);
    mock3.setRegister(_REG_FIFO_SRC_A, [0xC0]);
    mock3.setRegister(_REG_INT1_SRC_A, [0x40]);
    mock3.setRegister(_REG_INT2_SRC_A, [0x40]);
    mock3.setRegister(_REG_CLICK_SRC_A, [0x40]);
    mock3Mag.setRegister(_REG_SR_M, [0x01]);
    checkTrue('poll_interrupt_all_sources', (await full3.pollInterrupt()) === 0xBF);

    // Disable everything; only the magnetometer DRDY bit remains (always polled).
    await full3.disableInterrupt(LSM303Full.SOURCE_DATA_READY, 1);
    await full3.disableInterrupt(LSM303Full.SOURCE_WATERMARK, 1);
    await full3.disableInterrupt(LSM303Full.SOURCE_OVERRUN, 1);
    await full3.disableInterrupt(LSM303Full.SOURCE_IA1, 1);
    await full3.disableInterrupt(LSM303Full.SOURCE_IA2, 1);
    await full3.disableInterrupt(LSM303Full.SOURCE_CLICK, 1);
    mock3.setRegister(_REG_STATUS_A, [0x00]);
    checkTrue('poll_interrupt_mag_only', (await full3.pollInterrupt()) === 0x80);
    mock3Mag.setRegister(_REG_SR_M, [0x00]);
    checkTrue('poll_interrupt_none', (await full3.pollInterrupt()) === 0);

    // ------------------------------------------------------------------
    // Full: onInterrupt / offInterrupt fallback (mock has no intPin -> 5 ms interval)
    // ------------------------------------------------------------------
    let calls1 = 0, calls2 = 0, lastStatus = 0;
    await full3.onInterrupt(status => { calls1++; lastStatus = status; }, 1);
    mock3Mag.setRegister(_REG_SR_M, [0x01]);
    await new Promise(r => setTimeout(r, 30));
    checkTrue('on_interrupt_fallback_fires', calls1 >= 2);
    checkTrue('on_interrupt_fallback_status', lastStatus === 0x80);

    try { await full3.onInterrupt(() => {}, 3); checkTrue('on_interrupt_bad_pin_throws', false); }
    catch (e) { checkTrue('on_interrupt_bad_pin_throws', true); }

    await full3.onInterrupt(() => { calls2++; }, 2);
    const calls1Before = calls1;
    await new Promise(r => setTimeout(r, 30));
    checkTrue('on_interrupt_pin2_dispatches_both', calls2 >= 2 && calls1 > calls1Before);

    await full3.offInterrupt(1);
    const calls2Before = calls2;
    await new Promise(r => setTimeout(r, 30));
    checkTrue('off_interrupt_pin1_keeps_pin2', calls2 > calls2Before);

    await full3.offInterrupt(2);
    const calls2After = calls2;
    await new Promise(r => setTimeout(r, 40));
    checkTrue('off_interrupt_stops_delivery', calls2 === calls2After);

    // ------------------------------------------------------------------
    // Full: reboot
    // ------------------------------------------------------------------
    const ctrl5Before = lastWriteTo(mock3, _REG_CTRL5_A)[1];
    await full3.reboot();
    checkTrue('reboot_sets_boot_bit', lastWriteTo(mock3, _REG_CTRL5_A)[1] === (ctrl5Before | 0x80));

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.log('FAIL exception:', e.message); process.exit(1); });
