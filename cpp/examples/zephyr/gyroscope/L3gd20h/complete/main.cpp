#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/drivers/i2c.h>
#include <I2CConnectionZephyr.h>
#include <L3gd20h.h>
#include <stdio.h>

#ifndef L3GD20H_I2C_NODE
#define L3GD20H_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef L3GD20H_ADDR
#define L3GD20H_ADDR 0x6A
#endif

int main(void) {
    const struct device* i2c_dev = DEVICE_DT_GET(L3GD20H_I2C_NODE);
    if (!device_is_ready(i2c_dev)) {
        printf("I2C device not ready\n");
        return 0;
    }

    I2CConnectionZephyr conn(i2c_dev, L3GD20H_ADDR);
    L3gd20hFull gyro(conn);

    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    gyro.configure_hp_filter(L3gd20hFull::HPM_NORMAL, 0);  // Configure HPF, (mode 0-3, cutoff 0-15) -> None
    gyro.enable_hp_filter(true);                            // Enable HPF, (enable=true) -> None

    gyro.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);  // Configure FIFO, (mode 0/1/2/3/7, watermark 0-31) -> None
    gyro.enable_fifo(true);                            // Enable FIFO, (enable=true) -> None

    gyro.set_power_mode(L3gd20hFull::POWER_NORMAL);    // Set power mode, (mode='normal'/'sleep'/'power_down') -> None

    int8_t temp = gyro.temperature();                  // Read temperature, () -> int8_t
    printf("Temperature: %d\n", temp);

    while (1) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            printf("x=%.3f y=%.3f z=%.3f rad/s\n", x, y, z);

            int16_t rx, ry, rz;
            gyro.gyro_raw(rx, ry, rz);                 // Read raw, () -> (int16_t, int16_t, int16_t)

            uint8_t level = gyro.fifo_level();         // FIFO level, () -> uint8_t
            if (level > 0) {
                float fx[32], fy[32], fz[32];
                uint8_t n = gyro.read_fifo(fx, fy, fz, level); // Read FIFO, (out_x, out_y, out_z, max) -> uint8_t
                printf("FIFO: %d samples\n", n);
            }
        }
        k_sleep(K_MSEC(10));
    }
    return 0;
}