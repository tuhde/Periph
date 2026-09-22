#include <SPI.h>
#include "SiPoConnection.h"
#include "TPIC6B595.h"

SiPoConnection connection(SPI, 17, 16, 15);                       // Create SiPo connection, (spi, rck_pin=17, srclr_pin=16, g_pin=15)
TPIC6B595Full<SiPoConnection> chip(connection, 2);               // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                  // two cascaded devices — 16 outputs total (DRAIN0..DRAIN15)

TPIC6B595Full<SiPoConnection>::IOExpanderPin p0 = chip.pin(0);   // Get pin proxy for DRAIN0 of device 0, (n=0) → IOExpanderPin

void setup() {
    Serial.begin(115200);
    SPI.begin();

    // --- Pin-level control ---
    p0.high();                                                    // Set DRAIN0 ON, () → void
                                                                  // sets shadow[0] bit 0, reverses the cascade, shifts out and pulses RCK
    p0.low();                                                     // Set DRAIN0 OFF, () → void
                                                                  // clears shadow[0] bit 0, retransmits and latches
    p0.toggle();                                                  // Invert shadow bit, () → void

    uint8_t state = p0.read();                                    // Read pin state, () → uint8_t
                                                                  // returns the shadow bit (no bus read — SiPo is write-only)
    p0.write(HIGH);                                               // Write pin high, (v=HIGH) → void
                                                                  // equivalent to high(); updates shadow, retransmits, latches
    p0.set(true);                                                 // OutputPin set, (high=true) → void
                                                                  // same path as high()/write(HIGH), but matches the OutputPin contract

    // --- Port-level bulk write ---
    chip.write_port(0, 0xAA);                                    // Write device 0 outputs, (port=0, mask=0xAA) → void
                                                                  // sets DRAIN{1,3,5,7} ON, DRAIN{0,2,4,6} OFF; preserves device 1
    chip.write_port(1, 0x55);                                    // Write device 1 outputs, (port=1, mask=0x55) → void

    // --- Bulk fill / off ---
    chip.fill(true);                                             // Set every output ON, (value=true) → void
                                                                  // fills every shadow byte with 0xFF and retransmits — fast "all on" path
    chip.fill(false);                                            // Set every output OFF, (value=false) → void
                                                                  // fills every shadow byte with 0x00 and retransmits — fast "all off" path
    chip.off();                                                  // Turn every output off, () → void
                                                                  // shorthand for fill(false); the safe initial state

    // --- Multi-device bulk write ---
    uint8_t bytes_[2] = { 0x01, 0x80 };
    chip.write_all(bytes_, 2);                                   // Write all device bytes, (values=uint8_t*, len=2) → void
                                                                  // updates both shadow bytes and performs one transmit + latch

    // --- Hardware features (Full only) ---
    chip.clear();                                                // Pulse SRCLR, () → bool
                                                                  // clears the shift register only; outputs unaffected until next RCK pulse
    chip.set_output_enable(false);                               // Force every output off via G, (enabled=false) → bool
                                                                  // drives G HIGH, blanking outputs without disturbing the shadow register
    delay(100);
    chip.set_output_enable(true);                                // Re-enable outputs, (enabled=true) → bool
                                                                  // drives G LOW; outputs resume from the previously-latched state
}

void loop() {}
