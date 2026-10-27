#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <math.h>
#include "I2CConnectionZephyr.h"
#include "LSM303.h"

#define I2C_NODE DT_NODELABEL(i2c0)

static const char* const COMPASS_16[16] = {
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
};

int main(void) {
    const struct device *i2c_dev = DEVICE_DT_GET(I2C_NODE);
    I2CConnectionZephyr accel_connection(i2c_dev, 0x19);
    I2CConnectionZephyr mag_connection(i2c_dev, 0x1E);
    LSM303Full sensor(accel_connection, mag_connection);    // Create LSM303 Full driver, (accel, mag connections)

    // --- Tilt-compensated compass: ±2 g / 50 Hz, ±130 µT / 15 Hz ---
    // High-resolution and slow ODRs keep noise out of the tilt angles.
    // The magnetometer runs 15 Hz, fast enough for a 1 Hz compass update;
    // the FIFO smooths the acceleration going into pitch / roll.
    sensor.set_range(2);                                     // Set accelerometer range, (range_g=2) → None
    sensor.set_data_rate(50);                                // Set accelerometer data rate, (rate_hz=50) → None
    sensor.set_mode(2);                                      // Set operating mode, (mode=2 high-resolution) → None
    sensor.set_mag_range(130);                               // Set magnetometer range, (range_ut=130) → None
    sensor.set_mag_data_rate(15);                            // Set magnetometer data rate, (rate_hz=15) → None

    // --- Tap (double-tap) and free-fall detection on INT1 ---
    // Double-tap on Z (0.8 g, 20 ms limit) and a 60 ms free-fall window,
    // both latched so the 1 Hz sampling loop cannot miss a short event.
    sensor.set_click(0, 4, 0.8, 20, 40, 200);                // Configure click detection, (single=0, double=4, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → None
                                                             // double-tap on Z (axis mask Z=4)
    sensor.set_free_fall(1, 0.35, 60);                       // Configure free-fall, (generator=1, threshold_g=0.35, duration_ms=60) → None
    sensor.set_latch(1, true);                               // Set latch, (generator=1, enabled=true) → None
                                                             // latch free-fall until read_interrupt_source(1)
    sensor.enable_interrupt(LSM303Full::SOURCE_CLICK, 1);    // Route interrupt, (source=CLICK, pin=1) → None
    sensor.enable_interrupt(LSM303Full::SOURCE_IA1, 1);      // Route interrupt, (source=IA1, pin=1) → None

    // --- FIFO in stream mode with a 16-sample watermark ---
    // Stream mode keeps the newest 16 samples; the drain below averages them.
    sensor.set_fifo(2, 16);                                  // Configure FIFO, (mode=2 stream, watermark=16, trigger_int2=false) → None

    int64_t start = k_uptime_get();
    int64_t last_second = -1;

    // --- 60-second compass run ---
    // Hold the board flat; the heading is the tilt-compensated magnetic
    // north angle. Every second the FIFO is drained and averaged, the
    // magnetic field, heading, pitch / roll, |B| and relative temperature
    // are printed. A double-tap on the board prints DOUBLE TAP; dropping
    // it prints FREE FALL (read_interrupt_source clears the latch).
    while (k_uptime_get() - start < 60000) {
        int64_t second = (k_uptime_get() - start) / 1000;
        if (second == last_second) {
            k_sleep(K_MSEC(10));
            continue;
        }
        last_second = second;

        // --- Drain and average the FIFO ---
        float fx[32], fy[32], fz[32];
        uint8_t n = sensor.read_fifo(fx, fy, fz, 32);        // Drain FIFO, (x, y, z, max_samples=32) → floats³, count
                                                             // pops all unread sample sets (up to 32)
        float axv = 0, ayv = 0, azv = 0;
        for (uint8_t i = 0; i < n; i++) {
            axv += fx[i]; ayv += fy[i]; azv += fz[i];
        }
        if (n > 0) { axv /= n; ayv /= n; azv /= n; }

        float mx, my, mz;
        sensor.read_magnetic(mx, my, mz);                    // Read 3-axis magnetic field, (x, y, z) → µT, µT, µT
        float hdg = sensor.heading(true);                    // Compute compass heading, (tilt_compensate=true) → degrees
        float temp = sensor.read_temperature_delta();        // Read relative temperature, () → °C

        float pitch = atan2f(-axv, sqrtf(ayv * ayv + azv * azv)) * 180.0f / 3.14159265f;
        float roll  = atan2f(ayv, azv) * 180.0f / 3.14159265f;
        float bmag  = sqrtf(mx * mx + my * my + mz * mz);
        int dir = ((int)((hdg + 11.25f) / 22.5f)) % 16;
        if (dir < 0) dir += 16;

        printk("%4llds  hdg=%5.1f° %-3s  pitch=%+.1f° roll=%+.1f°  |B|=%.1f uT  T=%+.1f°C  fifo=%u\n",
               (long long)second, (double)hdg, COMPASS_16[dir],
               (double)pitch, (double)roll, (double)bmag, (double)temp, n);

        uint16_t status = sensor.poll_interrupt();           // Poll all sources, () → status mask
                                                             // source reads clear the latched flags
        if (status & LSM303Full::SOURCE_IA1) {
            (void)sensor.read_interrupt_source(1);           // Read interrupt source, (generator=1) → source byte
                                                             // clears the latched free-fall interrupt
            printk("  FREE FALL\n");
        }
        if (status & LSM303Full::SOURCE_CLICK) {
            (void)sensor.read_click_source();                // Read click source, () → source byte
                                                             // clears the latched double-tap interrupt
            printk("  DOUBLE TAP\n");
        }
    }
    sensor.set_fifo(0);                                      // Configure FIFO, (mode=0 bypass) → None
    printk("done\n");
    return 0;
}
