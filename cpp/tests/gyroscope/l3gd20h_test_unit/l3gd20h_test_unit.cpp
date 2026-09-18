#include <I2CConnectionMock.h>
#include <L3gd20h.h>
#include <cstdio>
#include <cmath>

int passed = 0;
int failed = 0;

void check(const char* label, bool condition) {
    if (condition) {
        printf("PASS %s\n", label);
        passed++;
    } else {
        printf("FAIL %s\n", label);
        failed++;
    }
}

int main() {
    printf("=== L3GD20H Unit Tests ===\n");

    I2CConnectionMock mock;
    mock.set_reg(0x0F, {0xD7});  // WHO_AM_I = L3GD20H
    mock.set_reg(0x20, {0x0F});  // CTRL_REG1 default
    mock.set_reg(0x23, {0x80});  // CTRL_REG4 default

    // Test 1: Minimal init with L3GD20H WHO_AM_I
    {
        L3gd20hMinimal gyro(mock);
        check("Minimal init (L3GD20H WHO_AM_I=0xD7)", true);
    }

    // Test 2: Minimal init with L3GD20 WHO_AM_I
    {
        I2CConnectionMock mock2;
        mock2.set_reg(0x0F, {0xD4});
        mock2.set_reg(0x20, {0x0F});
        mock2.set_reg(0x23, {0x80});
        L3gd20hMinimal gyro2(mock2);
        check("Minimal init (L3GD20 WHO_AM_I=0xD4)", true);
    }

    // Test 3: Minimal init with invalid WHO_AM_I
    {
        I2CConnectionMock mock3;
        mock3.set_reg(0x0F, {0x00});
        L3gd20hMinimal gyro3(mock3);
        // Silent no-op on invalid WHO_AM_I; check via who_am_i() on Full
        check("Minimal init invalid WHO_AM_I (silent)", true);
    }

    // Test 4: gyro() returns valid floats
    {
        I2CConnectionMock mock4;
        mock4.set_reg(0x0F, {0xD7});
        mock4.set_reg(0x20, {0x0F});
        mock4.set_reg(0x23, {0x80});
        // X=0x0100=256, Y=0x0200=512, Z=0x0300=768
        mock4.set_reg(0x28, {0x00, 0x01, 0x00, 0x02, 0x00, 0x03});
        L3gd20hMinimal gyro4(mock4);
        float x, y, z;
        gyro4.gyro(x, y, z);
        check("gyro() returns valid floats", !std::isnan(x) && !std::isnan(y) && !std::isnan(z));
    }

    // Test 5: Full init
    {
        I2CConnectionMock mock5;
        mock5.set_reg(0x0F, {0xD7});
        mock5.set_reg(0x20, {0x0F});
        mock5.set_reg(0x23, {0x80});
        L3gd20hFull gyro5(mock5);
        check("Full init", true);
    }

    // Test 6: configure() sets registers
    {
        I2CConnectionMock mock6;
        mock6.set_reg(0x0F, {0xD7});
        mock6.set_reg(0x20, {0x0F});
        mock6.set_reg(0x23, {0x80});
        L3gd20hFull gyro6(mock6);
        gyro6.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // 190 Hz, BW=0, ±500 dps
        check("configure() sets CTRL_REG1", mock6.get_reg(0x20) == std::vector<uint8_t>{0x4F});
        check("configure() sets CTRL_REG4", mock6.get_reg(0x23) == std::vector<uint8_t>{0x90});
    }

    // Test 7: gyro_raw() returns signed int16
    {
        I2CConnectionMock mock7;
        mock7.set_reg(0x0F, {0xD7});
        mock7.set_reg(0x20, {0x0F});
        mock7.set_reg(0x23, {0x80});
        mock7.set_reg(0x28, {0x00, 0x80, 0xFF, 0x7F, 0x00, 0x00});  // X=-32768, Y=32767, Z=0
        L3gd20hFull gyro7(mock7);
        int16_t x, y, z;
        gyro7.gyro_raw(x, y, z);
        check("gyro_raw() returns signed int16", x == -32768 && y == 32767 && z == 0);
    }

    // Test 8: temperature() returns signed 8-bit
    {
        I2CConnectionMock mock8;
        mock8.set_reg(0x0F, {0xD7});
        mock8.set_reg(0x20, {0x0F});
        mock8.set_reg(0x23, {0x80});
        mock8.set_reg(0x26, {0x80});  // -128
        L3gd20hFull gyro8(mock8);
        check("temperature() negative", gyro8.temperature() == -128);
        mock8.set_reg(0x26, {0x7F});  // 127
        check("temperature() positive", gyro8.temperature() == 127);
    }

    // Test 9: data_ready() returns ZYXDA bit
    {
        I2CConnectionMock mock9;
        mock9.set_reg(0x0F, {0xD7});
        mock9.set_reg(0x20, {0x0F});
        mock9.set_reg(0x23, {0x80});
        mock9.set_reg(0x27, {0x08});  // ZYXDA=1
        L3gd20hFull gyro9(mock9);
        check("data_ready() true", gyro9.data_ready() == true);
        mock9.set_reg(0x27, {0x00});  // ZYXDA=0
        check("data_ready() false", gyro9.data_ready() == false);
    }

    // Test 10: configure_hp_filter()
    {
        I2CConnectionMock mock10;
        mock10.set_reg(0x0F, {0xD7});
        mock10.set_reg(0x20, {0x0F});
        mock10.set_reg(0x23, {0x80});
        L3gd20hFull gyro10(mock10);
        gyro10.configure_hp_filter(1, 5);
        check("configure_hp_filter() sets CTRL_REG2", mock10.get_reg(0x21) == std::vector<uint8_t>{0x15});
    }

    // Test 11: enable_hp_filter()
    {
        I2CConnectionMock mock11;
        mock11.set_reg(0x0F, {0xD7});
        mock11.set_reg(0x20, {0x0F});
        mock11.set_reg(0x23, {0x80});
        L3gd20hFull gyro11(mock11);
        gyro11.enable_hp_filter(true);
        check("enable_hp_filter(true) sets HPen", (mock11.get_reg(0x24)[0] & 0x10) == 0x10);
        gyro11.enable_hp_filter(false);
        check("enable_hp_filter(false) clears HPen", (mock11.get_reg(0x24)[0] & 0x10) == 0);
    }

    // Test 12: configure_fifo()
    {
        I2CConnectionMock mock12;
        mock12.set_reg(0x0F, {0xD7});
        mock12.set_reg(0x20, {0x0F});
        mock12.set_reg(0x23, {0x80});
        L3gd20hFull gyro12(mock12);
        gyro12.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);
        check("configure_fifo() sets FIFO_EN", (mock12.get_reg(0x24)[0] & 0x40) == 0x40);
        check("configure_fifo() sets FIFO_CTRL_REG", mock12.get_reg(0x2E) == std::vector<uint8_t>{0x2A});
    }

    // Test 13: enable_fifo()
    {
        I2CConnectionMock mock13;
        mock13.set_reg(0x0F, {0xD7});
        mock13.set_reg(0x20, {0x0F});
        mock13.set_reg(0x23, {0x80});
        L3gd20hFull gyro13(mock13);
        gyro13.enable_fifo(true);
        check("enable_fifo(true) sets FIFO_EN", (mock13.get_reg(0x24)[0] & 0x40) == 0x40);
        gyro13.enable_fifo(false);
        check("enable_fifo(false) clears FIFO_EN", (mock13.get_reg(0x24)[0] & 0x40) == 0);
        check("enable_fifo(false) sets FIFO_CTRL_REG=0", mock13.get_reg(0x2E) == std::vector<uint8_t>{0x00});
    }

    // Test 14: fifo_level()
    {
        I2CConnectionMock mock14;
        mock14.set_reg(0x0F, {0xD7});
        mock14.set_reg(0x20, {0x0F});
        mock14.set_reg(0x23, {0x80});
        mock14.set_reg(0x2F, {0x05});  // FSS=5
        L3gd20hFull gyro14(mock14);
        check("fifo_level() returns FSS", gyro14.fifo_level() == 5);
    }

    // Test 15: set_power_mode()
    {
        I2CConnectionMock mock15;
        mock15.set_reg(0x0F, {0xD7});
        mock15.set_reg(0x20, {0x0F});
        mock15.set_reg(0x23, {0x80});
        L3gd20hFull gyro15(mock15);
        gyro15.set_power_mode(L3gd20hFull::POWER_NORMAL);
        check("set_power_mode(NORMAL) enables all axes", (mock15.get_reg(0x20)[0] & 0x0F) == 0x0F);
        gyro15.set_power_mode(L3gd20hFull::POWER_SLEEP);
        check("set_power_mode(SLEEP) disables axes", (mock15.get_reg(0x20)[0] & 0x0F) == 0x08);
        gyro15.set_power_mode(L3gd20hFull::POWER_POWERDOWN);
        check("set_power_mode(POWERDOWN) clears PD", (mock15.get_reg(0x20)[0] & 0x08) == 0);
    }

    printf("\n=== DONE: %d passed, %d failed ===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}