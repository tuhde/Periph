#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LSM303.h"

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

static bool finite(float v) { return v == v && v > -1e30f && v < 1e30f; }

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    stdio_init_all();
    sleep_ms(2000);  // let USB CDC enumerate

    // Accelerometer at 0x19 and magnetometer at 0x1E, both on i2c0.
    I2CConnectionPicoSDK accelConn(i2c0, 0x19);
    I2CConnectionPicoSDK magConn(i2c0, 0x1E);
    LSM303Full chip(accelConn, magConn);

    check_true(chip.valid(), "identity valid");

    uint8_t id[3] = {0, 0, 0};
    chip.readMagIdentity(id);
    check_true(id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33, "mag identity 0x48 0x34 0x33");

    // Wait for the first accelerometer sample (7/ODR after init) and one magnetometer period.
    sleep_ms(200);

    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);
    check_true(finite(ax), "accel_x finite");
    check_true(finite(ay), "accel_y finite");
    check_true(finite(az), "accel_z finite");
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    check_true(amag > 0.5f && amag < 1.5f, "accel magnitude 0.5..1.5 g (stationary)");

    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);
    check_true(finite(mx), "mag_x finite");
    check_true(finite(my), "mag_y finite");
    check_true(finite(mz), "mag_z finite");
    float mmag = sqrtf(mx * mx + my * my + mz * mz);
    check_true(mmag < 1000.0f, "mag magnitude below 1000 uT");

    float h = chip.heading(false);
    check_true(h >= 0.0f && h < 360.0f, "heading flat in 0..360");
    float ht = chip.heading(true);
    check_true(ht >= 0.0f && ht < 360.0f, "heading tilt in 0..360");

    float t = chip.readTemperatureDelta();
    check_true(finite(t), "temperature delta finite");
    check_true(t > -60.0f && t < 60.0f, "temperature delta within ±60 C");

    int16_t rx, ry, rz;
    chip.readAccelerationRaw(rx, ry, rz);
    check_true(rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047, "accel raw in range");

    // Range and rate changes round-trip.
    check_true(chip.setRange(4), "set_range 4");
    check_true(chip.setRange(2), "set_range 2");
    check_true(!chip.setRange(3), "set_range 3 rejected");
    check_true(chip.setDataRate(50), "set_data_rate 50");
    check_true(chip.setDataRate(100), "set_data_rate 100");
    check_true(chip.setMagRange(810), "set_mag_range 810");
    check_true(chip.setMagRange(130), "set_mag_range 130");
    check_true(chip.setMagDataRate(15.0f), "set_mag_data_rate 15");

    // FIFO bypass drain returns no more than requested.
    float xs[32], ys[32], zs[32];
    uint8_t n = chip.readFifo(xs, ys, zs, 32);
    check_true(n <= 32, "fifo drain bounded");

    // Interrupt routing and polling (no INT line is wired in this test).
    check_true(chip.enableInterrupt(LSM303Source::DATA_READY, 1), "enable DATA_READY on INT1");
    check_true(chip.disableInterrupt(LSM303Source::DATA_READY, 1), "disable DATA_READY on INT1");
    check_true(!chip.enableInterrupt(LSM303Source::MAG_DATA_READY, 1), "mag DRDY not routable");
    uint8_t status = chip.pollInterrupt();
    // No accelerometer source is routed now, so only the magnetometer DRDY bit (0x80) may be set.
    check_true(status == 0x00 || status == 0x80, "poll status only carries mag DRDY");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
