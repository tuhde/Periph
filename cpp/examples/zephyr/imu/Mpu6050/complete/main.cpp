#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "Mpu6050.h"

#define I2C_NODE DT_NODELABEL(i2c0)
#define MPU6050_ADDR 0x68

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CTransportZephyr transport(i2c_dev, MPU6050_ADDR);
    MPU6050Full imu(transport);                          // Create MPU6050 driver, (transport, addr=0x68) → None

    imu.configure_gyro(1);                                // Configure gyro range, (full_scale=0) → None
                                                         // sets FS_SEL: 0=±250, 1=±500, 2=±1000, 3=±2000 dps
    imu.configure_accel(1);                               // Configure accel range, (full_scale=0) → None
                                                         // sets AFS_SEL: 0=±2g, 1=±4g, 2=±8g, 3=±16g
    imu.configure_dlpf(3);                                // Configure DLPF bandwidth, (dlpf=3) → None
                                                         // sets DLPF_CFG: 0=260Hz … 6=5Hz (gyro/accel BW)
    imu.configure_sample_rate(4);                         // Configure sample rate, (divider=4) → None
                                                         // sets SMPLRT_DIV: output rate = 1kHz / (1 + divider)

    float ax, ay, az, gx, gy, gz;
    imu.accel(ax, ay, az);                               // Read 3-axis acceleration, (x, y, z) → m/s²
    imu.gyro(gx, gy, gz);                                // Read 3-axis angular rate, (x, y, z) → rad/s
    printk("accel: %.2f %.2f %.2f\n", (double)ax, (double)ay, (double)az);
    printk("gyro:  %.2f %.2f %.2f\n", (double)gx, (double)gy, (double)gz);
    printk("temp:  %.1f C\n",        (double)imu.temperature()); // Read die temperature, () → °C

    int16_t rax, ray, raz, rgx, rgy, rgz;
    imu.accel_raw(rax, ray, raz);                         // Read raw accel values, (x, y, z) → int16_t
    imu.gyro_raw(rgx, rgy, rgz);                          // Read raw gyro values, (x, y, z) → int16_t
    printk("raw accel: %d %d %d\n", rax, ray, raz);
    printk("raw gyro:  %d %d %d\n", rgx, rgy, rgz);

    printk("data_ready: %d\n", imu.data_ready());         // Check data ready flag, () → bool

    imu.set_sleep(true);                                  // Enter sleep mode, (sleep=true) → None
    k_sleep(K_MSEC(10));
    imu.set_sleep(false);                                 // Wake from sleep, (sleep=true) → None
    k_sleep(K_MSEC(50));

    imu.set_standby(true, false, false, false, false, false); // Set axes standby, (xa, ya, za, xg, yg, zg) → None
    imu.set_standby();                                    // Clear all standby, (xa=false, ...) → None

    imu.reset_fifo();                                     // Reset FIFO buffer, () → None
    imu.enable_fifo(true, true, false);                   // Enable FIFO sources, (gyro=true, accel=true, temp=false) → None
    k_sleep(K_MSEC(50));
    printk("fifo_count: %d\n", imu.fifo_count());         // Read FIFO byte count, () → uint16_t
    uint8_t buf[128];
    uint16_t n = imu.read_fifo(buf, sizeof(buf));         // Read FIFO data, (buf, len) → uint16_t
    printk("fifo read: %d bytes\n", n);
    imu.reset_fifo();                                     // Reset FIFO buffer, () → None

    return 0;
}
