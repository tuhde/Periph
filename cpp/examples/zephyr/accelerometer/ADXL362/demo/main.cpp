#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "SPIConnectionZephyr.h"
#include "ADXL362.h"

#ifndef ADXL362_SPI_NODE
#define ADXL362_SPI_NODE DT_NODELABEL(spi0)
#endif
#ifndef ADXL362_CS_GPIOS
#define ADXL362_CS_GPIOS DT_PROP(ADXL362_SPI_NODE, cs_gpios)
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(ADXL362_SPI_NODE);
    struct spi_config cfg = {
        .frequency = 8000000,
        .operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER,
        .slave     = 0,
        .cs        = { .gpio = ADXL362_CS_GPIOS, .delay = 0 },
    };
    SPIConnectionZephyr connection(dev, cfg);                              // Create SPI connection, (dev, cfg) → SPIConnectionZephyr
    ADXL362Full accel(connection);                                           // Create ADXL362 Full driver, (connection) → ADXL362Full

    // --- Configure referenced activity/inactivity thresholds ---
    accel.set_activity_threshold(0.25f, true);                               // Set activity threshold, (threshold_g=0.25, referenced=true) → None
    accel.set_inactivity_threshold(0.15f, true);                             // Set inactivity threshold, (threshold_g=0.15, referenced=true) → None
    accel.set_inactivity_time(30);                                           // Set inactivity time, (samples=30) → None

    // --- Engage linked/loop mode and enable both detectors ---
    accel.enable_activity_detection(true);                                   // Enable activity detection, (enabled=true) → None
    accel.enable_inactivity_detection(true);                                 // Enable inactivity detection, (enabled=true) → None
    accel.set_link_loop_mode(ADXL362Full::LINKLOOP_LOOP);                    // Set link/loop mode, (mode=LOOP=3) → None

    // --- Map AWAKE to INT2 and enter wake-up mode ---
    accel.set_interrupt(2, ADXL362Full::SOURCE_AWAKE, true);                 // Map AWAKE to INT2, (pin=2, source=AWAKE=6, enabled=true) → None
    accel.set_wakeup_mode(true);                                             // Enter wake-up mode, (enabled=true) → None

    printk("Watching for motion. Pick up or tap the board to wake; "
           "let it settle to sleep.\n");

    int last_awake = -1;
    int transitions = 0;
    int64_t start_ms = k_uptime_get();
    while (k_uptime_get() - start_ms < 60000) {                              // Loop until 60 s elapsed, () → bool
        bool now_awake = accel.awake();                                      // Read AWAKE bit, () → bool
        if (last_awake == -1 || (int)now_awake != last_awake) {
            int64_t now_ms = k_uptime_get() - start_ms;
            printk("%lld ms  %s\n", now_ms, now_awake ? "AWAKE" : "asleep"); // Print timestamped state, () → None
            transitions++;
            last_awake = (int)now_awake;
        }
        k_msleep(200);                                                       // Sleep 200 ms between polls, () → None
    }

    printk("Total transitions observed: %d\n", transitions);                 // Print final count, () → None
    return 0;
}