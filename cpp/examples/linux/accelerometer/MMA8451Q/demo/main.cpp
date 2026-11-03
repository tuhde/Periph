#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cmath>
#include <unistd.h>
#include <time.h>
#include "I2CConnectionLinux.h"
#include "MMA8451Q.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x1C;
    I2CConnectionLinux connection(bus, addr);

    MMA8451QFull accel(connection);                       // Create MMA8451Q driver, (connection)

    // --- Configure ±4 g, 100 Hz, high-resolution oversampling ---
    // ±4 g gives 2048 counts/g, enough headroom for a 1.0 g tap threshold
    // while still covering a free-fall event. High-resolution oversampling
    // lowers the noise floor, which matters because the orientation detector
    // trips on small changes of the gravity vector.
    accel.set_range(4);                                  // Set measurement range, (range_g=2) → g
                                                          // ±4 g, 2048 counts/g
    accel.set_data_rate(100);                            // Set output data rate, (rate_hz=100) → Hz
                                                          // 100 Hz — 10 ms between samples
    accel.set_oversampling(2, 0);                        // Set oversampling mode, (mode=0, sleep_mode=-1) → None
                                                          // mode 2 = high resolution in WAKE, normal in SLEEP

    bool st_ok = false;
    int16_t dx = 0, dy = 0, dz = 0;
    accel.self_test(st_ok, dx, dy, dz);                  // Run self-test, () → bool, int, int, int
                                                          // electrostatic self-test; prints the per-axis deltas
    printf("self-test: %s dx=%d dy=%d dz=%d\n",
           st_ok ? "PASS" : "FAIL", (int)dx, (int)dy, (int)dz);

    accel.set_orientation(true, 100.0f, 45, 4, 4, 1);    // Configure orientation, (enabled, debounce_ms, threshold_deg, hysteresis, z_lock, back_front) → None
                                                          // portrait/landscape at 45°, 100 ms debounce, ±14° hysteresis
    accel.set_pulse(0, 0x04, 0.5f, 0.5f, 1.0f, 40.0f, 100.0f, 300.0f, true, false, true, true);
                                                          // Configure pulse/tap, (single, double, ths_x, ths_y, ths_z, tmlt, latency, window, latch, abort, lpf, hpf) → None
                                                          // double tap on Z, 1.0 g threshold, latched
    accel.set_freefall_motion(false, 7, 0.2f, 60.0f, true, false);
                                                          // Configure freefall/motion, (motion, axes, threshold_g, debounce_ms, latch, debounce_clear) → None
                                                          // freefall below 0.2 g on all axes, 60 ms debounce, latched
    accel.set_auto_sleep(true, 12.5f, 10.0f,
                         MMA8451QFull::SOURCE_PULSE | MMA8451QFull::SOURCE_FF_MT);
                                                          // Configure Auto-SLEEP, (enabled, sleep_rate_hz, timeout_s, wake_sources) → None
                                                          // 12.5 Hz sleep rate, 10 s timeout, wake on tap or freefall

    accel.enable_interrupt(MMA8451QFull::SOURCE_ORIENTATION, 1);
                                                          // Enable interrupt source, (source, pin=1) → None
                                                          // orientation change on INT1
    accel.enable_interrupt(MMA8451QFull::SOURCE_PULSE, 1);  // Enable interrupt source, (source, pin=1) → None
                                                          // tap on INT1
    accel.enable_interrupt(MMA8451QFull::SOURCE_FF_MT, 1);   // Enable interrupt source, (source, pin=1) → None
                                                          // freefall on INT1

    accel.set_fifo(1, 16);                               // Configure the FIFO, (mode=0, watermark=0) → None
                                                          // circular FIFO with a 16-sample watermark

    const int DURATION_MS = 60'000;
    const int POLL_MS = 250;

    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    long elapsed_ms = 0;
    long last_print = 0;

    // --- 60-second orientation / tap / free-fall monitor ---
    // The chip sleeps at 12.5 Hz after 10 s of inactivity and wakes on a tap
    // or a free-fall event. Every 250 ms the acceleration and the current
    // system mode are printed; whenever poll_interrupt() reports an event the
    // matching detail is printed too.
    while (elapsed_ms < DURATION_MS) {
        struct timespec now_ts;
        clock_gettime(CLOCK_MONOTONIC, &now_ts);
        elapsed_ms = (now_ts.tv_sec - start.tv_sec) * 1000
                   + (now_ts.tv_nsec - start.tv_nsec) / 1'000'000;

        if (elapsed_ms - last_print >= POLL_MS) {
            float x, y, z;
            accel.read(x, y, z);                            // Read 3-axis acceleration, (x, y, z) → g, g, g
            uint8_t mode = accel.read_system_mode();        // Read system mode, () → byte
                                                              // SYSMOD bits 1:0: 0 standby, 1 wake, 2 sleep

            printf("%5ld  x=%+.3f  y=%+.3f  z=%+.3f  mode=%s",
                   elapsed_ms / 1000, (double)x, (double)y, (double)z,
                   mode == 0 ? "STANDBY" : (mode == 1 ? "WAKE" : "SLEEP"));

            uint8_t status = accel.poll_interrupt();        // Read interrupt status, () → byte
                                                              // INT_SOURCE; the source flags are cleared by the read
            if (status & MMA8451QFull::SOURCE_ORIENTATION) {
                bool changed = false, lockout = false, back = false;
                uint8_t lapo = 0;
                accel.read_orientation(changed, lockout, lapo, back);
                                                              // Read orientation, (changed, lockout, lapo, back) → bool, bool, byte, bool
                static const char* names[4] = { "portrait up", "portrait down",
                                                "landscape right", "landscape left" };
                printf("  ORIENTATION=%s%s%s", names[lapo & 0x03],
                       back ? " (back)" : "", lockout ? " (lockout)" : "");
            }
            if (status & MMA8451QFull::SOURCE_PULSE) {
                uint8_t pulse = accel.read_pulse_source();  // Read pulse source, () → byte
                                                              // PULSE_SRC; reading it clears the interrupt
                printf("  DOUBLE TAP src=0x%02X", pulse);
            }
            if (status & MMA8451QFull::SOURCE_FF_MT) {
                uint8_t motion = accel.read_motion_source();  // Read motion source, () → byte
                                                              // FF_MT_SRC; reading it clears the latched flags
                printf("  FREE FALL src=0x%02X", motion);
            }

            uint8_t level = accel.fifo_level();             // Read FIFO level, () → byte
                                                              // number of samples buffered in the FIFO
            printf("  fifo=%u\n", level);
            last_print = elapsed_ms;
        }

        usleep(10'000);
    }

    // --- Drain the FIFO and print the mean acceleration ---
    float samples[3 * MMA8451QFull::FIFO_CAPACITY];
    uint8_t got = accel.read_fifo(samples, MMA8451QFull::FIFO_CAPACITY);
                                                              // Drain the FIFO, (out, max_in) → byte
                                                              // up to 32 samples, three floats (x, y, z in g) each
    float mx = 0.0f, my = 0.0f, mz = 0.0f;
    for (uint8_t i = 0; i < got; i++) {
        mx += samples[i * 3 + 0];
        my += samples[i * 3 + 1];
        mz += samples[i * 3 + 2];
    }
    if (got > 0) {
        printf("fifo: %u samples, mean=(%+.3f, %+.3f, %+.3f) g\n", got,
               (double)(mx / got), (double)(my / got), (double)(mz / got));
    } else {
        printf("fifo: empty\n");
    }
    printf("done\n");
    return 0;
}
