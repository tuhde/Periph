#include <Wire.h>
#include "I2CTransport.h"
#include "APDS9960.h"

I2CTransport transport(Wire, 0x39);
APDS9960Full apds(transport);                              // Create APDS9960 driver, (transport) → APDS9960Full

void setup() {
    Serial.begin(115200);
    Wire.begin();

    Serial.println(apds.chip_id(), HEX);                   // Read device ID, () → uint8_t

    uint16_t c, r, g, b;
    apds.color(c, r, g, b);                                // Read all RGBC channels, (clear, red, green, blue) → void
                                                           // burst read 0x94-0x9B latches all channels atomically
    Serial.println(apds.color_clear());                    // Read clear channel, () → uint16_t
    Serial.println(apds.color_red());                      // Read red channel, () → uint16_t
    Serial.println(apds.color_green());                    // Read green channel, () → uint16_t
    Serial.println(apds.color_blue());                     // Read blue channel, () → uint16_t

    apds.configure_als(0xB6, 1);                           // Configure ALS, (atime 0-255, again 0-3) → void
                                                           // sets integration time and gain for the ALS/color engine
    apds.configure_wait(0xFF, false);                      // Configure wait, (wtime 0-255, wlong=false) → void
                                                           // sets idle period between measurement cycles
    apds.enable_wait(true);                                // Enable wait engine, (enabled) → void

    apds.enable_proximity(true);                           // Enable proximity engine, (enabled) → void
    apds.configure_proximity_led(0, 0, 0, 1);              // Configure proximity LED, (ldrive 0-3, pgain 0-3, ppulse 0-63, pplen 0-3) → void
                                                           // sets LED drive strength, gain, pulse count and length
    apds.set_led_boost(0);                                 // Set LED boost, (boost 0-3) → void
                                                           // multiplies LED current: 0=100%, 1=150%, 2=200%, 3=300%
    Serial.println(apds.proximity());                      // Read proximity count, () → uint8_t

    apds.als_threshold(100, 60000);                        // Set ALS thresholds, (low 0-65535, high 0-65535) → void
    apds.proximity_threshold(10, 200);                     // Set proximity thresholds, (low 0-255, high 0-255) → void
    apds.set_persistence(0, 1);                            // Set persistence, (ppers 0-15, apers 0-15) → void

    apds.enable_als_interrupt(true);                       // Enable ALS interrupt, (enabled) → void
    apds.enable_proximity_interrupt(true);                 // Enable proximity interrupt, (enabled) → void
    apds.clear_als_interrupt();                            // Clear ALS interrupt, () → void
    apds.clear_proximity_interrupt();                      // Clear proximity interrupt, () → void
    apds.clear_all_interrupts();                           // Clear all interrupts, () → void

    apds.set_proximity_offset(10, -5);                     // Set proximity offset, (ur -127..127, dl -127..127) → void
                                                           // sign-magnitude encoding compensates for optical crosstalk
    apds.set_proximity_mask(false, false, false, false);   // Set proximity mask, (u, d, l, r) → void

    apds.enable_gesture(true);                             // Enable gesture engine, (enabled) → void
    apds.configure_gesture(1, 0, 0, 1, 1, 50, 20);        // Configure gesture, (ggain, gldrive, gpulse, gplen, gwtime, gpenth, gexth) → void
                                                           // sets gain, LED drive, pulse, wait time, entry/exit thresholds
    Serial.println(apds.gesture_available());              // Check gesture data, () → bool
    Serial.println(apds.gesture_fifo_level());             // Read FIFO level, () → uint8_t
    uint8_t fifo_buf[128];
    uint8_t n = apds.read_gesture_fifo(fifo_buf, 32);     // Read gesture FIFO, (buf, max_len) → uint8_t
    apds.clear_gesture_fifo();                             // Clear gesture FIFO, () → void
    apds.enable_gesture_interrupt(false);                  // Enable gesture interrupt, (enabled) → void
    apds.enable_gesture(false);                            // Disable gesture engine, (enabled) → void

    Serial.println(apds.status());                         // Read STATUS register, () → uint8_t
    Serial.println(apds.is_als_valid());                   // Check ALS data valid, () → bool
    Serial.println(apds.is_proximity_valid());             // Check proximity valid, () → bool
    Serial.println(apds.is_als_saturated());               // Check ALS saturated, () → bool
    Serial.println(apds.is_proximity_saturated());         // Check proximity saturated, () → bool

    apds.enable_proximity(false);
}

void loop() {}
