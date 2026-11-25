'use strict';
const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LSM303Minimal, LSM303Full, LSM303Source } =
    require('../../packages/periph/src/chips/accelerometer/lsm303');

let passed = 0;
let failed = 0;

function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

function close(a, b, eps = 1e-6) {
    return Math.abs(a - b) < eps;
}

function bytesEqual(buf, arr) {
    if (!buf) return false;
    if (buf.length !== arr.length) return false;
    for (let i = 0; i < arr.length; i++) if (buf[i] !== arr[i]) return false;
    return true;
}

// The init sequence uses real setTimeout-based delays (5 ms + 70 ms + 67 ms),
// so the tests have to wait for real time.
function wait(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
}

// The magnetometer identity lives at 0x0A..0x0C; preload it so _init() passes.
function newMagConnection() {
    const c = new I2CConnectionMock();
    c.setRegister(0x0A, [0x48, 0x34, 0x33]);
    return c;
}

function newAccelConnection() {
    return new I2CConnectionMock();
}

// The accelerometer sets bit 7 of the sub-address for multi-byte transfers,
// so the driver reads the output registers from 0x28 | 0x80. The mock
// resolves the address literally.
const ACCEL_OUT = 0x28 | 0x80;
const MAG_OUT = 0x03;

/**
 * Preload the accelerometer output registers with 12-bit counts.
 *
 * The high-resolution mode writes the 12-bit value left-justified into the
 * 16-bit register pair. The 12-bit range is -2048..2047, so the counts stay
 * within that range here.
 */
function setAccelOutput(conn, x, y, z) {
    const buf = Buffer.alloc(6);
    buf.writeInt16LE(x << 4, 0);
    buf.writeInt16LE(y << 4, 2);
    buf.writeInt16LE(z << 4, 4);
    conn.setRegister(ACCEL_OUT, [...buf]);
}

/**
 * Preload the magnetometer output registers with 12-bit counts.
 *
 * The magnetometer writes the 12-bit value right-justified into the 16-bit
 * register pair and sends X, Z, Y on the wire.
 */
function setMagOutput(conn, x, y, z) {
    const buf = Buffer.alloc(6);
    buf.writeInt16BE(x, 0);  // X
    buf.writeInt16BE(z, 2);  // Z
    buf.writeInt16BE(y, 4);  // Y
    conn.setRegister(MAG_OUT, [...buf]);
}

/**
 * Return the value of the last write to `reg`.
 *
 * The mock accumulates every write since construction, so "the register now
 * holds X" has to look at the last write to that register, not at any write.
 */
function lastWriteTo(conn, reg) {
    for (let i = conn.writes.length - 1; i >= 0; i--) {
        const w = conn.writes[i];
        if (w.length >= 2 && w[0] === reg) return w[1];
    }
    return undefined;
}

async function main() {
    // --- Construction: identity check and the init register writes ---------
    const accelConn = newAccelConnection();
    const magConn = newMagConnection();
    const chip = new LSM303Minimal(accelConn, magConn);
    await wait(160);

    checkTrue('init_ctrl4_a', lastWriteTo(accelConn, 0x23) === 0x88);
    checkTrue('init_ctrl1_a', lastWriteTo(accelConn, 0x20) === 0x57);
    checkTrue('init_cra_m', lastWriteTo(magConn, 0x00) === 0x10);
    checkTrue('init_crb_m', lastWriteTo(magConn, 0x01) === 0x20);
    checkTrue('init_mr_m', lastWriteTo(magConn, 0x02) === 0x00);
    checkTrue('init_fifo_bypass', lastWriteTo(accelConn, 0x2E) === 0x00);

    // A wrong magnetometer identity must be rejected. The init sequence runs
    // asynchronously, so the rejection has to be awaited explicitly.
    let threw = false;
    const badMag = new I2CConnectionMock();
    badMag.setRegister(0x0A, [0x00, 0x00, 0x00]);
    const bad = new LSM303Minimal(newAccelConnection(), badMag);
    try {
        await bad._initPromise;
    } catch (e) {
        threw = true;
    }
    checkTrue('init_rejects_bad_identity', threw);

    // --- readAcceleration(): little-endian, 1 mg/LSB at +-2 g ---------------
    const accel2 = newAccelConnection();
    const mag2 = newMagConnection();
    const chip2 = new LSM303Minimal(accel2, mag2);
    await wait(160);

    // x = +1000 mg, y = -2000 mg, z = +2000 mg (little-endian per axis).
    setAccelOutput(accel2, 1000, -2000, 2000);
    const acc = await chip2.readAcceleration();
    checkTrue('read_acceleration_x', close(acc.x, 1.0));
    checkTrue('read_acceleration_y', close(acc.y, -2.0));
    checkTrue('read_acceleration_z', close(acc.z, 2.0));

    // --- readMagnetic(): X, Z, Y on the wire, high byte first --------------
    // X = 1000 counts, Z = 2000 counts, Y = 3000 counts at +-130 uT
    // (gain 1100 LSB/gauss for X/Y, 980 LSB/gauss for Z).
    setMagOutput(mag2, 1000, 3000, 2000);
    const mag = await chip2.readMagnetic();
    checkTrue('read_magnetic_x', close(mag.x, 1000 / 1100 * 100));
    checkTrue('read_magnetic_y', close(mag.y, 3000 / 1100 * 100));
    checkTrue('read_magnetic_z', close(mag.z, 2000 / 980 * 100));

    // --- LSM303Full --------------------------------------------------------
    const fAccel = newAccelConnection();
    const fMag = newMagConnection();
    const full = new LSM303Full(fAccel, fMag);
    await wait(160);

    // setRange(): the FS field is bits 4:3 of CTRL_REG4_A.
    fAccel.setRegister(0x23, [0x88]);
    await full.setRange(8);
    checkTrue('set_range', lastWriteTo(fAccel, 0x23) === 0xA8);

    // setDataRate(): the ODR field is bits 7:4 of CTRL_REG1_A.
    fAccel.setRegister(0x20, [0x57]);
    await full.setDataRate(400);
    checkTrue('set_data_rate', lastWriteTo(fAccel, 0x20) === 0x77);

    // setDataRate() rejects a rate that does not exist.
    threw = false;
    try { await full.setDataRate(123); } catch (e) { threw = true; }
    checkTrue('set_data_rate_rejects_invalid', threw);

    // setMode(): low-power sets LPEN and clears HR.
    fAccel.setRegister(0x20, [0x57]);
    fAccel.setRegister(0x23, [0x88]);
    await full.setMode(LSM303Full.MODE_LOW_POWER);
    checkTrue('set_mode_low_power', lastWriteTo(fAccel, 0x20) === 0x5F);
    checkTrue('set_mode_low_power_clears_hr', lastWriteTo(fAccel, 0x23) === 0x80);

    // setAxes(): the axis bits are bits 2:0 of CTRL_REG1_A.
    fAccel.setRegister(0x20, [0x57]);
    await full.setAxes(true, false, true);
    checkTrue('set_axes', lastWriteTo(fAccel, 0x20) === 0x55);

    // powerDown(): the ODR field is cleared, the rest of the register stays.
    fAccel.setRegister(0x20, [0x57]);
    await full.powerDown();
    checkTrue('power_down', lastWriteTo(fAccel, 0x20) === 0x07);

    // newDataAvailable(): STATUS_REG_A.ZYXDA is bit 3.
    fAccel.setRegister(0x27, [0x08]);
    checkTrue('new_data_available', (await full.newDataAvailable()) === true);
    fAccel.setRegister(0x27, [0x00]);
    checkTrue('new_data_unavailable', (await full.newDataAvailable()) === false);

    // readStatus(): the raw STATUS_REG_A byte.
    fAccel.setRegister(0x27, [0x0F]);
    checkTrue('read_status', (await full.readStatus()) === 0x0F);

    // setHighPass(): mode and cutoff land in CTRL_REG2_A.
    // mode=2 (0x80), cutoff=1 (0x10), FDS (0x08), HPCLICK (0x04), HPIS1 (0x01).
    await full.setHighPass(2, 1, true, true, false, true);
    checkTrue('set_high_pass', lastWriteTo(fAccel, 0x21) === 0x9D);

    // resetHighPass(): reads REFERENCE_A.
    fAccel.setRegister(0x26, [0x42]);
    checkTrue('reset_high_pass', (await full.resetHighPass()) === 0x42);

    // setReference(): writes REFERENCE_A.
    await full.setReference(0x37);
    checkTrue('set_reference', lastWriteTo(fAccel, 0x26) === 0x37);

    // setFifo(): stream mode with a watermark of 16, FIFO_EN in CTRL_REG5_A.
    fAccel.setRegister(0x24, [0x00]);
    await full.setFifo(LSM303Full.FIFO_STREAM, 16);
    checkTrue('set_fifo_ctrl', lastWriteTo(fAccel, 0x2E) === 0x90);
    checkTrue('set_fifo_enables_fifo', lastWriteTo(fAccel, 0x24) === 0x40);

    // setFifo() rejects an out-of-range watermark.
    threw = false;
    try { await full.setFifo(LSM303Full.FIFO_STREAM, 99); } catch (e) { threw = true; }
    checkTrue('set_fifo_rejects_invalid', threw);

    // fifoLevel(): the level is the low five bits of FIFO_SRC_REG_A.
    fAccel.setRegister(0x2F, [0x85]);
    checkTrue('fifo_level', (await full.fifoLevel()) === 5);

    // readFifoStatus(): decodes the three flags and the level.
    fAccel.setRegister(0x2F, [0xE7]);
    const fs = await full.readFifoStatus();
    checkTrue('fifo_status_watermark', fs.watermark === true);
    checkTrue('fifo_status_overrun', fs.overrun === true);
    checkTrue('fifo_status_empty', fs.empty === true);
    checkTrue('fifo_status_level', fs.level === 7);

    // setMotionInterrupt(): the event mask goes into INT1_CFG_A. The
    // threshold code is relative to the current full-scale range, which is
    // +-8 g after the setRange() call above: 0.5 g * 128 / 8 = 8.
    await full.setMotionInterrupt(1, 0x02, 0.5, 10);
    checkTrue('set_motion_int_cfg', lastWriteTo(fAccel, 0x30) === 0x02);
    checkTrue('set_motion_int_ths', lastWriteTo(fAccel, 0x32) === 0x08);

    // setFreeFall(): all three axes low, combined with AND.
    await full.setFreeFall(1, 0.35, 30);
    checkTrue('set_free_fall', lastWriteTo(fAccel, 0x30) === 0x95);

    // readFifo(): pops one sample set per six-byte burst read. The counts
    // depend on the operating mode and the full-scale range, so switch back
    // to the defaults (high resolution, +-2 g) that the setRange() and
    // setMode() calls above changed away from.
    fAccel.setRegister(0x23, [0x88]);
    await full.setRange(2);
    fAccel.setRegister(0x20, [0x57]);
    fAccel.setRegister(0x23, [0x88]);
    await full.setMode(LSM303Full.MODE_HIGH_RESOLUTION);
    setAccelOutput(fAccel, 1000, -2000, 2000);
    const samples = await full.readFifo(2);
    checkTrue('read_fifo_count', samples.length === 2);
    checkTrue('read_fifo_x', close(samples[0].x, 1.0));

    // setOrientation(): 6D movement recognition.
    await full.setOrientation(1, false, false);
    checkTrue('set_orientation', lastWriteTo(fAccel, 0x30) === 0x7F);

    // setLatch(): the LIR bit of generator 1 is bit 3 of CTRL_REG5_A.
    fAccel.setRegister(0x24, [0x00]);
    await full.setLatch(1, true);
    checkTrue('set_latch', lastWriteTo(fAccel, 0x24) === 0x08);

    // readInterruptSource(): reads INT1_SOURCE_A.
    fAccel.setRegister(0x31, [0x41]);
    checkTrue('read_interrupt_source', (await full.readInterruptSource(1)) === 0x41);

    // setClick(): the axis masks land in CLICK_CFG_A.
    // double & CLICK_AXIS_Z (0x04) -> 0x20.
    await full.setClick(0, 0x04, 0.8, 20, 40, 200);
    checkTrue('set_click', lastWriteTo(fAccel, 0x38) === 0x20);

    // readClickSource(): reads CLICK_SRC_A.
    fAccel.setRegister(0x39, [0x42]);
    checkTrue('read_click_source', (await full.readClickSource()) === 0x42);

    // setInterruptPolarity(): H_LACTIVE is bit 1 of CTRL_REG6_A.
    fAccel.setRegister(0x25, [0x00]);
    await full.setInterruptPolarity(true);
    checkTrue('set_interrupt_polarity', lastWriteTo(fAccel, 0x25) === 0x02);

    // setMagRange(): the GN field is bits 7:5 of CRB_REG_M.
    fMag.setRegister(0x01, [0x20]);
    await full.setMagRange(400);
    checkTrue('set_mag_range', lastWriteTo(fMag, 0x01) === 0x80);

    // setMagDataRate(): the DO field is bits 4:2 of CRA_REG_M.
    // 30 Hz is the sixth entry of [0.75, 1.5, 3, 7.5, 15, 30, 75, 220].
    fMag.setRegister(0x00, [0x10]);
    await full.setMagDataRate(30);
    checkTrue('set_mag_data_rate', lastWriteTo(fMag, 0x00) === 0x14);

    // setMagMode(): the MD field is bits 1:0 of MR_REG_M.
    fMag.setRegister(0x02, [0x00]);
    await full.setMagMode(LSM303Full.MAG_MODE_SLEEP);
    checkTrue('set_mag_mode', lastWriteTo(fMag, 0x02) === 0x02);

    // magDataReady(): SR_REG_M.DRDY is bit 0.
    fMag.setRegister(0x09, [0x01]);
    checkTrue('mag_data_ready', (await full.magDataReady()) === true);

    // readMagStatus(): decodes DRDY and LOCK.
    fMag.setRegister(0x09, [0x03]);
    const ms = await full.readMagStatus();
    checkTrue('read_mag_status_drdy', ms.drdy === true);
    checkTrue('read_mag_status_lock', ms.lock === true);

    // readMagIdentity(): returns the three identity bytes.
    const ident = await full.readMagIdentity();
    checkTrue('read_mag_identity', bytesEqual(ident, [0x48, 0x34, 0x33]));

    // enableTemperature(): TEMP_EN is bit 7 of CRA_REG_M.
    fMag.setRegister(0x00, [0x10]);
    await full.enableTemperature(true);
    checkTrue('enable_temperature', lastWriteTo(fMag, 0x00) === 0x90);

    // readTemperatureDelta(): 8 LSB/degC, signed 16-bit shifted right by 4.
    fMag.setRegister(0x31, [0x01, 0x00]);
    checkTrue('read_temperature_delta', close(await full.readTemperatureDelta(), 16 / 8));

    // heading(): the flat heading follows the magnetic field.
    // X = 1100 counts (1 gauss) and Y = 0 -> heading 0 deg (north).
    setMagOutput(fMag, 1100, 0, 0);
    const heading = await full.heading(false);
    checkTrue('heading_north', close(heading, 0, 1e-3));

    // X = 0 and Y = 1100 counts -> heading 90 deg (east).
    setMagOutput(fMag, 0, 1100, 0);
    const headingEast = await full.heading(false);
    checkTrue('heading_east', close(headingEast, 90, 1e-3));

    // X = 0 and Y = -1100 counts -> heading 270 deg (west).
    setMagOutput(fMag, 0, -1100, 0);
    const headingWest = await full.heading(false);
    checkTrue('heading_west', close(headingWest, 270, 1e-3));

    // enableInterrupt(): the routing bit lands in CTRL_REG3_A.
    fAccel.setRegister(0x22, [0x00]);
    await full.enableInterrupt(LSM303Source.SOURCE_CLICK, 1);
    checkTrue('enable_interrupt_ctrl3', lastWriteTo(fAccel, 0x22) === 0x80);

    // disableInterrupt(): the routing bit is cleared again.
    fAccel.setRegister(0x22, [0x80]);
    await full.disableInterrupt(LSM303Source.SOURCE_CLICK, 1);
    checkTrue('disable_interrupt_ctrl3', lastWriteTo(fAccel, 0x22) === 0x00);

    // The magnetometer DRDY source cannot be routed.
    threw = false;
    try { await full.enableInterrupt(LSM303Source.SOURCE_MAG_DATA_READY, 1); } catch (e) { threw = true; }
    checkTrue('enable_interrupt_rejects_drdy', threw);

    // pollInterrupt(): combines the status bits into the source mask.
    fAccel.setRegister(0x27, [0x08]);   // ZYXDA -> data ready
    fAccel.setRegister(0x2F, [0x80]);   // WTM   -> watermark
    fMag.setRegister(0x09, [0x00]);     // no magnetometer DRDY
    const status = await full.pollInterrupt();
    checkTrue('poll_interrupt_data_ready', (status & LSM303Source.SOURCE_DATA_READY) !== 0);
    checkTrue('poll_interrupt_watermark', (status & LSM303Source.SOURCE_WATERMARK) !== 0);
    checkTrue('poll_interrupt_no_overrun', (status & LSM303Source.SOURCE_OVERRUN) === 0);

    // reboot(): sets the BOOT bit of CTRL_REG5_A. The reboot re-runs init(),
    // which rewrites CTRL_REG5_A afterwards, so look for a write with the
    // BOOT bit set rather than at the last write.
    fAccel.setRegister(0x24, [0x00]);
    await full.reboot();
    const bootWritten = fAccel.writes.some(
        (w) => w.length >= 2 && w[0] === 0x24 && (w[1] & 0x80) !== 0);
    checkTrue('reboot_sets_boot_bit', bootWritten);
    checkTrue('reboot_restores_defaults', lastWriteTo(fAccel, 0x20) === 0x57);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    if (failed > 0) process.exit(1);
}

main().catch((e) => {
    console.error(e);
    process.exit(1);
});
