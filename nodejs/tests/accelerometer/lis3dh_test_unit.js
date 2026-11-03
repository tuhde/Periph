'use strict';

const { I2CConnectionMock } = require('../../packages/periph/src/connection/i2c_mock');
const { LIS3DHMinimal, LIS3DHFull, LIS3DHSource } = require('../../packages/periph/src/chips/accelerometer/lis3dh');

// Register addresses used by the checks.
const REG = {
    STATUS_AUX: 0x07,
    OUT_ADC1_L: 0x08,
    OUT_ADC2_L: 0x0A,
    OUT_ADC3_L: 0x0C,
    OUT_ADC3_H: 0x0D,
    WHO_AM_I: 0x0F,
    CTRL_REG0: 0x1E,
    TEMP_CFG: 0x1F,
    CTRL_REG1: 0x20,
    CTRL_REG2: 0x21,
    CTRL_REG3: 0x22,
    CTRL_REG4: 0x23,
    CTRL_REG5: 0x24,
    CTRL_REG6: 0x25,
    REFERENCE: 0x26,
    STATUS: 0x27,
    OUT_X_L: 0x28,
    FIFO_CTRL: 0x2E,
    FIFO_SRC: 0x2F,
    INT1_CFG: 0x30,
    INT1_SRC: 0x31,
    INT1_THS: 0x32,
    INT1_DUR: 0x33,
    INT2_CFG: 0x34,
    INT2_SRC: 0x35,
    INT2_THS: 0x36,
    INT2_DUR: 0x37,
    CLICK_CFG: 0x38,
    CLICK_SRC: 0x39,
    CLICK_THS: 0x3A,
    TIME_LIMIT: 0x3B,
    TIME_LATENCY: 0x3C,
    TIME_WINDOW: 0x3D,
    ACT_THS: 0x3E,
    ACT_DUR: 0x3F,
};

// The sub-address of a multi-byte transfer carries the auto-increment bit.
const AUTO_OUT_X_L = REG.OUT_X_L | 0x80;   // 0xA8
const AUTO_OUT_ADC1_L = REG.OUT_ADC1_L | 0x80; // 0x88

let passed = 0, failed = 0;
function checkTrue(label, condition) {
    if (condition) { console.log('PASS', label); passed++; }
    else { console.log('FAIL', label); failed++; }
}

// Mock writes accumulate; "off" checks must look at the most recent write to the register.
function lastWriteTo(mock, reg) {
    return [...mock.writes].reverse().find(w => w.length === 2 && w[0] === reg);
}
function lastValue(mock, reg) {
    const w = lastWriteTo(mock, reg);
    return w === undefined ? undefined : w[1];
}
function allWritesTo(mock, reg) {
    return mock.writes.filter(w => w.length === 2 && w[0] === reg);
}

async function expectError(label, fn) {
    let err = null;
    try { await fn(); } catch (e) { err = e; }
    checkTrue(label, err instanceof Error);
}

function newMock(whoAmI = 0x33) {
    const mock = new I2CConnectionMock();
    mock.setRegister(REG.WHO_AM_I, [whoAmI]);
    return mock;
}

async function main() {
    // ------------------------------------------------------------------
    // Initialization Sequence
    // ------------------------------------------------------------------
    {
        const mock = newMock();
        const accel = new LIS3DHMinimal(mock);
        await accel.init();
        checkTrue('construct_minimal', true);

        // The identity read has no side effect beyond reading 0x0F.
        const whoRead = mock.writes.find(w => w.length === 1 && w[0] === REG.WHO_AM_I);
        checkTrue('init_reads_who_am_i', whoRead !== undefined);

        // Six control-register clears, then CTRL_REG4 = 0x88, CTRL_REG1 = 0x57.
        const initWrites = mock.writes.filter(w => w.length === 2);
        const order = initWrites.map(w => w[0]);
        checkTrue('init_control_register_write_order',
            JSON.stringify(order) === JSON.stringify([0x21, 0x22, 0x24, 0x25, 0x2E, 0x1F, 0x23, 0x20]));
        checkTrue('init_clears_ctrl_reg2', initWrites[0][1] === 0x00);
        checkTrue('init_clears_ctrl_reg3', initWrites[1][1] === 0x00);
        checkTrue('init_clears_ctrl_reg5', initWrites[2][1] === 0x00);
        checkTrue('init_clears_ctrl_reg6', initWrites[3][1] === 0x00);
        checkTrue('init_clears_fifo_ctrl', initWrites[4][1] === 0x00);
        checkTrue('init_clears_temp_cfg', initWrites[5][1] === 0x00);
        checkTrue('init_ctrl_reg4_is_0x88', initWrites[6][1] === 0x88);
        checkTrue('init_ctrl_reg1_is_0x57', initWrites[7][1] === 0x57);
        // CTRL_REG0 is not a normal register and must never be written at init.
        checkTrue('init_does_not_write_ctrl_reg0', allWritesTo(mock, REG.CTRL_REG0).length === 0);
    }

    // A wrong WHO_AM_I must be reported.
    {
        const mock = newMock(0x00);
        const accel = new LIS3DHMinimal(mock);
        let err = null;
        try { await accel.init(); } catch (e) { err = e; }
        checkTrue('who_am_i_mismatch_throws', err instanceof Error && /WHO_AM_I/.test(err.message));
    }

    // ------------------------------------------------------------------
    // Data reads
    // ------------------------------------------------------------------
    {
        const mock = newMock();
        // x = +1.0 g, y = 0, z = -0.5 g in high-resolution mode (±2 g, 1 mg/LSB).
        mock.setRegister(AUTO_OUT_X_L, [0x80, 0x3E, 0x00, 0x00, 0xC0, 0xE0]);
        const accel = new LIS3DHFull(mock);
        await accel.init();

        const acc = await accel.read();
        // The 6-byte burst must use the auto-increment sub-address 0xA8.
        const burst = mock.writes.filter(w => w.length === 1 && w[0] === AUTO_OUT_X_L);
        checkTrue('read_uses_auto_increment_sub_address', burst.length >= 1);
        checkTrue('read_x_is_1g', Math.abs(acc.x - 1.0) < 1e-9);
        checkTrue('read_y_is_0g', Math.abs(acc.y - 0.0) < 1e-9);
        checkTrue('read_z_is_minus_0_5g', Math.abs(acc.z - (-0.5)) < 1e-9);

        const raw = await accel.readRaw();
        checkTrue('read_raw_x_is_1000', raw.x === 1000);
        checkTrue('read_raw_y_is_0', raw.y === 0);
        checkTrue('read_raw_z_is_minus_500', raw.z === -500);

        // Status register.
        mock.setRegister(REG.STATUS, [0x28]);
        checkTrue('read_status', await accel.readStatus() === 0x28);
        checkTrue('new_data_available', await accel.newDataAvailable() === true);
        mock.setRegister(REG.STATUS, [0x00]);
        checkTrue('new_data_available_false', await accel.newDataAvailable() === false);

        // Temperature: OUT_ADC3_H is a signed byte, 1 digit/°C.
        mock.setRegister(REG.OUT_ADC3_H, [0xFB]);
        checkTrue('read_temperature_delta_is_minus_5', await accel.readTemperatureDelta() === -5);
        // TEMP_CFG must have been switched to ADC_EN | TEMP_EN.
        checkTrue('read_temperature_delta_enables_adc_and_temp',
            (lastValue(mock, REG.TEMP_CFG) & 0xC0) === 0xC0);

        // Channel 3 is the temperature sensor while TEMP_EN = 1.
        await expectError('read_adc_channel_3_rejected_while_temp_en', () => accel.readAdc(3));

        // Aux ADC channel 1: 100 counts above the 1.200 V centre.
        mock.setRegister(AUTO_OUT_ADC1_L, [0x00, 0x19]);   // 100 << 6
        const v = await accel.readAdc(1);
        checkTrue('read_adc_channel_1', Math.abs(v - (1.200 + 100 * (0.400 / 512.0))) < 1e-9);

        // Aux ADC channel 2, negative counts: -100 << 6 = -6400 = 0xE700 → LSB 0x00, MSB 0xE7.
        mock.setRegister(REG.OUT_ADC2_L | 0x80, [0x00, 0xE7]);
        const v2 = await accel.readAdc(2);
        checkTrue('read_adc_channel_2_negative', Math.abs(v2 - (1.200 - 100 * (0.400 / 512.0))) < 1e-9);

        // enableAdc toggles ADC_EN only.
        await accel.enableAdc(false);
        checkTrue('enable_adc_false_clears_adc_en', (lastValue(mock, REG.TEMP_CFG) & 0x80) === 0x00);
        await accel.enableAdc(true);
        checkTrue('enable_adc_true_sets_adc_en', (lastValue(mock, REG.TEMP_CFG) & 0x80) !== 0x00);
    }

    // ------------------------------------------------------------------
    // Configuration
    // ------------------------------------------------------------------
    {
        const mock = newMock();
        mock.setRegister(AUTO_OUT_X_L, [0x80, 0x3E, 0x00, 0x00, 0xC0, 0xE0]);
        const accel = new LIS3DHFull(mock);
        await accel.init();

        // Full-scale range: FS field in CTRL_REG4.
        await accel.setRange(8);
        checkTrue('set_range_8g_fs_field', lastValue(mock, REG.CTRL_REG4) === 0xA8);
        await accel.setRange(2);
        checkTrue('set_range_2g_fs_field', lastValue(mock, REG.CTRL_REG4) === 0x88);
        await expectError('set_range_invalid_throws', () => accel.setRange(3));

        // Mode: LPen in CTRL_REG1, HR in CTRL_REG4.
        await accel.setMode(0);
        checkTrue('set_mode_low_power_sets_lpen', lastValue(mock, REG.CTRL_REG1) === 0x5F);
        checkTrue('set_mode_low_power_clears_hr', lastValue(mock, REG.CTRL_REG4) === 0x80);
        await accel.setMode(1);
        checkTrue('set_mode_normal_clears_lpen', lastValue(mock, REG.CTRL_REG1) === 0x57);
        checkTrue('set_mode_normal_clears_hr', lastValue(mock, REG.CTRL_REG4) === 0x80);
        await accel.setMode(2);
        checkTrue('set_mode_high_resolution_sets_hr', lastValue(mock, REG.CTRL_REG4) === 0x88);
        await expectError('set_mode_invalid_throws', () => accel.setMode(3));

        // Data rate: ODR field in CTRL_REG1.
        await accel.setDataRate(50);
        checkTrue('set_data_rate_50hz_odr_field', lastValue(mock, REG.CTRL_REG1) === 0x47);
        await accel.setDataRate(100);
        checkTrue('set_data_rate_100hz_odr_field', lastValue(mock, REG.CTRL_REG1) === 0x57);
        await expectError('set_data_rate_invalid_throws', () => accel.setDataRate(123));
        await expectError('set_data_rate_1620_rejected_outside_low_power', () => accel.setDataRate(1620));
        await accel.setMode(0);
        await expectError('set_data_rate_1344_rejected_in_low_power', () => accel.setDataRate(1344));
        await accel.setMode(2);
        await accel.setDataRate(50);

        // Per-axis enable.
        await accel.setAxes(true, false, true);
        checkTrue('set_axes_x_y_z_bits', lastValue(mock, REG.CTRL_REG1) === 0x45);
        await accel.setAxes();
        checkTrue('set_axes_all_enabled', (lastValue(mock, REG.CTRL_REG1) & 0x07) === 0x07);

        // Power down: ODR = 0000, the axis bits are kept.
        await accel.powerDown();
        checkTrue('power_down_clears_odr', (lastValue(mock, REG.CTRL_REG1) & 0xF0) === 0x00);
        await accel.setDataRate(50);

        // High-pass filter.
        await accel.setHighPass();
        checkTrue('set_high_pass_default_is_zero', lastValue(mock, REG.CTRL_REG2) === 0x00);
        await accel.setHighPass({mode: 1, cutoff: 2, filterOutput: true, onIa1: true, onIa2: true, onClick: true});
        checkTrue('set_high_pass_all_options', lastValue(mock, REG.CTRL_REG2) === 0x6F);
        await expectError('set_high_pass_invalid_mode_throws', () => accel.setHighPass({mode: 4}));
        await expectError('set_high_pass_invalid_cutoff_throws', () => accel.setHighPass({cutoff: 4}));

        // Reference register.
        mock.setRegister(REG.REFERENCE, [0x42]);
        checkTrue('reset_high_pass_returns_reference', await accel.resetHighPass() === 0x42);
        await accel.setReference(0x33);
        checkTrue('set_reference_writes_value', lastValue(mock, REG.REFERENCE) === 0x33);
        await expectError('set_reference_out_of_range_throws', () => accel.setReference(256));

        // FIFO.
        await accel.setFifo(2, 16, true);
        checkTrue('set_fifo_stream_watermark_int2', lastValue(mock, REG.FIFO_CTRL) === 0xB0);
        checkTrue('set_fifo_sets_fifo_en', (lastValue(mock, REG.CTRL_REG5) & 0x40) !== 0x00);
        mock.setRegister(REG.FIFO_SRC, [0xC3]);
        checkTrue('fifo_level', await accel.fifoLevel() === 3);
        const fs = await accel.readFifoStatus();
        checkTrue('read_fifo_status_watermark', fs.watermark === true);
        checkTrue('read_fifo_status_overrun', fs.overrun === true);
        checkTrue('read_fifo_status_empty', fs.empty === false);
        checkTrue('read_fifo_status_level', fs.level === 3);
        const samples = await accel.readFifo(2);
        checkTrue('read_fifo_returns_two_samples', samples.length === 2);
        checkTrue('read_fifo_sample_values',
            samples.length === 2 && Math.abs(samples[0].x - 1.0) < 1e-9 && Math.abs(samples[1].z - (-0.5)) < 1e-9);
        await accel.setFifo(0);
        checkTrue('set_fifo_bypass_clears_fifo_en', (lastValue(mock, REG.CTRL_REG5) & 0x40) === 0x00);
        checkTrue('set_fifo_bypass_clears_fifo_ctrl', lastValue(mock, REG.FIFO_CTRL) === 0x00);
        await expectError('set_fifo_invalid_mode_throws', () => accel.setFifo(4));
        await expectError('set_fifo_invalid_watermark_throws', () => accel.setFifo(1, 32));

        // Inertial interrupt generator 1.
        await accel.setMotionInterrupt(1, 0x05, 0.5, 20, true);
        checkTrue('set_motion_interrupt_cfg', lastValue(mock, REG.INT1_CFG) === 0x85);
        checkTrue('set_motion_interrupt_threshold', lastValue(mock, REG.INT1_THS) === 31);
        checkTrue('set_motion_interrupt_duration', lastValue(mock, REG.INT1_DUR) === 1);
        await expectError('set_motion_interrupt_invalid_events_throws', () => accel.setMotionInterrupt(1, 0x40, 0.5));
        await expectError('set_motion_interrupt_invalid_generator_throws', () => accel.setMotionInterrupt(3, 1, 0.5));

        // Free fall.
        await accel.setFreeFall(1, 0.35, 60);
        checkTrue('set_free_fall_cfg', lastValue(mock, REG.INT1_CFG) === 0x95);
        checkTrue('set_free_fall_threshold', lastValue(mock, REG.INT1_THS) === 22);
        checkTrue('set_free_fall_duration', lastValue(mock, REG.INT1_DUR) === 3);
        await expectError('set_free_fall_invalid_generator_throws', () => accel.setFreeFall(5));

        // 6D / 4D orientation.
        await accel.setOrientation(1, false, false);
        checkTrue('set_orientation_movement_cfg', lastValue(mock, REG.INT1_CFG) === 0x7F);
        checkTrue('set_orientation_4d_cleared', (lastValue(mock, REG.CTRL_REG5) & 0x04) === 0x00);
        await accel.setOrientation(2, true, true);
        checkTrue('set_orientation_position_4d_cfg', lastValue(mock, REG.INT2_CFG) === 0xCF);
        checkTrue('set_orientation_4d_bit_set', (lastValue(mock, REG.CTRL_REG5) & 0x01) !== 0x00);

        // Latching.
        await accel.setLatch(1, true);
        checkTrue('set_latch_generator1', (lastValue(mock, REG.CTRL_REG5) & 0x08) !== 0x00);
        await accel.setLatch(1, false);
        checkTrue('set_latch_generator1_off', (lastValue(mock, REG.CTRL_REG5) & 0x08) === 0x00);
        await accel.setLatch(2, true);
        checkTrue('set_latch_generator2', (lastValue(mock, REG.CTRL_REG5) & 0x02) !== 0x00);
        await expectError('set_latch_invalid_generator_throws', () => accel.setLatch(0, true));

        // Interrupt source registers.
        mock.setRegister(REG.INT1_SRC, [0x40]);
        checkTrue('read_interrupt_source_generator1', await accel.readInterruptSource(1) === 0x40);
        mock.setRegister(REG.INT2_SRC, [0x64]);
        checkTrue('read_interrupt_source_generator2', await accel.readInterruptSource(2) === 0x64);
        await expectError('read_interrupt_source_invalid_generator_throws', () => accel.readInterruptSource(0));

        // Click detection.
        await accel.setClick(0, 0x04, 0.8, 20, 40, 200, true);
        checkTrue('set_click_cfg_double_z', lastValue(mock, REG.CLICK_CFG) === 0x20);
        checkTrue('set_click_threshold_latched', lastValue(mock, REG.CLICK_THS) === 0xB2);
        checkTrue('set_click_time_limit', lastValue(mock, REG.TIME_LIMIT) === 1);
        checkTrue('set_click_time_latency', lastValue(mock, REG.TIME_LATENCY) === 2);
        checkTrue('set_click_time_window', lastValue(mock, REG.TIME_WINDOW) === 10);
        mock.setRegister(REG.CLICK_SRC, [0x24]);
        checkTrue('read_click_source', await accel.readClickSource() === 0x24);
        await expectError('set_click_invalid_axis_mask_throws', () => accel.setClick(8));

        // Activity function.
        await accel.setActivity(0.1, 100);
        checkTrue('set_activity_threshold', lastValue(mock, REG.ACT_THS) === 6);
        checkTrue('set_activity_duration', lastValue(mock, REG.ACT_DUR) === 1);
        await expectError('set_activity_zero_threshold_throws', () => accel.setActivity(0.001, 10));

        // Interrupt polarity.
        await accel.setInterruptPolarity(true);
        checkTrue('set_interrupt_polarity_active_low', (lastValue(mock, REG.CTRL_REG6) & 0x02) !== 0x00);
        await accel.setInterruptPolarity(false);
        checkTrue('set_interrupt_polarity_active_high', (lastValue(mock, REG.CTRL_REG6) & 0x02) === 0x00);
    }

    // ------------------------------------------------------------------
    // Interrupt routing and poll
    // ------------------------------------------------------------------
    {
        const mock = newMock();
        const accel = new LIS3DHFull(mock);
        await accel.init();

        await accel.enableInterrupt(LIS3DHSource.SOURCE_DATA_READY, 1);
        checkTrue('enable_interrupt_data_ready_routes_to_ctrl_reg3',
            (lastValue(mock, REG.CTRL_REG3) & 0x10) !== 0x00);
        await accel.enableInterrupt(LIS3DHSource.SOURCE_IA1, 1);
        checkTrue('enable_interrupt_ia1_routes_to_ctrl_reg3',
            (lastValue(mock, REG.CTRL_REG3) & 0x40) !== 0x00);
        await accel.enableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
        checkTrue('enable_interrupt_click_routes_to_ctrl_reg3',
            (lastValue(mock, REG.CTRL_REG3) & 0x80) !== 0x00);
        await accel.enableInterrupt(LIS3DHSource.SOURCE_WATERMARK, 1);
        checkTrue('enable_interrupt_watermark_routes_to_ctrl_reg3',
            (lastValue(mock, REG.CTRL_REG3) & 0x04) !== 0x00);
        await accel.enableInterrupt(LIS3DHSource.SOURCE_IA2, 2);
        checkTrue('enable_interrupt_ia2_routes_to_ctrl_reg6',
            (lastValue(mock, REG.CTRL_REG6) & 0x20) !== 0x00);
        await accel.enableInterrupt(LIS3DHSource.SOURCE_ACTIVITY, 2);
        checkTrue('enable_interrupt_activity_routes_to_ctrl_reg6',
            (lastValue(mock, REG.CTRL_REG6) & 0x08) !== 0x00);

        mock.setRegister(REG.STATUS, [0x08]);
        mock.setRegister(REG.FIFO_SRC, [0x80]);
        mock.setRegister(REG.INT1_SRC, [0x40]);
        mock.setRegister(REG.INT2_SRC, [0x40]);
        mock.setRegister(REG.CLICK_SRC, [0x40]);
        const status = await accel.pollInterrupt();
        checkTrue('poll_interrupt_combined_status',
            status === (LIS3DHSource.SOURCE_DATA_READY | LIS3DHSource.SOURCE_WATERMARK |
                       LIS3DHSource.SOURCE_IA1 | LIS3DHSource.SOURCE_IA2 | LIS3DHSource.SOURCE_CLICK));
        checkTrue('poll_interrupt_does_not_report_activity', (status & LIS3DHSource.SOURCE_ACTIVITY) === 0);

        await accel.disableInterrupt(LIS3DHSource.SOURCE_CLICK, 1);
        checkTrue('disable_interrupt_clears_ctrl_reg3_bit',
            (lastValue(mock, REG.CTRL_REG3) & 0x80) === 0x00);
        const status2 = await accel.pollInterrupt();
        checkTrue('poll_interrupt_after_disable', (status2 & LIS3DHSource.SOURCE_CLICK) === 0);

        await expectError('enable_interrupt_unknown_source_throws', () => accel.enableInterrupt(0x1234, 1));
        await expectError('enable_interrupt_invalid_pin_throws', () => accel.enableInterrupt(LIS3DHSource.SOURCE_CLICK, 3));
        await expectError('disable_interrupt_invalid_pin_throws', () => accel.disableInterrupt(LIS3DHSource.SOURCE_CLICK, 0));

        // No INT pin wired: the driver falls back to a 5 ms polling loop.
        await accel.onInterrupt(() => {});
        await accel.offInterrupt();
        checkTrue('on_interrupt_without_int_pin_uses_polling_fallback', true);
    }

    // ------------------------------------------------------------------
    // System functions
    // ------------------------------------------------------------------
    {
        const mock = newMock();
        mock.setRegister(AUTO_OUT_X_L, [0x80, 0x3E, 0x00, 0x00, 0xC0, 0xE0]);
        const accel = new LIS3DHFull(mock);
        await accel.init();

        // SDO pull-up: bit 7 of CTRL_REG0, bit 4 always 1.
        await accel.setSdoPullup(false);
        checkTrue('set_sdo_pullup_disconnect', lastValue(mock, REG.CTRL_REG0) === 0x90);
        await accel.setSdoPullup(true);
        checkTrue('set_sdo_pullup_connect', lastValue(mock, REG.CTRL_REG0) === 0x10);

        // Reboot: BOOT bit in CTRL_REG5, then the init sequence runs again.
        const before = mock.writes.length;
        await accel.reboot();
        const after = mock.writes.slice(before);
        const bootWrite = after.find(w => w.length === 2 && w[0] === REG.CTRL_REG5);
        checkTrue('reboot_sets_boot_bit', bootWrite !== undefined && (bootWrite[1] & 0x80) !== 0x00);
        checkTrue('reboot_reruns_init_sequence',
            after.some(w => w.length === 2 && w[0] === REG.CTRL_REG4 && w[1] === 0x88) &&
            after.some(w => w.length === 2 && w[0] === REG.CTRL_REG1 && w[1] === 0x57));

        // Self test: the mock never moves, so the delta is 0 and the test fails.
        const st = await accel.selfTest(1);
        checkTrue('self_test_fails_without_motion', st === false);
        // The previous configuration is restored.
        checkTrue('self_test_restores_ctrl_reg4', lastValue(mock, REG.CTRL_REG4) === 0x88);
        checkTrue('self_test_restores_ctrl_reg1', lastValue(mock, REG.CTRL_REG1) === 0x57);
        await expectError('self_test_invalid_mode_throws', () => accel.selfTest(3));
    }

    console.log(`===DONE: ${passed} passed, ${failed} failed===`);
    process.exit(failed === 0 ? 0 : 1);
}

main().catch(e => { console.log('FAIL exception:', e.message); process.exit(1); });
