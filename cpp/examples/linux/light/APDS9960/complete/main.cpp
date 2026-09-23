#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include "I2CConnectionLinux.h"
#include "APDS9960.h"

int main() {
    const char* bus_env  = getenv("I2C_BUS");
    const char* addr_env = getenv("I2C_ADDR");
    int     bus  = bus_env  ? atoi(bus_env)       : 1;
    uint8_t addr = addr_env ? (uint8_t)strtol(addr_env, nullptr, 0) : 0x39;
    I2CConnectionLinux connection(bus, addr);

    APDS9960Full apds(connection);                                          // Create APDS9960 driver, (connection)

    uint8_t id = apds.chip_id();                                           // Read ID register, () → uint8_t
                                                                           // 0xAB for the APDS-9960
    printf("chip_id=0x%02X\n", id);

    // --- Colour / ALS ---
    apds.configure_als(0xB6, 1);                                           // Set ALS timing and gain, (atime 0–255, again 0–3) → void
                                                                           // ATIME 0xB6 ≈ 200 ms, gain 4×
    usleep(250000);
    printf("c=%u r=%u g=%u b=%u\n", apds.color_clear(), apds.color_red(),  // Read one channel, () → uint16_t counts
           apds.color_green(), apds.color_blue());
    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                                // Read all four channels, (clear, red, green, blue) → void
                                                                           // one burst, so the channels come from the same cycle
    printf("valid=%d saturated=%d\n", apds.is_als_valid(), apds.is_als_saturated());  // ALS status flags, () → bool
    apds.als_threshold(100, 60000);                                        // Set ALS interrupt window, (low 0–65535, high 0–65535) → void
    apds.set_persistence(2, 3);                                            // Set interrupt persistence, (ppers 0–15, apers 0–15) → void
                                                                           // cycles out of range before INT asserts
    apds.enable_als_interrupt(true);                                       // Enable AIEN, (enabled) → void
    apds.clear_als_interrupt();                                            // Clear ALS interrupt, () → void
    apds.enable_als_interrupt(false);

    // --- Wait timer ---
    apds.configure_wait(0xFF, false);                                      // Set wait time, (wtime 0–255, wlong=false) → void
                                                                           // 0xFF = 2.78 ms between cycles; wlong multiplies by 12
    apds.enable_wait(true);                                                // Enable WEN, (enabled) → void
    apds.enable_wait(false);

    // --- Proximity ---
    apds.configure_proximity_led(0, 2, 7, 1);                              // Set LED drive/gain/pulses, (ldrive 0–3, pgain 0–3, ppulse 0–63, pplen 0–3) → void
                                                                           // 100 mA, 4× gain, 8 pulses of 8 µs
    apds.set_led_boost(0);                                                 // Set LED boost, (boost 0–3 = 100–300 %) → void
    apds.set_proximity_offset(0, 0);                                       // Offset crosstalk, (ur −127…127, dl −127…127) → void
    apds.set_proximity_mask(false, false, false, false);                   // Mask photodiodes, (u, d, l, r) → void
    apds.enable_proximity(true);                                           // Enable PEN, (enabled) → void
    usleep(50000);
    printf("prox=%u valid=%d saturated=%d\n", apds.proximity(),           // Read proximity, () → uint8_t 0–255 (higher = closer)
           apds.is_proximity_valid(), apds.is_proximity_saturated());      // Proximity status flags, () → bool
    apds.proximity_threshold(0, 50);                                       // Set proximity interrupt window, (low 0–255, high 0–255) → void
    apds.enable_proximity_interrupt(true);                                 // Enable PIEN, (enabled) → void
    apds.clear_proximity_interrupt();                                      // Clear proximity interrupt, () → void
    apds.enable_proximity_interrupt(false);

    // --- Gesture ---
    apds.configure_gesture(2, 0, 9, 3, 1, 40, 30);                         // Configure gesture engine, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → void
                                                                           // gesture mode starts when proximity exceeds gpenth
    apds.enable_gesture(true);                                             // Enable GEN, (enabled) → void
    apds.enable_gesture_interrupt(false);                                  // Enable GIEN, (enabled) → void
    usleep(200000);
    if (apds.gesture_available()) {                                        // Gesture data waiting?, () → bool
        uint8_t level = apds.gesture_fifo_level();                         // FIFO fill level, () → uint8_t datasets
        uint8_t buf[32 * 4];
        uint8_t n = apds.read_gesture_fifo(buf, 32);                       // Read U/D/L/R datasets, (buf, max_len) → uint8_t datasets read
        printf("gesture fifo level=%u read=%u\n", level, n);
    }
    apds.clear_gesture_fifo();                                             // Discard gesture FIFO, () → void
    apds.enable_gesture(false);
    apds.enable_proximity(false);

    printf("status=0x%02X\n", apds.status());                             // Raw STATUS register, () → uint8_t
    apds.clear_all_interrupts();                                           // Clear every interrupt, () → void
    return 0;
}
