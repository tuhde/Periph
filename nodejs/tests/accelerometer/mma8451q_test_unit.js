'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const {
    MMA8451QMinimal,
    MMA8451QFull,
    SOURCE_DATA_READY,
    SOURCE_FF_MT,
    SOURCE_PULSE,
    SOURCE_ORIENTATION,
    SOURCE_TRANSIENT,
    SOURCE_FIFO,
    SOURCE_AUTO_SLEEP,
} = require('../../packages/periph/src/chips/accelerometer/mma8451q');

// Mock writes accumulate; "off" checks must look at the most recent write to the register.
function lastWriteTo(mock, reg) {
    return [...mock.writes].reverse().find(w => w.length === 2 && w[0] === reg);
}

function lastValueTo(mock, reg) {
    const w = lastWriteTo(mock, reg);
    return w === undefined ? undefined : w[1];
}

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// Build a mock with a plausible chip state: WHO_AM_I, ACTIVE at 100 Hz,
// and an X/Y/Z sample of 0 g / 0 g / +1 g in 14-bit representation.
function newMock() {
    const mock = new I2CConnectionMock();
    mock.setRegister(0x0D, [0x1A]);   // WHO_AM_I
    mock.setRegister(0x2A, [0x19]);   // CTRL_REG1: DR = 011 (100 Hz), ACTIVE = 1
    mock.setRegister(0x2B, [0x00]);   // CTRL_REG2
    mock.setRegister(0x2C, [0x00]);   // CTRL_REG3
    mock.setRegister(0x2D, [0x00]);   // CTRL_REG4
    mock.setRegister(0x2E, [0x00]);   // CTRL_REG5
    mock.setRegister(0x0E, [0x00]);   // XYZ_DATA_CFG: ±2 g
    mock.setRegister(0x0F, [0x00]);   // HP_FILTER_CUTOFF
    // +1 g on Z: 4096 counts << 2 = 0x4000
    mock.setRegister(0x01, [0x00, 0x00, 0x00, 0x00, 0x40, 0x00]);
    return mock;
}

async function main() {
    // --- source constants -------------------------------------------------
    checkTrue('source_data_ready_is_0x01', SOURCE_DATA_READY === 0x01);
    checkTrue('source_ff_mt_is_0x04', SOURCE_FF_MT === 0x04);
    checkTrue('source_pulse_is_0x08', SOURCE_PULSE === 0x08);
    checkTrue('source_orientation_is_0x10', SOURCE_ORIENTATION === 0x10);
    checkTrue('source_transient_is_0x20', SOURCE_TRANSIENT === 0x20);
    checkTrue('source_fifo_is_0x40', SOURCE_FIFO === 0x40);
    checkTrue('source_auto_sleep_is_0x80', SOURCE_AUTO_SLEEP === 0x80);
    checkTrue('sources_are_also_on_the_full_class',
        MMA8451QFull.SOURCE_DATA_READY === 0x01 &&
        MMA8451QFull.SOURCE_AUTO_SLEEP === 0x80);

    // --- Minimal: init sequence ------------------------------------------
    const mock = newMock();
    const accel = new MMA8451QMinimal(mock);
    checkTrue('construct_minimal', true);

    await accel.init();
    checkTrue('init_reads_who_am_i', mock.writes.some(w => w.length === 1 && w[0] === 0x0D));
    checkTrue('init_writes_ctrl_reg2_reset',
        mock.writes.some(w => w.length === 2 && w[0] === 0x2B && w[1] === 0x40));
    checkTrue('init_writes_xyz_data_cfg_0x00',
        mock.writes.some(w => w.length === 2 && w[0] === 0x0E && w[1] === 0x00));
    checkTrue('init_writes_ctrl_reg2_0x00',
        mock.writes.some(w => w.length === 2 && w[0] === 0x2B && w[1] === 0x00));
    checkTrue('init_writes_ctrl_reg1_0x19',
        mock.writes.some(w => w.length === 2 && w[0] === 0x2A && w[1] === 0x19));

    // --- 14-bit conversion ------------------------------------------------
    const [x, y, z] = await accel.read();
    checkTrue('read_x_zero_g', Math.abs(x - 0.0) < 1e-9);
    checkTrue('read_y_zero_g', Math.abs(y - 0.0) < 1e-9);
    checkTrue('read_z_plus_1g', Math.abs(z - 1.0) < 1e-9);

    // Negative value: -0.5 g → -2048 counts → raw16 = -8192 = 0xE000.
    mock.setRegister(0x01, [0xE0, 0x00, 0x00, 0x00, 0x00, 0x00]);
    const [nx] = await accel.read();
    checkTrue('read_x_minus_0_5g', Math.abs(nx - (-0.5)) < 1e-9);

    // --- Full: construct + init ------------------------------------------
    const mock2 = newMock();
    const full = new MMA8451QFull(mock2);
    await full.init();
    checkTrue('construct_full', true);

    // --- setRange ---------------------------------------------------------
    await full.setRange(4);
    checkTrue('set_range_4g_writes_fs_01',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x0E && (w[1] & 0x03) === 0x01));
    // ±4 g → 2048 counts/g: the same 4096 counts now read as 2 g.
    const [, , rz4] = await full.read();
    checkTrue('read_after_set_range_4g', Math.abs(rz4 - 2.0) < 1e-9);

    await full.setRange(2);
    checkTrue('set_range_2g_writes_fs_00',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x0E && (w[1] & 0x03) === 0x00));

    let threw = false;
    try { await full.setRange(3); } catch (e) { threw = true; }
    checkTrue('set_range_rejects_invalid_value', threw);

    // --- setDataRate ------------------------------------------------------
    await full.setDataRate(200);
    checkTrue('set_data_rate_200hz_dr_010',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2A && (w[1] & 0x38) === 0x10));
    await full.setDataRate(800);
    checkTrue('set_data_rate_800hz_dr_000',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2A && (w[1] & 0x38) === 0x00));
    await full.setDataRate(100);
    checkTrue('set_data_rate_100hz_dr_011',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2A && (w[1] & 0x38) === 0x18));

    threw = false;
    try { await full.setDataRate(123); } catch (e) { threw = true; }
    checkTrue('set_data_rate_rejects_invalid_value', threw);

    // --- setOversampling --------------------------------------------------
    await full.setOversampling(2);
    checkTrue('set_oversampling_high_res_modes_10',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2B && (w[1] & 0x03) === 0x02));
    await full.setOversampling(0, 3);
    checkTrue('set_oversampling_sleep_mode_11',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2B && (w[1] & 0x18) === 0x18));

    threw = false;
    try { await full.setOversampling(4); } catch (e) { threw = true; }
    checkTrue('set_oversampling_rejects_invalid_mode', threw);

    // --- low noise --------------------------------------------------------
    await full.setLowNoise(true);
    checkTrue('set_low_noise_sets_lnoise_bit',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2A && (w[1] & 0x04) === 0x04));
    await full.setLowNoise(false);
    checkTrue('set_low_noise_false_clears_lnoise_bit',
        !(lastValueTo(mock2, 0x2A) & 0x04));

    // ±8 g is rejected while low-noise mode is on.
    await full.setLowNoise(true);
    threw = false;
    try { await full.setRange(8); } catch (e) { threw = true; }
    checkTrue('set_range_8g_rejected_in_low_noise_mode', threw);
    await full.setLowNoise(false);

    // --- resolution (F_READ) ---------------------------------------------
    // 8-bit mode reads three bytes in one transaction. On real hardware the
    // auto-increment skips the LSB registers; the mock is byte-addressable,
    // so the three MSB values are preloaded consecutively at 0x01.
    const mock8 = newMock();
    mock8.setRegister(0x01, [0x00, 0x00, 0x40]);
    const full8 = new MMA8451QFull(mock8);
    await full8.init();
    await full8.setResolution(8);
    checkTrue('set_resolution_8_sets_f_read_bit',
        mock8.writes.some(w => w.length === 2 && w[0] === 0x2A && (w[1] & 0x02) === 0x02));
    // 8-bit mode: 0x40 = +64 counts = +1 g at ±2 g.
    const [fx8, fy8, fz8] = await full8.read();
    checkTrue('read_in_8bit_mode', fz8 === 1 && fx8 === 0 && fy8 === 0);

    await full8.setResolution(14);
    checkTrue('set_resolution_14_clears_f_read_bit',
        !(lastValueTo(mock8, 0x2A) & 0x02));

    threw = false;
    try { await full8.setResolution(12); } catch (e) { threw = true; }
    checkTrue('set_resolution_rejects_invalid_value', threw);

    // The resolution cannot change while the FIFO is enabled.
    await full8.setFifo(1, 0);
    threw = false;
    try { await full8.setResolution(8); } catch (e) { threw = true; }
    checkTrue('set_resolution_rejected_while_fifo_enabled', threw);
    await full8.setFifo(0, 0);

    // --- offsets ----------------------------------------------------------
    await full.setOffset(0.1, -0.2, 0.25);
    checkTrue('set_offset_x', lastValueTo(mock2, 0x2F) === 50);             // 0.1 / 0.002
    checkTrue('set_offset_y', (lastValueTo(mock2, 0x30) & 0xFF) === (-100 & 0xFF));  // -0.2 / 0.002
    checkTrue('set_offset_z', lastValueTo(mock2, 0x31) === 125);            // 0.25 / 0.002
    const [ox, oy, oz] = await full.getOffset();
    checkTrue('get_offset_x', Math.abs(ox - 0.1) < 1e-6);
    checkTrue('get_offset_y', Math.abs(oy - (-0.2)) < 1e-6);
    checkTrue('get_offset_z', Math.abs(oz - 0.25) < 1e-6);

    // Clamped to ±128 counts (±0.256 g).
    await full.setOffset(1.0, -1.0, 0);
    checkTrue('set_offset_clamped_high', lastValueTo(mock2, 0x2F) === 127);
    checkTrue('set_offset_clamped_low', (lastValueTo(mock2, 0x30) & 0xFF) === (-128 & 0xFF));

    // --- thresholds (0.063 g per LSB) ------------------------------------
    await full.setFreefallMotion(false, 7, 0.2, 0, false, false);
    // round(0.2 / 0.063) = 3
    checkTrue('set_freefall_motion_threshold_0_063g_per_lsb',
        lastValueTo(mock2, 0x17) === 3);
    checkTrue('set_freefall_motion_cfg_axes_111',
        (lastValueTo(mock2, 0x15) & 0x38) === 0x38);
    checkTrue('set_freefall_motion_freefall_mode_oae_clear',
        (lastValueTo(mock2, 0x15) & 0x40) === 0x00);

    await full.setFreefallMotion(true, 5, 0.5, 0, true, true);
    checkTrue('set_freefall_motion_motion_mode_oae_set',
        (lastValueTo(mock2, 0x15) & 0x40) === 0x40);
    checkTrue('set_freefall_motion_latch_ele_set',
        (lastValueTo(mock2, 0x15) & 0x80) === 0x80);
    checkTrue('set_freefall_motion_axes_101',
        (lastValueTo(mock2, 0x15) & 0x38) === 0x28);
    // round(0.5 / 0.063) = 8, DBCNTM = 1 → 0x88
    checkTrue('set_freefall_motion_debounce_clear_sets_db_cntm',
        lastValueTo(mock2, 0x17) === (0x80 | 8));

    await full.setTransient(7, 0.25, 0, false, false);
    // round(0.25 / 0.063) = 4
    checkTrue('set_transient_threshold',
        lastValueTo(mock2, 0x1F) === 4);
    checkTrue('set_transient_axes_111',
        (lastValueTo(mock2, 0x1D) & 0x0E) === 0x0E);
    checkTrue('set_transient_hpf_bypass_clear',
        (lastValueTo(mock2, 0x1D) & 0x01) === 0x00);

    await full.setTransient(3, 0.25, 0, true, true);
    checkTrue('set_transient_hpf_bypass_set',
        (lastValueTo(mock2, 0x1D) & 0x01) === 0x01);
    checkTrue('set_transient_latch_ele_set',
        (lastValueTo(mock2, 0x1D) & 0x10) === 0x10);

    await full.setPulse(0, 4, [0.5, 0.5, 1.0]);
    // round(0.5 / 0.063) = 8, round(1.0 / 0.063) = 16
    checkTrue('set_pulse_threshold_x', lastValueTo(mock2, 0x23) === 8);
    checkTrue('set_pulse_threshold_y', lastValueTo(mock2, 0x24) === 8);
    checkTrue('set_pulse_threshold_z', lastValueTo(mock2, 0x25) === 16);
    // double = 4 (Z only) → PULSE_CFG bits 2:0 = 0b100
    checkTrue('set_pulse_double_axis_mask',
        (lastValueTo(mock2, 0x21) & 0x07) === 0x04);
    checkTrue('set_pulse_lpf_enabled',
        (lastValueTo(mock2, 0x0F) & 0x10) === 0x10);
    checkTrue('set_pulse_hpf_enabled',
        (lastValueTo(mock2, 0x0F) & 0x20) === 0x00);

    await full.setPulse(1, 0, [0.5, 0.5, 0.5], 30, 100, 300, true, true, false, false);
    // single = 1 (X only) → PULSE_CFG bits 5:3 = 0b001
    checkTrue('set_pulse_single_axis_mask',
        (lastValueTo(mock2, 0x21) & 0x38) === 0x08);
    checkTrue('set_pulse_latch_ele_set',
        (lastValueTo(mock2, 0x21) & 0x40) === 0x40);
    checkTrue('set_pulse_double_abort_dpa_set',
        (lastValueTo(mock2, 0x21) & 0x80) === 0x80);
    checkTrue('set_pulse_lpf_disabled',
        (lastValueTo(mock2, 0x0F) & 0x10) === 0x00);
    checkTrue('set_pulse_hpf_bypass_set',
        (lastValueTo(mock2, 0x0F) & 0x20) === 0x20);

    // --- debounce counts --------------------------------------------------
    // At 100 Hz normal oversampling the debounce step is 10 ms.
    await full.setDataRate(100);
    await full.setOversampling(0);
    await full.setFreefallMotion(false, 7, 0.2, 60);
    checkTrue('set_freefall_motion_debounce_count_60ms_at_100hz',
        lastValueTo(mock2, 0x18) === 6);

    // At 200 Hz high-resolution oversampling the step is 2.5 ms.
    await full.setDataRate(200);
    await full.setOversampling(2);
    await full.setFreefallMotion(false, 7, 0.2, 60);
    checkTrue('set_freefall_motion_debounce_count_60ms_at_200hz_hr',
        lastValueTo(mock2, 0x18) === 24);
    await full.setDataRate(100);
    await full.setOversampling(0);

    // --- orientation ------------------------------------------------------
    await full.setOrientation(true, 100, 45, 4, 4, 1);
    checkTrue('set_orientation_pl_en_set',
        (lastValueTo(mock2, 0x11) & 0x40) === 0x40);
    // 45° → P_L_THS = 0x10, HYS = 4 → (0x10 << 3) | 4 = 0x84
    checkTrue('set_orientation_threshold_45deg',
        lastValueTo(mock2, 0x14) === 0x84);
    // ZLOCK = 4, BKFR = 1 → (1 << 6) | 4 = 0x44
    checkTrue('set_orientation_zlock_and_bkfr',
        lastValueTo(mock2, 0x13) === 0x44);
    // debounce 100 ms at 100 Hz → 10
    checkTrue('set_orientation_debounce_count',
        lastValueTo(mock2, 0x12) === 10);

    await full.setOrientation(false, 0, 30, 0, 0, 0);
    checkTrue('set_orientation_disabled_clears_pl_en',
        (lastValueTo(mock2, 0x11) & 0x40) === 0x00);
    // 30° → 0x0C, HYS = 0 → 0x0C << 3 = 0x60
    checkTrue('set_orientation_threshold_30deg',
        lastValueTo(mock2, 0x14) === 0x60);

    threw = false;
    try { await full.setOrientation(true, 0, 33); } catch (e) { threw = true; }
    checkTrue('set_orientation_rejects_invalid_angle', threw);

    threw = false;
    // 75° → P_L_THS = 0x19 = 25, plus HYS = 7 → 32, outside the 1..31 window.
    try { await full.setOrientation(true, 0, 75, 7); } catch (e) { threw = true; }
    checkTrue('set_orientation_rejects_threshold_plus_hys_out_of_range', threw);

    // --- readOrientation --------------------------------------------------
    mock2.setRegister(0x10, [0xC5]);   // NEWLP | LO | LAPO = 0b10 | BAFRO
    const orient = await full.readOrientation();
    checkTrue('read_orientation_changed', orient.changed === true);
    checkTrue('read_orientation_lockout', orient.lockout === true);
    checkTrue('read_orientation_portrait_landscape', orient.portraitLandscape === 2);
    checkTrue('read_orientation_back', orient.back === true);

    // --- FIFO -------------------------------------------------------------
    await full.setFifo(1, 16);
    // F_SETUP: F_MODE = 01 (circular) << 6 | watermark 16 = 0x50
    checkTrue('set_fifo_circular_with_watermark_16',
        lastValueTo(mock2, 0x09) === 0x50);

    await full.setFifo(2, 0);
    checkTrue('set_fifo_fill_mode',
        lastValueTo(mock2, 0x09) === (2 << 6));
    // Switching between enabled modes must go through "disabled".
    checkTrue('set_fifo_switches_via_disabled',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x09 && w[1] === 0x00));

    await full.setFifo(0, 0);
    checkTrue('set_fifo_disabled', lastValueTo(mock2, 0x09) === 0x00);

    await full.setFifoTrigger(true, false, true, false);
    // TRIG_CFG: Trig_TRANS (0x20) | Trig_PULSE (0x08) = 0x28
    checkTrue('set_fifo_trigger_sources',
        lastValueTo(mock2, 0x0A) === 0x28);

    mock2.setRegister(0x00, [0xC5]);   // F_OVF | F_WMRK_FLAG | F_CNT = 5
    const fstatus = await full.readFifoStatus();
    checkTrue('read_fifo_status_overflow', fstatus.overflow === true);
    checkTrue('read_fifo_status_watermark', fstatus.watermark === true);
    checkTrue('read_fifo_status_count', fstatus.count === 5);
    checkTrue('fifo_level', (await full.fifoLevel()) === 5);

    // FIFO read: one sample per transaction at 0x01. The FIFO level is
    // polled after every sample, so the mock must report the remaining
    // samples for the drain to continue.
    mock2.setRegister(0x01, [0x00, 0x00, 0x00, 0x00, 0x40, 0x00]);
    mock2.setRegister(0x00, [0x02]);   // F_CNT = 2
    const fifo = await full.readFifo(2);
    checkTrue('read_fifo_returns_requested_count', fifo.length === 2);
    checkTrue('read_fifo_values_in_g', Math.abs(fifo[0][2] - 1.0) < 1e-9);

    // Without an explicit count the drain stops when the FIFO runs dry.
    // The mock decrements F_CNT on every FIFO sample read, like the chip.
    const mockFifo = newMock();
    mockFifo.setRegister(0x01, [0x00, 0x00, 0x00, 0x00, 0x40, 0x00]);
    mockFifo.setRegister(0x00, [0x03]);   // F_CNT = 3
    const fifoRead = mockFifo.readReg.bind(mockFifo);
    mockFifo.readReg = async (reg, len) => {
        const out = await fifoRead(reg, len);
        if (reg === 0x01 && len === 6) {
            const level = mockFifo.registers.get(0x00) & 0x3F;
            mockFifo.registers.set(0x00, level > 0 ? level - 1 : 0);
        }
        return out;
    };
    const fullFifo = new MMA8451QFull(mockFifo);
    await fullFifo.init();
    mockFifo.setRegister(0x00, [0x03]);
    const fifoAll = await fullFifo.readFifo();
    checkTrue('read_fifo_drains_until_empty', fifoAll.length === 3);

    threw = false;
    try { await full.setFifo(4); } catch (e) { threw = true; }
    checkTrue('set_fifo_rejects_invalid_mode', threw);

    // --- auto sleep -------------------------------------------------------
    await full.setAutoSleep(true, 12.5, 10, SOURCE_PULSE | SOURCE_FF_MT);
    checkTrue('set_auto_sleep_slpe_set',
        (lastValueTo(mock2, 0x2B) & 0x04) === 0x04);
    checkTrue('set_auto_sleep_aslp_rate_12_5hz',
        (lastValueTo(mock2, 0x2A) & 0xC0) === 0x40);
    // step 0.32 s → round(10 / 0.32) = 31
    checkTrue('set_auto_sleep_count',
        lastValueTo(mock2, 0x29) === 31);
    // WAKE_PULSE (0x10) | WAKE_FF_MT (0x08) = 0x18
    checkTrue('set_auto_sleep_wake_sources',
        (lastValueTo(mock2, 0x2C) & 0x78) === 0x18);

    await full.setAutoSleep(false);
    checkTrue('set_auto_sleep_disabled_clears_slpe',
        (lastValueTo(mock2, 0x2B) & 0x04) === 0x00);

    // --- FIFO gate and interrupt pins -------------------------------------
    await full.setFifoGate(true);
    checkTrue('set_fifo_gate_sets_bit',
        (lastValueTo(mock2, 0x2C) & 0x80) === 0x80);
    await full.setFifoGate(false);
    checkTrue('set_fifo_gate_clears_bit',
        (lastValueTo(mock2, 0x2C) & 0x80) === 0x00);

    await full.setInterruptPins(true, true);
    checkTrue('set_interrupt_pins_active_high_and_open_drain',
        (lastValueTo(mock2, 0x2C) & 0x03) === 0x03);
    await full.setInterruptPins(false, false);
    checkTrue('set_interrupt_pins_default_active_low_push_pull',
        (lastValueTo(mock2, 0x2C) & 0x03) === 0x00);

    // --- interrupt enable / disable --------------------------------------
    mock2.setRegister(0x2D, [0x00]);
    mock2.setRegister(0x2E, [0x00]);
    await full.enableInterrupt(SOURCE_ORIENTATION, 1);
    checkTrue('enable_interrupt_sets_ctrl_reg4_bit',
        (lastValueTo(mock2, 0x2D) & 0x10) === 0x10);
    checkTrue('enable_interrupt_routes_to_int1_in_ctrl_reg5',
        (lastValueTo(mock2, 0x2E) & 0x10) === 0x10);

    mock2.setRegister(0x2D, [0x00]);
    mock2.setRegister(0x2E, [0x00]);
    await full.enableInterrupt(SOURCE_PULSE, 2);
    checkTrue('enable_interrupt_pulse_sets_ctrl_reg4_bit',
        (lastValueTo(mock2, 0x2D) & 0x08) === 0x08);
    checkTrue('enable_interrupt_routes_to_int2_clears_ctrl_reg5_bit',
        (lastValueTo(mock2, 0x2E) & 0x08) === 0x00);

    mock2.setRegister(0x2D, [0x10]);
    await full.disableInterrupt(SOURCE_ORIENTATION);
    checkTrue('disable_interrupt_clears_ctrl_reg4_bit',
        (lastValueTo(mock2, 0x2D) & 0x10) === 0x00);

    threw = false;
    try { await full.enableInterrupt(0x02, 1); } catch (e) { threw = true; }
    checkTrue('enable_interrupt_rejects_unknown_source', threw);

    // --- pollInterrupt ----------------------------------------------------
    mock2.setRegister(0x0C, [0x00]);
    checkTrue('poll_interrupt_returns_int_source', (await full.pollInterrupt()) === 0x00);

    mock2.setRegister(0x0C, [0x10]);   // SOURCE_ORIENTATION
    const polled = await full.pollInterrupt();
    checkTrue('poll_interrupt_returns_status_bits', polled === 0x10);
    // The PL_STATUS register must be read to clear the orientation flag.
    checkTrue('poll_interrupt_reads_pl_status_to_clear',
        mock2.writes.some(w => w.length === 1 && w[0] === 0x10));

    mock2.setRegister(0x0C, [0x01]);   // SOURCE_DATA_READY only
    await full.pollInterrupt();
    // Reading STATUS must NOT happen for a data-ready-only interrupt.
    const statusReadsBefore = mock2.writes.filter(w => w.length === 1 && w[0] === 0x00).length;
    mock2.setRegister(0x0C, [0x01]);
    await full.pollInterrupt();
    const statusReadsAfter = mock2.writes.filter(w => w.length === 1 && w[0] === 0x00).length;
    checkTrue('poll_interrupt_leaves_data_ready_alone',
        statusReadsAfter === statusReadsBefore);

    mock2.setRegister(0x0C, [0x40]);   // SOURCE_FIFO
    await full.pollInterrupt();
    checkTrue('poll_interrupt_reads_f_status_to_clear_fifo',
        mock2.writes.some(w => w.length === 1 && w[0] === 0x00));

    mock2.setRegister(0x0C, [0x80]);   // SOURCE_AUTO_SLEEP
    await full.pollInterrupt();
    checkTrue('poll_interrupt_reads_sysmod_to_clear_auto_sleep',
        mock2.writes.some(w => w.length === 1 && w[0] === 0x0B));

    // --- readRaw / status / system mode ----------------------------------
    mock2.setRegister(0x01, [0xE0, 0x00, 0x00, 0x00, 0x40, 0x00]);
    const raw = await full.readRaw();
    checkTrue('read_raw_x_minus_2048', raw[0] === -2048);
    checkTrue('read_raw_y_zero', raw[1] === 0);
    checkTrue('read_raw_z_4096', raw[2] === 4096);

    mock2.setRegister(0x00, [0x08]);   // STATUS.ZYXDR
    checkTrue('new_data_available_true', (await full.newDataAvailable()) === true);
    checkTrue('read_status', (await full.readStatus()) === 0x08);

    mock2.setRegister(0x0B, [0x01]);   // SYSMOD = wake
    checkTrue('read_system_mode_wake', (await full.readSystemMode()) === 1);
    mock2.setRegister(0x0B, [0x02]);   // SYSMOD = sleep
    checkTrue('read_system_mode_sleep', (await full.readSystemMode()) === 2);

    // --- standby / activate ------------------------------------------------
    mock2.setRegister(0x2A, [0x19]);
    await full.standby();
    checkTrue('standby_clears_active_bit',
        (lastValueTo(mock2, 0x2A) & 0x01) === 0x00);
    await full.activate();
    checkTrue('activate_sets_active_bit',
        (lastValueTo(mock2, 0x2A) & 0x01) === 0x01);

    // --- high-pass output --------------------------------------------------
    await full.setHighPassOutput(true, 2);
    checkTrue('set_high_pass_output_sets_hpf_out',
        (lastValueTo(mock2, 0x0E) & 0x10) === 0x10);
    checkTrue('set_high_pass_output_sets_sel',
        (lastValueTo(mock2, 0x0F) & 0x03) === 0x02);
    await full.setHighPassOutput(false, 0);
    checkTrue('set_high_pass_output_clears_hpf_out',
        (lastValueTo(mock2, 0x0E) & 0x10) === 0x00);

    // --- reset -------------------------------------------------------------
    mock2.setRegister(0x2B, [0x00]);
    await full.reset();
    checkTrue('reset_writes_rst_bit',
        mock2.writes.some(w => w.length === 2 && w[0] === 0x2B && (w[1] & 0x40) === 0x40));

    // --- self test ---------------------------------------------------------
    const mock3 = newMock();
    // +1 g on Z before and after the self-test -> no delta -> ok = false.
    const full3 = new MMA8451QFull(mock3);
    await full3.init();
    const st = await full3.selfTest();
    checkTrue('self_test_returns_object', typeof st === 'object' &&
        typeof st.ok === 'boolean' && typeof st.dx === 'number');
    checkTrue('self_test_ok_false_without_delta', st.ok === false);

    // --- identity mismatch -------------------------------------------------
    const mockBad = new I2CConnectionMock();
    mockBad.setRegister(0x0D, [0x00]);   // not 0x1A
    const bad = new MMA8451QMinimal(mockBad);
    let initFailed = false;
    try { await bad.init(); } catch (e) { initFailed = true; }
    checkTrue('init_rejects_wrong_who_am_i', initFailed);

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.log('FAIL exception:', e.message, e.stack); process.exit(1); });
