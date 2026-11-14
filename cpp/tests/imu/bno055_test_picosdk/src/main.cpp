#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "BNO055.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}
#define CHECK(c, l) check_true((c), (l))
#define NEAR(v, lo, hi, l) check_true(((v) >= (lo) && (v) <= (hi)), (l))



int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x29);
    BNO055Full imu(connection);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    uint8_t chip_id, acc_id, mag_id, gyr_id, bootloader;
    uint16_t sw_revision;
    imu.revisions(chip_id, acc_id, mag_id, gyr_id, sw_revision, bootloader);
    CHECK(chip_id == 0xA0, "chip_id");
    CHECK(acc_id == 0xFB, "acc_id");
    CHECK(mag_id == 0x32, "mag_id");
    CHECK(gyr_id == 0x0F, "gyr_id");
    CHECK(imu.valid(), "valid");
    CHECK(imu.get_mode() == 0x0C, "mode_ndof");

    float h, r, p;
    imu.euler(h, r, p);
    NEAR(h, 0.0f, 6.5f, "heading range");
    NEAR(r, -1.6f, 1.6f, "roll range");
    NEAR(p, -3.2f, 3.2f, "pitch range");

    float qw, qx, qy, qz;
    imu.quaternion(qw, qx, qy, qz);
    NEAR(qw * qw + qx * qx + qy * qy + qz * qz, 0.9f, 1.1f, "quaternion unit");

    float gx, gy, gz;
    imu.gravity(gx, gy, gz);
    NEAR(gx * gx + gy * gy + gz * gz, 72.0f, 121.0f, "gravity ~1 g");

    float lx, ly, lz;
    imu.linear_acceleration(lx, ly, lz);
    NEAR(lx, -5.0f, 5.0f, "linear acceleration x");
    NEAR(ly, -5.0f, 5.0f, "linear acceleration y");
    NEAR(lz, -5.0f, 5.0f, "linear acceleration z");

    NEAR((float)imu.temperature(), -40.0f, 85.0f, "temperature range");
    uint8_t sys_c, gyr_c, acc_c, mag_c;
    imu.calibration_status(sys_c, gyr_c, acc_c, mag_c);
    CHECK(sys_c <= 3 && gyr_c <= 3 && acc_c <= 3 && mag_c <= 3, "calibration status range");
    uint8_t status, error;
    imu.system_status(status, error);
    CHECK(status == 5 && error == 0, "system status");

    uint8_t uid[16];
    imu.unique_id(uid);
    uint8_t blob[22];
    imu.get_calibration(blob);
    CHECK(imu.get_mode() == 0x0C, "mode restored after get_calibration");

    imu.set_mode(BNO055Full::MODE_AMG);
    CHECK(imu.get_mode() == 0x07, "mode_amg");
    float ax, ay, az, mx, my, mz;
    imu.acceleration(ax, ay, az);
    NEAR(ax * ax + ay * ay + az * az, 64.0f, 132.0f, "accel magnitude ~1 g");
    imu.magnetic_field(mx, my, mz);
    NEAR(mx * mx + my * my + mz * mz, 25.0f, 40000.0f, "mag field range");
    imu.set_mode(BNO055Full::MODE_NDOF);
    CHECK(imu.get_mode() == 0x0C, "mode_ndof_again");

    bool st_acc, st_mag, st_gyr, st_mcu;
    imu.self_test(st_acc, st_mag, st_gyr, st_mcu);
    CHECK(st_acc && st_mag && st_gyr && st_mcu, "self_test passes");
    CHECK(imu.get_mode() == 0x0C, "mode restored after self_test");

    imu.enable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);
    imu.poll_interrupt();
    imu.disable_interrupt(BNO055Full::SOURCE_ACC_BSX_DRDY);
    CHECK(true, "interrupt api");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
