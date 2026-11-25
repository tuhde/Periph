#include <math.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "ICM20948.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x68;
    I2CConnectionLinux connection(bus, addr);

    ICM20948Full imu(connection);     // Create ICM20948 driver, (connection) → void

    // --- Configure for motion and heading monitoring ---
    // ±4g / ±500dps give headroom for sharper motion; DLPFCFG 3 with a divider of
    // 10 keeps a ~100 Hz sample rate with a ~50 Hz noise bandwidth. The magnetometer
    // runs through the chip's own I²C master, so one connection is enough.
    imu.configure_accel(1);                       // Configure accel range, (full_scale=0) → void
    imu.configure_gyro(1);                        // Configure gyro range, (full_scale=0) → void
    imu.configure_filters(3, 3, true, true);      // Configure low-pass filters, (gyro_dlpf=3, accel_dlpf=3, gyro_filter=true, accel_filter=true) → void
    imu.configure_sample_rate(10, 10);            // Configure sample rate, (gyro_div=10, accel_div=10) → void
    imu.enable_mag();                             // Enable magnetometer, () → bool

    float temp0 = imu.temperature();              // Read die temperature, () → float °C
    printf("ICM-20948 die temperature: %.1f C\n", temp0);

    // --- Arm wake-on-motion and route it to the interrupt status ---
    // A 200 mg threshold flags a bump; poll_interrupt() reports it as
    // SOURCE_WAKE_ON_MOTION without needing the INT pin wired.
    imu.set_wake_on_motion(200.0f);               // Arm wake-on-motion, (threshold_mg, enabled=true) → void
    imu.enable_interrupt(ICM20948Full::SOURCE_WAKE_ON_MOTION); // Enable interrupt source, (source) → void

    // --- Stream accelerometer + gyro into the FIFO ---
    // Stream mode overwrites the oldest data; once per second the FIFO is
    // drained and counted in 12-byte frames (accel X, Y, Z, gyro X, Y, Z).
    imu.enable_fifo(true, true, false);           // Enable FIFO sources, (accel=true, gyro=true, temp=false) → void
    imu.set_fifo_mode(false);                     // Select FIFO mode, (snapshot=false) → void

    printf("roll     pitch    heading  |gyro|\n");

    for (int iteration = 0; iteration < 600; iteration++) {            // 600 x 100 ms = 60 s
        float a[3], g[3], t;
        imu.read_all(a, g, t);                        // Read one coherent sample, (float[3], float[3], float&) → void m/s², rad/s, °C
        float mx = 0, my = 0, mz = 0;
        imu.mag(mx, my, mz);                          // Read 3-axis magnetic field, (float&, float&, float&) → bool µT

        // --- Compute roll, pitch and a tilt-compensated heading ---
        // The magnetometer frame is rotated 180° about X relative to the inertial
        // frame, so (mx, -my, -mz) maps it into the accel frame before the tilt
        // compensation rotates it into the horizontal plane.
        const float RAD2DEG = 57.2957795f;
        float roll = atan2f(a[1], a[2]);
        float pitch = atan2f(-a[0], sqrtf(a[1] * a[1] + a[2] * a[2]));
        float bx = mx, by = -my, bz = -mz;
        float xh = bx * cosf(pitch) + bz * sinf(pitch);
        float yh = bx * sinf(roll) * sinf(pitch) + by * cosf(roll) - bz * sinf(roll) * cosf(pitch);
        float heading = atan2f(-yh, xh) * RAD2DEG;
        if (heading < 0.0f) heading += 360.0f;
        float gyro_mag = sqrtf(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);

        printf("%.1f      %.1f      %.1f      %.3f\n", roll * RAD2DEG, pitch * RAD2DEG, heading, gyro_mag);

        uint8_t status = imu.poll_interrupt();        // Read interrupt status, () → uint8_t
        if (status & ICM20948Full::SOURCE_WAKE_ON_MOTION) {
            printf("MOTION\n");
        }

        if (iteration % 10 == 9) {
            uint8_t fifo[240];
            uint16_t n = imu.fifo_count();            // Read FIFO byte count, () → uint16_t
            uint16_t got = imu.read_fifo(fifo, sizeof(fifo)); // Read FIFO data, (uint8_t*, uint16_t) → uint16_t
            printf("FIFO: %d bytes, %d frames\n", (int)(got), (int)(got / 12));
            (void)n;
        }
        usleep(100000);
    }
    return 0;
}
