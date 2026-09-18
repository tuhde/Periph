#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "L3gd20h.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

class L3gd20hTestAccess : public L3gd20hFull {
public:
    using L3gd20hFull::L3gd20hFull;
    using L3gd20hFull::REG_WHO_AM_I;
    using L3gd20hFull::REG_CTRL_REG1;
    using L3gd20hFull::REG_CTRL_REG2;
    using L3gd20hFull::REG_CTRL_REG4;
    using L3gd20hFull::REG_CTRL_REG5;
    using L3gd20hFull::REG_OUT_TEMP;
    using L3gd20hFull::REG_STATUS;
    using L3gd20hFull::REG_OUT_X_L;
    using L3gd20hFull::REG_FIFO_CTRL;
    using L3gd20hFull::REG_FIFO_SRC;
};

int main() {
    printf("=== L3GD20H Unit Tests ===\n");

    // --- Test 1: Minimal init with L3GD20H WHO_AM_I ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hMinimal gyro(connection);
        check_true(true, "Minimal init (L3GD20H WHO_AM_I=0xD7)");
    }

    // --- Test 2: Minimal init with L3GD20 WHO_AM_I ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD4});
        L3gd20hMinimal gyro(connection);
        check_true(true, "Minimal init (L3GD20 WHO_AM_I=0xD4)");
    }

    // --- Test 3: Init writes CTRL_REG4/CTRL_REG1 defaults ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hMinimal gyro(connection);
        bool sawCtrl4Default = false, sawCtrl1Default = false;
        for (const auto& w : connection.writes()) {
            if (w.size() == 2 && w[0] == L3gd20hTestAccess::REG_CTRL_REG4 && w[1] == 0x80) sawCtrl4Default = true;
            if (w.size() == 2 && w[0] == L3gd20hTestAccess::REG_CTRL_REG1 && w[1] == 0x0F) sawCtrl1Default = true;
        }
        check_true(sawCtrl4Default, "init_writes_ctrl_reg4_default");
        check_true(sawCtrl1Default, "init_writes_ctrl_reg1_default");
    }

    // --- Test 4: gyro() returns valid floats; X=+16, Y=0, Z=-16 ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        connection.setRegister(L3gd20hTestAccess::REG_OUT_X_L | 0x80,
                                {0x10, 0x00,    // X=+16
                                 0x00, 0x00,    // Y=0
                                 0xF0, 0xFF});  // Z=-16
        L3gd20hMinimal gyro(connection);
        float x, y, z;
        gyro.gyro(x, y, z);
        float k = 3.141592653589793f / 180.0f;
        float expected_x = 16.0f * 0.00875f * k;
        float expected_z = -16.0f * 0.00875f * k;
        check_true(fabsf(x - expected_x) < 1e-6f, "gyro_x");
        check_true(fabsf(y - 0.0f) < 1e-6f, "gyro_y");
        check_true(fabsf(z - expected_z) < 1e-6f, "gyro_z");
    }

    // --- Test 5: Full init ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        check_true(true, "Full init");
    }

    // --- Test 6: configure() sets CTRL_REG1/CTRL_REG4 ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        sensor.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // 190 Hz, ±500 dps
        bool sawCtrl1 = false, sawCtrl4 = false;
        for (const auto& w : connection.writes()) {
            if (w.size() == 2 && w[0] == L3gd20hTestAccess::REG_CTRL_REG1 && w[1] == 0x4F) sawCtrl1 = true;
            if (w.size() == 2 && w[0] == L3gd20hTestAccess::REG_CTRL_REG4 && w[1] == 0x90) sawCtrl4 = true;
        }
        check_true(sawCtrl1, "configure_sets_ctrl_reg1");
        check_true(sawCtrl4, "configure_sets_ctrl_reg4");
    }

    // --- Test 7: gyro_raw() returns signed int16 ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        connection.setRegister(L3gd20hTestAccess::REG_OUT_X_L | 0x80,
                                {0x00, 0x80,    // X=-32768
                                 0xFF, 0x7F,    // Y=32767
                                 0x00, 0x00});  // Z=0
        L3gd20hTestAccess sensor(connection);
        int16_t x, y, z;
        sensor.gyro_raw(x, y, z);
        check_true(x == -32768 && y == 32767 && z == 0, "gyro_raw_signed_int16");
    }

    // --- Test 8: temperature() returns signed 8-bit ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_OUT_TEMP, {0x80});
        check_true(sensor.temperature() == -128, "temperature_negative");
        connection.setRegister(L3gd20hTestAccess::REG_OUT_TEMP, {0x7F});
        check_true(sensor.temperature() == 127, "temperature_positive");
    }

    // --- Test 9: data_ready() returns ZYXDA bit ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_STATUS, {0x08});
        check_true(sensor.data_ready() == true, "data_ready_true");
        connection.setRegister(L3gd20hTestAccess::REG_STATUS, {0x00});
        check_true(sensor.data_ready() == false, "data_ready_false");
    }

    // --- Test 10: configure_hp_filter() ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        sensor.configure_hp_filter(1, 5);
        bool sawCtrl2 = false;
        for (const auto& w : connection.writes()) {
            if (w.size() == 2 && w[0] == L3gd20hTestAccess::REG_CTRL_REG2 && w[1] == 0x15) sawCtrl2 = true;
        }
        check_true(sawCtrl2, "configure_hp_filter_sets_ctrl_reg2");
    }

    // --- Test 11: enable_hp_filter() ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_CTRL_REG5, {0x00});
        sensor.enable_hp_filter(true);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG5) & 0x10) == 0x10,
                   "enable_hp_filter_true_sets_hpen");
        sensor.enable_hp_filter(false);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG5) & 0x10) == 0,
                   "enable_hp_filter_false_clears_hpen");
    }

    // --- Test 12: configure_fifo() ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_CTRL_REG5, {0x00});
        sensor.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG5) & 0x40) == 0x40,
                   "configure_fifo_sets_fifo_en");
        check_true(connection.registers().at(L3gd20hTestAccess::REG_FIFO_CTRL) == 0x2A,
                   "configure_fifo_sets_fifo_ctrl_reg");
    }

    // --- Test 13: enable_fifo() ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_CTRL_REG5, {0x00});
        sensor.enable_fifo(true);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG5) & 0x40) == 0x40,
                   "enable_fifo_true_sets_fifo_en");
        sensor.enable_fifo(false);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG5) & 0x40) == 0,
                   "enable_fifo_false_clears_fifo_en");
        check_true(connection.registers().at(L3gd20hTestAccess::REG_FIFO_CTRL) == 0x00,
                   "enable_fifo_false_clears_fifo_ctrl_reg");
    }

    // --- Test 14: fifo_level() ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_FIFO_SRC, {0x05});
        check_true(sensor.fifo_level() == 5, "fifo_level_returns_fss");
    }

    // --- Test 15: set_power_mode() ---
    {
        I2CConnectionMock connection;
        connection.setRegister(L3gd20hTestAccess::REG_WHO_AM_I, {0xD7});
        L3gd20hTestAccess sensor(connection);
        connection.setRegister(L3gd20hTestAccess::REG_CTRL_REG1, {0x00});
        sensor.set_power_mode(L3gd20hFull::POWER_NORMAL);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG1) & 0x0F) == 0x0F,
                   "set_power_mode_normal_enables_all_axes");
        sensor.set_power_mode(L3gd20hFull::POWER_SLEEP);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG1) & 0x0F) == 0x08,
                   "set_power_mode_sleep_disables_axes");
        sensor.set_power_mode(L3gd20hFull::POWER_POWERDOWN);
        check_true((connection.registers().at(L3gd20hTestAccess::REG_CTRL_REG1) & 0x08) == 0,
                   "set_power_mode_powerdown_clears_pd");
    }

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
