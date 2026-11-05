#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdio.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "ICM20948.h"

#ifndef ICM20948_I2C_NODE
#define ICM20948_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef ICM20948_ADDR
#define ICM20948_ADDR 0x68
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ICM20948_I2C_NODE);
    I2CConnectionZephyr connection(dev, ICM20948_ADDR);
    ICM20948Full imu(connection);

    // WHO_AM_I is already verified during construction; if it mismatched,
    // the constructor would have aborted before reaching here.

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);
    check_true(ax > -200.0f && ax < 200.0f, "accel_x finite");
    check_true(ay > -200.0f && ay < 200.0f, "accel_y finite");
    check_true(az > -200.0f && az < 200.0f, "accel_z finite");
    imu.gyro(gx, gy, gz);
    check_true(gx > -100.0f && gx < 100.0f, "gyro_x finite");
    check_true(gy > -100.0f && gy < 100.0f, "gyro_y finite");
    check_true(gz > -100.0f && gz < 100.0f, "gyro_z finite");
    check_true(sqrtf(ax * ax + ay * ay + az * az) > 5.0f && sqrtf(ax * ax + ay * ay + az * az) < 15.0f, "gravity magnitude");

    float t = imu.temperature();
    check_true(t > -40.0f && t < 85.0f, "temperature range");

    float a[3], g[3], tc;
    imu.read_all(a, g, tc);
    check_true(tc > -40.0f && tc < 85.0f, "read_all temp range");

    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);
    imu.gyro_raw(rgx, rgy, rgz);
    check_true(sqrtf((float)rax * rax + (float)ray * ray + (float)raz * raz) > 8000.0f && sqrtf((float)rax * rax + (float)ray * ray + (float)raz * raz) < 24000.0f, "accel_raw gravity");
    (void)rgx; (void)rgy; (void)rgz;

    imu.configure_gyro(1);
    imu.configure_accel(1);
    float ax2, ay2, az2;
    imu.accel(ax2, ay2, az2);
    check_true(ax2 > -200.0f && ax2 < 200.0f, "accel after reconfig");

    imu.configure_filters(4, 4, true, true);
    imu.configure_sample_rate(9, 9);
    int16_t ox, oy, oz;
    imu.get_accel_offset(ox, oy, oz);
    check_true((ox + oy + oz) != 0x7FFF, "accel offset readable");

    check_true(imu.enable_mag(), "enable_mag");
    k_sleep(K_MSEC(150));
    float mx, my, mz;
    check_true(imu.mag(mx, my, mz), "mag read");
    check_true(sqrtf(mx * mx + my * my + mz * mz) > 5.0f && sqrtf(mx * mx + my * my + mz * mz) < 150.0f, "mag magnitude");
    uint8_t who = 0;
    check_true(imu.aux_read_byte(0x0C, 0x01, who) && who == 0x09, "aux_read WIA2");

    imu.set_sleep(true);
    k_sleep(K_MSEC(10));
    imu.set_sleep(false);
    k_sleep(K_MSEC(50));
    float ax3, ay3, az3;
    imu.accel(ax3, ay3, az3);
    check_true(ax3 > -200.0f && ax3 < 200.0f, "accel after wake");

    imu.enable_fifo(true, true, false);
    k_sleep(K_MSEC(50));
    uint16_t count = imu.fifo_count();
    check_true(count > 0, "fifo_count > 0");
    uint8_t data[256];
    uint16_t read = imu.read_fifo(data, 256);
    check_true(read > 0, "read_fifo returns bytes");
    imu.reset_fifo();

    imu.set_wake_on_motion(200.0f);
    uint8_t status = imu.poll_interrupt();
    check_true(status <= 0x1F, "poll_interrupt callable");

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}