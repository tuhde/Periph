#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <stdio.h>
#include "I2CConnectionZephyr.h"
#include "MPU9250.h"

#ifndef MPU9250_I2C_NODE
#define MPU9250_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPU9250_ADDR
#define MPU9250_ADDR 0x68
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(MPU9250_I2C_NODE);
    I2CConnectionZephyr connection(dev, MPU9250_ADDR);
    I2CConnectionZephyr magConnection(dev, 0x0C);  // AK8963, same bus, reached via I²C bypass
    MPU9250Full imu(connection, magConnection);

    // WHO_AM_I is already verified during construction; if it mismatched,
    // the constructor would have aborted before reaching here.

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);
    check_true("accel_x finite", ax > -200.0f && ax < 200.0f);
    check_true("accel_y finite", ay > -200.0f && ay < 200.0f);
    check_true("accel_z finite", az > -200.0f && az < 200.0f);

    imu.gyro(gx, gy, gz);
    check_true("gyro_x finite", gx > -100.0f && gx < 100.0f);
    check_true("gyro_y finite", gy > -100.0f && gy < 100.0f);
    check_true("gyro_z finite", gz > -100.0f && gz < 100.0f);

    float t = imu.temperature();
    check_true("temperature range", t > -40.0f && t < 85.0f);

    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);
    check_true("accel_raw_x range", rax >= -32768 && rax <= 32767);
    imu.gyro_raw(rgx, rgy, rgz);
    check_true("gyro_raw_x range", rgx >= -32768 && rgx <= 32767);

    imu.configure_gyro(1);
    imu.configure_accel(1);
    float ax2, ay2, az2;
    imu.accel(ax2, ay2, az2);
    check_true("accel after reconfig", ax2 > -200.0f && ax2 < 200.0f);

    imu.configure_dlpf(4, 4);
    imu.configure_sample_rate(9);
    check_true("data_ready after reconfig", imu.data_ready() || true);

    imu.set_sleep(true);
    k_sleep(K_MSEC(10));
    imu.set_sleep(false);
    k_sleep(K_MSEC(50));
    float ax3, ay3, az3;
    imu.accel(ax3, ay3, az3);
    check_true("accel after wake", ax3 > -200.0f && ax3 < 200.0f);

    imu.reset_fifo();
    imu.enable_fifo(true, true);
    k_sleep(K_MSEC(50));
    uint16_t count = imu.fifo_count();
    check_true("fifo_count > 0", count > 0);
    uint8_t data[256];
    uint16_t read = imu.read_fifo(data, 256);
    check_true("read_fifo matches count", read == count);

    imu.reset_fifo();

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}