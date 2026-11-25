#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <vector>
#include "I2CConnectionMock.h"
#include "LSM303.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static void check_close(float got, float expected, float tolerance, const char *label) {
    if (fabsf(got - expected) <= tolerance) { printf("PASS %s\n", label); passed++; }
    else { printf("FAIL %s (got %f, expected %f)\n", label, got, expected); failed++; }
}

// The accelerometer sets bit 7 of the sub-address for multi-byte transfers,
// so the driver reads the output registers from 0x28 | 0x80. The mock
// resolves the address literally.
static const uint16_t ACCEL_OUT = 0x28 | 0x80;
static const uint16_t MAG_OUT = 0x03;

// Return the data byte of the last 1-byte register write to `reg`.
static uint8_t lastWriteTo(const I2CConnectionMock& mock, uint8_t reg) {
    uint8_t value = 0;
    const std::vector<std::vector<uint8_t>>& log = mock.writes();
    for (size_t i = 0; i < log.size(); i++) {
        if (log[i].size() == 2 && log[i][0] == reg) value = log[i][1];
    }
    return value;
}

// True when the driver read from `reg`.
static bool readFrom(const I2CConnectionMock& mock, uint8_t reg) {
    const std::vector<std::vector<uint8_t>>& log = mock.writes();
    for (size_t i = 0; i < log.size(); i++) {
        if (log[i].size() == 1 && log[i][0] == reg) return true;
    }
    return false;
}

static void setAccelOutput(I2CConnectionMock& mock, int16_t x, int16_t y, int16_t z) {
    // The high-resolution mode writes the 12-bit value left-justified into
    // the 16-bit register pair.
    uint16_t rx = (uint16_t)(x << 4);
    uint16_t ry = (uint16_t)(y << 4);
    uint16_t rz = (uint16_t)(z << 4);
    mock.setRegister(ACCEL_OUT, {(uint8_t)(rx & 0xFF), (uint8_t)(rx >> 8), (uint8_t)(ry & 0xFF), (uint8_t)(ry >> 8), (uint8_t)(rz & 0xFF), (uint8_t)(rz >> 8)});
}

static void setAccelRaw(I2CConnectionMock& mock, uint16_t x, uint16_t y, uint16_t z) {
    mock.setRegister(ACCEL_OUT, {(uint8_t)(x & 0xFF), (uint8_t)(x >> 8), (uint8_t)(y & 0xFF), (uint8_t)(y >> 8), (uint8_t)(z & 0xFF), (uint8_t)(z >> 8)});
}

static void setMagOutput(I2CConnectionMock& mock, int16_t x, int16_t y, int16_t z) {
    // The magnetometer delivers X, Z, Y with the high byte first.
    mock.setRegister(MAG_OUT, {(uint8_t)(x >> 8), (uint8_t)(x & 0xFF), (uint8_t)(z >> 8), (uint8_t)(z & 0xFF), (uint8_t)(y >> 8), (uint8_t)(y & 0xFF)});
}

int main() {
    // --- Minimal: initialization sequence ---------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Minimal compass(accel, mag);
        check_true(lastWriteTo(accel, 0x21) == 0x00, "init_ctrl2_cleared");
        check_true(lastWriteTo(accel, 0x22) == 0x00, "init_ctrl3_cleared");
        check_true(lastWriteTo(accel, 0x24) == 0x00, "init_ctrl5_cleared");
        check_true(lastWriteTo(accel, 0x25) == 0x00, "init_ctrl6_cleared");
        check_true(lastWriteTo(accel, 0x2E) == 0x00, "init_fifo_ctrl_cleared");
        check_true(lastWriteTo(accel, 0x23) == 0x88, "init_ctrl4_bdu_hr");
        check_true(lastWriteTo(accel, 0x20) == 0x57, "init_ctrl1_100hz_axes");
        check_true(lastWriteTo(mag, 0x00) == 0x10, "init_cra_15hz");
        check_true(lastWriteTo(mag, 0x01) == 0x20, "init_crb_130ut");
        check_true(lastWriteTo(mag, 0x02) == 0x00, "init_mr_continuous");
        check_true(readFrom(mag, 0x0A), "init_reads_identity");
    }

    // --- Minimal: acceleration ---------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Minimal compass(accel, mag);
        setAccelOutput(accel, 1, -1, 512);
        float x, y, z;
        compass.readAcceleration(x, y, z);
        check_close(x, 0.001f, 1e-6f, "accel_x");
        check_close(y, -0.001f, 1e-6f, "accel_y");
        check_close(z, 0.512f, 1e-6f, "accel_z");
        check_true(readFrom(accel, (uint8_t)ACCEL_OUT), "accel_uses_auto_increment");
    }

    // --- Minimal: magnetic field -------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Minimal compass(accel, mag);
        setMagOutput(mag, 1100, 0, -980);
        float x, y, z;
        compass.readMagnetic(x, y, z);
        check_close(x, 100.0f, 1e-3f, "mag_x");
        check_close(y, 0.0f, 1e-3f, "mag_y");
        check_close(z, -100.0f, 1e-3f, "mag_z");
        check_true(readFrom(mag, 0x03), "mag_reads_from_0x03");
        check_true(!readFrom(mag, 0x83), "mag_no_auto_increment_bit");
    }

    // --- Full: range, rate, mode, axes --------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);

        check_true(compass.setRange(8), "set_range_ok");
        check_true((accel.registers().at(0x23) & 0x30) == 0x20, "set_range_fs_bits");
        setAccelOutput(accel, 128, 0, 0);
        float x, y, z;
        compass.readAcceleration(x, y, z);
        check_close(x, 0.512f, 1e-6f, "range_changes_sensitivity");
        check_true(!compass.setRange(5), "set_range_rejects_invalid");

        check_true(compass.setRange(2), "set_range_2_ok");
        check_true(compass.setDataRate(400), "set_data_rate_400hz_ok");
        check_true((accel.registers().at(0x20) & 0xF0) == 0x70, "set_data_rate_400hz");
        check_true(compass.setDataRate(0), "set_data_rate_power_down_ok");
        check_true((accel.registers().at(0x20) & 0xF0) == 0x00, "set_data_rate_power_down");
        check_true(!compass.setDataRate(123), "set_data_rate_rejects_invalid");

        check_true(compass.setMode(LSM303Full::MODE_LOW_POWER), "set_mode_low_power_ok");
        check_true((accel.registers().at(0x20) & 0x08) != 0, "mode_low_power_lpen");
        check_true((accel.registers().at(0x23) & 0x08) == 0, "mode_low_power_no_hr");
        setAccelRaw(accel, 0x0100, 0, 0);
        compass.readAcceleration(x, y, z);
        check_close(x, 0.016f, 1e-6f, "low_power_sensitivity");
        check_true(compass.setDataRate(5376), "low_power_allows_5376hz");
        check_true(!compass.setDataRate(1344), "low_power_rejects_1344hz");
        check_true(compass.setDataRate(100), "low_power_back_to_100hz");

        check_true(compass.setMode(LSM303Full::MODE_NORMAL), "set_mode_normal_ok");
        check_true((accel.registers().at(0x20) & 0x08) == 0, "mode_normal_no_lpen");
        check_true((accel.registers().at(0x23) & 0x08) == 0, "mode_normal_no_hr");
        setAccelRaw(accel, 0x0040, 0, 0);
        compass.readAcceleration(x, y, z);
        check_close(x, 0.004f, 1e-6f, "normal_mode_sensitivity");
        check_true(!compass.setDataRate(1620), "normal_rejects_1620hz");
        check_true(!compass.setDataRate(5376), "normal_rejects_5376hz");
        check_true(compass.setDataRate(100), "normal_back_to_100hz");

        check_true(compass.setMode(LSM303Full::MODE_HIGH_RESOLUTION), "set_mode_high_res_ok");
        check_true((accel.registers().at(0x23) & 0x08) != 0, "mode_high_res_hr");
        check_true(!compass.setMode(7), "set_mode_rejects_invalid");

        compass.setAxes(true, false, true);
        check_true((accel.registers().at(0x20) & 0x07) == 0x05, "set_axes_mask");

        compass.powerDown();
        check_true((accel.registers().at(0x20) & 0xF0) == 0x00, "power_down_clears_odr");
        check_true(compass.setDataRate(100), "resume_after_power_down");
    }

    // --- Full: status -------------------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        accel.setRegister(0x27, {0x08});
        check_true(compass.newDataAvailable(), "new_data_available");
        check_true(compass.readStatus() == 0x08, "read_status");
    }

    // --- Full: high-pass filter ---------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        check_true(compass.setHighPass(1, 2, true, true, false, true), "set_high_pass_ok");
        check_true(accel.registers().at(0x21) == (0x40 | 0x20 | 0x08 | 0x04 | 0x01), "high_pass_ctrl2");
        check_true(!compass.setHighPass(4, 0, false, false, false, false), "high_pass_rejects_invalid_mode");
        check_true(!compass.setHighPass(0, 9, false, false, false, false), "high_pass_rejects_invalid_cutoff");
        accel.setRegister(0x26, {0x5A});
        check_true(compass.resetHighPass() == 0x5A, "reset_high_pass_returns_reference");
        compass.setReference(0x33);
        check_true(accel.registers().at(0x26) == 0x33, "set_reference");
    }

    // --- Full: FIFO ----------------------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        check_true(compass.setFifo(LSM303Full::FIFO_STREAM, 16), "set_fifo_ok");
        check_true(accel.registers().at(0x2E) == (0x80 | 16), "fifo_ctrl_mode_watermark");
        check_true((accel.registers().at(0x24) & 0x40) != 0, "fifo_enabled");
        accel.setRegister(0x2F, {0x80 | 0x05});
        check_true(compass.fifoLevel() == 5, "fifo_level");
        bool watermark, overrun, empty;
        uint8_t level;
        compass.readFifoStatus(watermark, overrun, empty, level);
        check_true(watermark && !overrun && !empty && level == 5, "fifo_status");
        float samples[3 * 8];
        uint8_t count = compass.readFifo(samples, 8);
        check_true(count == 5, "fifo_sample_count");
        check_true(!compass.setFifo(4), "fifo_rejects_invalid_mode");
        check_true(!compass.setFifo(1, 32), "fifo_rejects_invalid_watermark");
        check_true(compass.setFifo(LSM303Full::FIFO_BYPASS), "set_fifo_bypass_ok");
        check_true((accel.registers().at(0x24) & 0x40) == 0, "fifo_disabled_in_bypass");
    }

    // --- Full: inertial interrupt generators --------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        check_true(compass.setMotionInterrupt(1, 0x02, 0.5f, 10.0f, false), "set_motion_interrupt_ok");
        check_true(accel.registers().at(0x30) == 0x02, "motion_int_cfg");
        check_true(accel.registers().at(0x32) == 32, "motion_int_threshold");
        check_true(accel.registers().at(0x33) == 1, "motion_int_duration");
        check_true(compass.setMotionInterrupt(2, 0x3F, 2.0f, 10.0f, true), "set_motion_interrupt_2_ok");
        check_true(accel.registers().at(0x34) == (0x80 | 0x3F), "motion_int2_cfg");
        check_true(accel.registers().at(0x36) == 127, "motion_int2_threshold");
        check_true(!compass.setMotionInterrupt(3, 1, 0.5f), "motion_int_rejects_invalid_generator");
        check_true(!compass.setMotionInterrupt(1, 0x40, 0.5f), "motion_int_rejects_invalid_events");

        check_true(compass.setFreeFall(1, 0.35f, 30.0f), "set_free_fall_ok");
        check_true(accel.registers().at(0x30) == 0x95, "free_fall_cfg");
        check_true(accel.registers().at(0x32) == 22, "free_fall_threshold");
        check_true(accel.registers().at(0x33) == 3, "free_fall_duration");

        check_true(compass.setOrientation(1, false, false), "set_orientation_6d_ok");
        check_true(accel.registers().at(0x30) == (0x40 | 0x3F), "orientation_6d_cfg");
        check_true(compass.setOrientation(1, true, false), "set_orientation_position_ok");
        check_true(accel.registers().at(0x30) == (0xC0 | 0x3F), "orientation_position_cfg");
        check_true(compass.setOrientation(2, false, true), "set_orientation_4d_ok");
        check_true(accel.registers().at(0x34) == (0x40 | 0x0F), "orientation_4d_cfg");
        check_true((accel.registers().at(0x24) & 0x01) != 0, "orientation_4d_sets_d4d");
        check_true(compass.setOrientation(2, false, false), "set_orientation_6d_2_ok");
        check_true((accel.registers().at(0x24) & 0x01) == 0, "orientation_4d_cleared");

        check_true(compass.setLatch(1, true), "set_latch_1_ok");
        check_true((accel.registers().at(0x24) & 0x08) != 0, "latch_int1");
        check_true(compass.setLatch(2, true), "set_latch_2_ok");
        check_true((accel.registers().at(0x24) & 0x02) != 0, "latch_int2");
        check_true(compass.setLatch(1, false), "unlatch_1_ok");
        check_true((accel.registers().at(0x24) & 0x08) == 0, "unlatch_int1");
        check_true(!compass.setLatch(3, true), "set_latch_rejects_invalid_generator");

        accel.setRegister(0x31, {0x40});
        check_true(compass.readInterruptSource(1) == 0x40, "read_interrupt_source_1");
        accel.setRegister(0x35, {0x41});
        check_true(compass.readInterruptSource(2) == 0x41, "read_interrupt_source_2");
    }

    // --- Full: click detection -----------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        check_true(compass.setClick(0x01, 0x04, 0.8f, 20.0f, 40.0f, 200.0f), "set_click_ok");
        check_true(accel.registers().at(0x38) == (0x01 | 0x20), "click_cfg");
        check_true(accel.registers().at(0x3A) == 51, "click_threshold");
        check_true(accel.registers().at(0x3B) == 2, "click_time_limit");
        check_true(accel.registers().at(0x3C) == 4, "click_time_latency");
        check_true(accel.registers().at(0x3D) == 20, "click_time_window");
        check_true(!compass.setClick(0x08, 0), "click_rejects_invalid_mask");
        accel.setRegister(0x39, {0x40});
        check_true(compass.readClickSource() == 0x40, "read_click_source");
    }

    // --- Full: interrupt polarity and routing --------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        compass.setInterruptPolarity(true);
        check_true((accel.registers().at(0x25) & 0x02) != 0, "polarity_active_low");
        compass.setInterruptPolarity(false);
        check_true((accel.registers().at(0x25) & 0x02) == 0, "polarity_active_high");

        check_true(compass.enableInterrupt(LSM303Full::SOURCE_CLICK, 1), "route_click_to_int1_ok");
        check_true((accel.registers().at(0x22) & 0x80) != 0, "route_click_to_int1");
        check_true(compass.enableInterrupt(LSM303Full::SOURCE_CLICK, 2), "route_click_to_int2_ok");
        check_true((accel.registers().at(0x25) & 0x80) != 0, "route_click_to_int2");
        check_true(compass.disableInterrupt(LSM303Full::SOURCE_CLICK, 1), "unroute_click_ok");
        check_true((accel.registers().at(0x22) & 0x80) == 0, "unroute_click_from_int1");
        check_true(compass.enableInterrupt(LSM303Full::SOURCE_IA1, 2), "route_ia1_to_int2_ok");
        check_true((accel.registers().at(0x25) & 0x40) != 0, "route_ia1_to_int2");
        check_true(compass.enableInterrupt(LSM303Full::SOURCE_BOOT, 2), "route_boot_to_int2_ok");
        check_true((accel.registers().at(0x25) & 0x10) != 0, "route_boot_to_int2");
        check_true(compass.enableInterrupt(LSM303Full::SOURCE_DATA_READY, 1), "route_data_ready_ok");
        check_true((accel.registers().at(0x22) & 0x10) != 0, "route_data_ready_to_int1");
        check_true(!compass.enableInterrupt(LSM303Full::SOURCE_MAG_DATA_READY), "route_rejects_mag_source");
        check_true(!compass.enableInterrupt(LSM303Full::SOURCE_CLICK, 3), "route_rejects_invalid_pin");
    }

    // --- Full: pollInterrupt --------------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        accel.setRegister(0x27, {0x08});
        accel.setRegister(0x2F, {0x80 | 0x40});
        accel.setRegister(0x31, {0x40});
        accel.setRegister(0x35, {0x40});
        accel.setRegister(0x39, {0x40});
        mag.setRegister(0x09, {0x01});
        uint8_t status = compass.pollInterrupt();
        check_true((status & LSM303Full::SOURCE_DATA_READY) != 0, "poll_interrupt_data_ready");
        check_true((status & LSM303Full::SOURCE_WATERMARK) != 0, "poll_interrupt_watermark");
        check_true((status & LSM303Full::SOURCE_OVERRUN) != 0, "poll_interrupt_overrun");
        check_true((status & LSM303Full::SOURCE_MAG_DATA_READY) != 0, "poll_interrupt_mag_drdy");
        // The generator sources are only read once they are enabled, because
        // reading them clears the latched flags.
        check_true((status & LSM303Full::SOURCE_IA1) == 0, "poll_interrupt_ia1_not_enabled");
        compass.enableInterrupt(LSM303Full::SOURCE_IA1, 1);
        status = compass.pollInterrupt();
        check_true((status & LSM303Full::SOURCE_IA1) != 0, "poll_interrupt_ia1");
    }

    // --- Full: magnetometer ----------------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        check_true(compass.setMagRange(250), "set_mag_range_ok");
        check_true((mag.registers().at(0x01) & 0xE0) == 0x60, "mag_range_gn_bits");
        setMagOutput(mag, 670, 0, 0);
        float x, y, z;
        compass.readMagnetic(x, y, z);
        check_close(x, 100.0f, 1e-3f, "mag_range_changes_gain");
        check_true(!compass.setMagRange(123), "mag_range_rejects_invalid");

        check_true(compass.setMagDataRate(30), "set_mag_data_rate_ok");
        check_true((mag.registers().at(0x00) & 0x1C) == (5 << 2), "mag_data_rate_do");
        check_true(!compass.setMagDataRate(12), "mag_data_rate_rejects_invalid");

        check_true(compass.setMagMode(LSM303Full::MAG_MODE_SLEEP), "set_mag_mode_sleep_ok");
        check_true((mag.registers().at(0x02) & 0x03) == 2, "mag_mode_sleep");
        check_true(compass.setMagMode(LSM303Full::MAG_MODE_SINGLE), "set_mag_mode_single_ok");
        check_true((mag.registers().at(0x02) & 0x03) == 1, "mag_mode_single");
        check_true(compass.setMagMode(LSM303Full::MAG_MODE_CONTINUOUS), "set_mag_mode_continuous_ok");
        check_true((mag.registers().at(0x02) & 0x03) == 0, "mag_mode_continuous");
        check_true(!compass.setMagMode(5), "mag_mode_rejects_invalid");

        uint8_t identity[3];
        compass.readMagIdentity(identity);
        check_true(identity[0] == 0x48 && identity[1] == 0x34 && identity[2] == 0x33, "read_mag_identity");

        mag.setRegister(0x09, {0x03});
        bool drdy, lock;
        compass.readMagStatus(drdy, lock);
        check_true(drdy && lock, "read_mag_status");
        check_true(compass.magDataReady(), "mag_data_ready");

        compass.enableTemperature(true);
        check_true((mag.registers().at(0x00) & 0x80) != 0, "temperature_enabled");
        compass.setMagDataRate(15);
        check_true((mag.registers().at(0x00) & 0x80) != 0, "temperature_kept_on_rate_change");
        mag.setRegister(0x31, {0x02, 0x80});
        check_close(compass.readTemperatureDelta(), 5.0f, 1e-4f, "temperature_delta");
        compass.enableTemperature(false);
        check_true((mag.registers().at(0x00) & 0x80) == 0, "temperature_disabled");
    }

    // --- Full: heading ----------------------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        setAccelOutput(accel, 0, 0, 0);
        setMagOutput(mag, 1100, 0, 0);
        check_close(compass.heading(), 0.0f, 1e-3f, "heading_north");
        setMagOutput(mag, 0, 1100, 0);
        check_close(compass.heading(), 90.0f, 1e-3f, "heading_east");
        setMagOutput(mag, -1100, 0, 0);
        check_close(compass.heading(), 180.0f, 1e-3f, "heading_south");
        setMagOutput(mag, 0, -1100, 0);
        check_close(compass.heading(), 270.0f, 1e-3f, "heading_west");
        setMagOutput(mag, 1100, 0, 0);
        check_close(compass.heading(false), 0.0f, 1e-3f, "flat_heading_north");
    }

    // --- Full: reboot ------------------------------------------------------------
    {
        I2CConnectionMock accel;
        I2CConnectionMock mag;
        mag.setRegister(0x0A, {0x48, 0x34, 0x33});
        LSM303Full compass(accel, mag);
        compass.reboot();
        bool bootBit = false;
        const std::vector<std::vector<uint8_t>>& log = accel.writes();
        for (size_t i = 0; i < log.size(); i++) {
            if (log[i].size() == 2 && log[i][0] == 0x24 && (log[i][1] & 0x80)) bootBit = true;
        }
        check_true(bootBit, "reboot_sets_boot_bit");
        check_true(lastWriteTo(accel, 0x20) == 0x57, "reboot_restores_defaults");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
