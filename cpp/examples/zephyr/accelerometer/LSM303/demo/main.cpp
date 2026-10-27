#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LSM303.h"

#ifndef LSM303_I2C_NODE
#define LSM303_I2C_NODE DT_NODELABEL(i2c0)
#endif

#define LOG(...) printk(__VA_ARGS__)
static void lsm303_sleep_ms(uint32_t ms) { k_sleep(K_MSEC(ms)); }
static uint32_t lsm303_now_ms() { return (uint32_t)k_uptime_get(); }
static void on_int(uint16_t status) { printk("interrupt status: 0x%04X\n", status); }

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LSM303_I2C_NODE);
    I2CConnectionZephyr accel_connection(dev, 0x19);
    I2CConnectionZephyr mag_connection(dev, 0x1E);
    LSM303Full compass(accel_connection, mag_connection);
    static const char* NAMES[16] = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    compass.set_range(2);                                   // Set accelerometer range, (range_g) -> g
    compass.set_mode(2);                                    // Set accelerometer mode, (mode) -> None
    compass.set_data_rate(50);                              // Set accelerometer data rate, (rate_hz) -> Hz
    compass.set_mag_range(130);                             // Set magnetometer range, (range_ut) -> uT
    compass.set_mag_data_rate(15.0f);                       // Set magnetometer data rate, (rate_hz) -> Hz

    compass.set_click(0, 0x04, 0.8f, 20, 40, 200);          // Configure click detection, (single, double_, threshold_g, time_limit_ms, latency_ms, window_ms) -> None
    compass.set_free_fall(1, 0.35f, 60);                    // Configure free-fall, (generator, threshold_g, duration_ms) -> None
    compass.set_latch(1, true);                             // Latch generator 1, (generator, enabled) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);  // Route source to INT1, (source, pin) -> None
    compass.enable_interrupt(LSM303Full::SOURCE_IA1, 1);    // Route source to INT1, (source, pin) -> None
    compass.set_fifo(LSM303Full::FIFO_STREAM, 16, false);   // Configure FIFO, (mode, watermark, trigger_int2) -> None

    uint32_t start = lsm303_now_ms();
    uint32_t last = start;
    while (lsm303_now_ms() - start < 60000) {
        uint32_t now = lsm303_now_ms();
        if (now - last >= 1000) {
            uint8_t level = compass.fifo_level();           // Read FIFO level, () -> int
            float xb[32], yb[32], zb[32];
            uint8_t n = compass.read_fifo(xb, yb, zb, 32);  // Drain FIFO, (x_buf, y_buf, z_buf, max_samples) -> int
            float ax = 0, ay = 0, az = 0;
            if (n > 0) {
                for (uint8_t i = 0; i < n; i++) { ax += xb[i]; ay += yb[i]; az += zb[i]; }
                ax /= n; ay /= n; az /= n;
            } else {
                compass.read_acceleration(ax, ay, az);      // Read 3-axis acceleration, (x, y, z) -> g, g, g
            }
            float mx, my, mz;
            compass.read_magnetic(mx, my, mz);              // Read 3-axis magnetic field, (x, y, z) -> uT, uT, uT
            float field = sqrtf(mx * mx + my * my + mz * mz);
            float pitch = atan2f(ax, sqrtf(ay * ay + az * az)) * 57.2957795f;
            float roll = atan2f(ay, az) * 57.2957795f;
            float hdg = compass.heading(true);              // Compute compass heading, (tilt_compensate) -> deg
            const char* name = NAMES[(int)((hdg + 11.25f) / 22.5f) % 16];
            float temp = compass.read_temperature_delta();  // Read relative temperature, () -> float C
            LOG("%5.1f deg %s  pitch=%+.1f roll=%+.1f |B|=%.1f uT  T=%+.1f C  fifo=%d\n",
                hdg, name, pitch, roll, field, temp, level);

            uint16_t isr = compass.poll_interrupt();        // Read interrupt status, () -> int
            if (isr & LSM303Full::SOURCE_CLICK) LOG("DOUBLE TAP\n");
            if (isr & LSM303Full::SOURCE_IA1)   LOG("FREE FALL\n");
            last = now;
        }
        lsm303_sleep_ms(10);
    }
    LOG("done\n");
    return 0;
}
