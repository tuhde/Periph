#include <SPI.h>
#include "SiPoConnection.h"
#include "TPIC6B595.h"

SiPoConnection connection(SPI, 17);                              // Create SiPo connection, (spi, rck_pin=17, srclr_pin=-1, g_pin=-1)
TPIC6B595Minimal<SiPoConnection> chip(connection);               // Create TPIC6B595 driver, (connection, num_devices=1)
                                                                  // initialises every output to OFF (shadow zeroed, latched once)

TPIC6B595Minimal<SiPoConnection>::IOExpanderPin p0 = chip.pin(0); // Get pin proxy, (n=0) → IOExpanderPin
TPIC6B595Minimal<SiPoConnection>::IOExpanderPin p7 = chip.pin(7); // Get pin proxy, (n=7) → IOExpanderPin

void setup() {
    Serial.begin(115200);
    SPI.begin();
}

void loop() {
    p0.high();                                                    // Set DMOS output ON, () → void
    p7.low();                                                     // Set DMOS output OFF, () → void
    delay(500);
    p0.low();                                                     // Set DMOS output OFF, () → void
    p7.high();                                                    // Set DMOS output ON, () → void
    delay(500);
}
