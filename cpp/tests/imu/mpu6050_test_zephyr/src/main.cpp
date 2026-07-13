#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "Mpu6050.h"

#ifndef MPU6050_I2C_NODE
#define MPU6050_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef MPU6050_ADDR
#define MPU6050_ADDR 0x68
#endif

static int passed = 0;
static int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

static void check_near(float val, float lo, float hi, const char *label) {
    if (val >= lo && val <= hi) { printk("PASS %s\n", label); passed++; }
    else { printk("FAIL %s: %.4f not in [%.4f, %.4f]\n",
                  label, (double)val, (double)lo, (double)hi); failed++; }
}

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(MPU6050_I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MPU6050_ADDR);
    MPU6050Full imu(transport);

    check_true(imu.who_am_i() == 0x68, "who_am_i");

    float ax, ay, az;
    imu.accel(ax, ay, az);
    check_near(ax, -200.0f, 200.0f, "accel_x finite");
    check_near(ay, -200.0f, 200.0f, "accel_y finite");
    check_near(az, -200.0f, 200.0f, "accel_z finite");

    float gx, gy, gz;
    imu.gyro(gx, gy, gz);
    check_near(gx, -100.0f, 100.0f, "gyro_x finite");
    check_near(gy, -100.0f, 100.0f, "gyro_y finite");
    check_near(gz, -100.0f, 100.0f, "gyro_z finite");

    check_near(imu.temperature(), -40.0f, 85.0f, "temperature range");

    int16_t rax, ray, raz;
    imu.accel_raw(rax, ray, raz);
    check_true(rax >= -32768 && rax <= 32767, "accel_raw_x range");

    imu.configure_gyro(1);
    imu.configure_accel(1);
    imu.accel(ax, ay, az);
    check_near(ax, -200.0f, 200.0f, "accel after reconfig");

    imu.set_sleep(true);
    k_sleep(K_MSEC(10));
    imu.set_sleep(false);
    k_sleep(K_MSEC(50));
    imu.accel(ax, ay, az);
    check_near(ax, -200.0f, 200.0f, "accel after wake");

    imu.reset_fifo();
    imu.enable_fifo(true, true, false);
    k_sleep(K_MSEC(50));
    uint16_t count = imu.fifo_count();
    check_true(count > 0, "fifo_count > 0");
    uint8_t buf[256];
    uint16_t n = imu.read_fifo(buf, sizeof(buf));
    check_true(n == count, "read_fifo matches count");

    imu.reset_fifo();

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
