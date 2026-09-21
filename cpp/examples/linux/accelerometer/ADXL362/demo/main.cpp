#include <cstdio>
#include <cmath>
#include <chrono>
#include <thread>
#include <unistd.h>
#include "SPIConnectionLinux.h"
#include "ADXL362.h"

#ifndef TEST_SPI_BUS
#define TEST_SPI_BUS 0
#endif
#ifndef TEST_SPI_DEVICE
#define TEST_SPI_DEVICE 0
#endif

int main() {
    SPIConnectionLinux connection(TEST_SPI_BUS, TEST_SPI_DEVICE, 0, 8000000);    // Create SPI connection, (bus=0, device=0, mode=0, max_speed_hz=8e6) → SPIConnectionLinux
    ADXL362Full accel(connection);                                                  // Create ADXL362 Full driver, (connection) → ADXL362Full

    // --- Configure referenced activity/inactivity thresholds ---
    // 0.25 g activity threshold and 0.15 g inactivity threshold (both
    // relative to orientation at engagement) — picked-up or tapped motion
    // easily exceeds 0.25 g, while a stationary board settles below 0.15 g.
    accel.set_activity_threshold(0.25f, true);                                       // Set activity threshold, (threshold_g=0.25, referenced=true) → None
    accel.set_inactivity_threshold(0.15f, true);                                     // Set inactivity threshold, (threshold_g=0.15, referenced=true) → None
    accel.set_inactivity_time(30);                                                   // Set inactivity time, (samples=30) → None

    // --- Engage linked/loop mode and enable both detectors ---
    accel.enable_activity_detection(true);                                           // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                                         // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                            // Set link/loop mode, (mode=LOOP=3) → None

    // --- Map AWAKE to INT2 and enter wake-up mode ---
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);                        // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_wakeup_mode(true);                                                     // Enter wake-up mode, (enabled=true) → None

    // --- Poll AWAKE for 60 s and count asleep<->awake transitions ---
    printf("Watching for motion. Pick up or tap the board to wake; "
           "let it settle to sleep.\n");
    int last_awake = -1;
    int transitions = 0;
    auto start = std::chrono::steady_clock::now();
    while (true) {
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::seconds>(now - start).count() >= 60) break;

        bool now_awake = accel.awake();                                             // Read AWAKE bit, () → bool
        if (last_awake == -1 || (int)now_awake != last_awake) {
            double elapsed = std::chrono::duration<double>(now - start).count();
            printf("%6.2fs  %s\n", elapsed, now_awake ? "AWAKE" : "asleep");        // Print timestamped state, () → None
            transitions++;
            last_awake = (int)now_awake;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(200));                 // Sleep 200 ms between polls, () → None
    }

    printf("Total transitions observed: %d\n", transitions);                        // Print final count, () → None
    printf("Note: during 'asleep' periods the ADXL362 draws ~270 nA — "
           "roughly two orders of magnitude below the ~1.8 uA of the "
           "continuous 100 Hz measurement mode used by the Minimal "
           "read() example.\n");
    return 0;
}