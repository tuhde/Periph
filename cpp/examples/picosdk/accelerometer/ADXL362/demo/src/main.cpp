#include <stdio.h>
#include "pico/stdlib.h"
#include <hardware/spi.h>
#include "SPIConnectionPicoSDK.h"
#include "ADXL362.h"

static const uint MOSI_PIN = 19;
static const uint MISO_PIN = 16;
static const uint SCLK_PIN = 18;
static const uint CS_PIN   = 5;

int main(void) {
    stdio_init_all();
    sleep_ms(2000);

    spi_init(spi0, 8000000);
    gpio_set_function(MOSI_PIN, GPIO_FUNC_SPI);
    gpio_set_function(MISO_PIN, GPIO_FUNC_SPI);
    gpio_set_function(SCLK_PIN, GPIO_FUNC_SPI);

    SPIConnectionPicoSDK connection(spi0, CS_PIN);                         // Create SPI connection, (spi0, cs_pin=5) → SPIConnectionPicoSDK
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

    printf("Watching for motion. Pick up or tap the board to wake; "
           "let it settle to sleep.\n");

    int last_awake = -1;
    int transitions = 0;
    uint32_t start_ms = to_ms_since_boot(get_absolute_time());
    while (to_ms_since_boot(get_absolute_time()) - start_ms < 60000) {        // Loop until 60 s elapsed, () → bool
        bool now_awake = accel.awake();                                      // Read AWAKE bit, () → bool
        if (last_awake == -1 || (int)now_awake != last_awake) {
            uint32_t now_ms = to_ms_since_boot(get_absolute_time()) - start_ms;
            printf("%lu ms  %s\n", now_ms, now_awake ? "AWAKE" : "asleep");   // Print timestamped state, () → None
            transitions++;
            last_awake = (int)now_awake;
        }
        sleep_ms(200);                                                       // Sleep 200 ms between polls, () → None
    }

    printf("Total transitions observed: %d\n", transitions);                 // Print final count, () → None
    return 0;
}