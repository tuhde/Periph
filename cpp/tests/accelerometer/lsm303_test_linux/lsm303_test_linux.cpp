#ifndef TEST_I2C_BUS
#define TEST_I2C_BUS 1
#endif

#include <stdio.h>
#include <stdint.h>
#include <math.h>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "LSM303.h"

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        printf("PASS %s\n", label);
        passed++;
    } else {
        printf("FAIL %s\n", label);
        failed++;
    }
}

static bool finite(float v) { return v == v && v > -1e30f && v < 1e30f; }

int main() {
    // Accelerometer at 0x19 and magnetometer at 0x1E, both on the same bus.
    I2CConnectionLinux accelConn(TEST_I2C_BUS, 0x19);
    I2CConnectionLinux magConn(TEST_I2C_BUS, 0x1E);
    LSM303Full chip(accelConn, magConn);

    check_true("identity valid", chip.valid());

    uint8_t id[3] = {0, 0, 0};
    chip.readMagIdentity(id);
    check_true("mag identity 0x48 0x34 0x33", id[0] == 0x48 && id[1] == 0x34 && id[2] == 0x33);

    // Wait for the first accelerometer sample (7/ODR after init) and one magnetometer period.
    usleep(200000);

    float ax, ay, az;
    chip.readAcceleration(ax, ay, az);
    check_true("accel_x finite", finite(ax));
    check_true("accel_y finite", finite(ay));
    check_true("accel_z finite", finite(az));
    float amag = sqrtf(ax * ax + ay * ay + az * az);
    check_true("accel magnitude 0.5..1.5 g (stationary)", amag > 0.5f && amag < 1.5f);

    float mx, my, mz;
    chip.readMagnetic(mx, my, mz);
    check_true("mag_x finite", finite(mx));
    check_true("mag_y finite", finite(my));
    check_true("mag_z finite", finite(mz));
    float mmag = sqrtf(mx * mx + my * my + mz * mz);
    check_true("mag magnitude below 1000 uT", mmag < 1000.0f);

    float h = chip.heading(false);
    check_true("heading flat in 0..360", h >= 0.0f && h < 360.0f);
    float ht = chip.heading(true);
    check_true("heading tilt in 0..360", ht >= 0.0f && ht < 360.0f);

    float t = chip.readTemperatureDelta();
    check_true("temperature delta finite", finite(t));
    check_true("temperature delta within ±60 C", t > -60.0f && t < 60.0f);

    int16_t rx, ry, rz;
    chip.readAccelerationRaw(rx, ry, rz);
    check_true("accel raw in range", rx >= -2048 && rx <= 2047 && ry >= -2048 && ry <= 2047);

    // Range and rate changes round-trip.
    check_true("set_range 4", chip.setRange(4));
    check_true("set_range 2", chip.setRange(2));
    check_true("set_range 3 rejected", !chip.setRange(3));
    check_true("set_data_rate 50", chip.setDataRate(50));
    check_true("set_data_rate 100", chip.setDataRate(100));
    check_true("set_mag_range 810", chip.setMagRange(810));
    check_true("set_mag_range 130", chip.setMagRange(130));
    check_true("set_mag_data_rate 15", chip.setMagDataRate(15.0f));

    // FIFO bypass drain returns no more than requested.
    float xs[32], ys[32], zs[32];
    uint8_t n = chip.readFifo(xs, ys, zs, 32);
    check_true("fifo drain bounded", n <= 32);

    // Interrupt routing and polling (no INT line is wired in this test).
    check_true("enable DATA_READY on INT1", chip.enableInterrupt(LSM303Source::DATA_READY, 1));
    check_true("disable DATA_READY on INT1", chip.disableInterrupt(LSM303Source::DATA_READY, 1));
    check_true("mag DRDY not routable", !chip.enableInterrupt(LSM303Source::MAG_DATA_READY, 1));
    uint8_t status = chip.pollInterrupt();
    // No accelerometer source is routed now, so only the magnetometer DRDY bit (0x80) may be set.
    check_true("poll status only carries mag DRDY", status == 0x00 || status == 0x80);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
