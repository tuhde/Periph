// TPIC6B595 demo — "knight rider" chase pattern across two cascaded devices.
//
// Hardware:
//   Two cascaded TPIC6B595s driving 16 LEDs as an automotive-cluster-style
//   indicator bank (DRAIN0..DRAIN7 on each device). Each LED's anode goes to
//   the supply through a series resistor and its cathode to a DRAIN pin;
//   writing HIGH turns the LED on (active-low via the DMOS sink).
//
// The demo walks a single lit LED back and forth across all 16 outputs and,
// every few sweeps, blanks every output for half a second via
// set_output_enable(false) to demonstrate glitch-free global blanking. The
// shadow register is untouched across the blank, so the chase pattern resumes
// exactly where it left off.
#include <SPI.h>
#include "SiPoConnection.h"
#include "TPIC6B595.h"

static constexpr uint8_t NUM_DEVICES = 2;
static constexpr uint8_t NUM_OUTPUTS = NUM_DEVICES * 8;

SiPoConnection connection(SPI, 17, 16, 15);                       // Create SiPo connection, (spi, rck_pin=17, srclr_pin=16, g_pin=15)
TPIC6B595Full<SiPoConnection> chip(connection, NUM_DEVICES);    // Create TPIC6B595 full driver, (connection, num_devices=2)
                                                                  // two cascaded devices — 16 outputs total; outputs start OFF

void setup() {
    Serial.begin(115200);
    SPI.begin();
}

void loop() {
    // --- Walk a single lit LED across all 16 outputs and back ---
    // Use write_all() each step so both cascaded devices latch together —
    // there is no way to update just one downstream device without re-sending
    // the whole chain's data.
    static int8_t position = 0;
    static int8_t direction = 1;
    static uint8_t sweep_count = 0;
    constexpr uint8_t BLANK_EVERY = 3;
    constexpr uint16_t BLANK_MS = 500;

    uint8_t bytes_[NUM_DEVICES] = {0, 0};
    uint8_t port = position / 8;
    uint8_t bit  = position % 8;
    bytes_[port] = (uint8_t)(1u << bit);
    chip.write_all(bytes_, NUM_DEVICES);                         // Write all device bytes, (values=uint8_t*, len=2) → void

    Serial.print("position=");
    Serial.print((int)position);
    Serial.print("  bytes=[0x");
    Serial.print(bytes_[0], HEX);
    Serial.print(", 0x");
    Serial.print(bytes_[1], HEX);
    Serial.println("]");

    // --- Periodically blank every output via G, then resume ---
    // set_output_enable(false) drives G HIGH, forcing every DMOS off without
    // touching the shadow register — the LEDs simply resume exactly where they
    // left off when G is re-enabled.
    sweep_count++;
    if (sweep_count % BLANK_EVERY == 0) {
        chip.set_output_enable(false);                          // Force every output off via G, (enabled=false) → bool
                                                                  // the chase pattern's shadow state is preserved
        Serial.print("  blanked via G for ");
        Serial.print(BLANK_MS);
        Serial.println(" ms");
        delay(BLANK_MS);
        chip.set_output_enable(true);                            // Re-enable outputs, (enabled=true) → bool
                                                                  // LEDs resume from the previously-latched state
    }

    // Bounce the chase position at both ends of the strip
    position += direction;
    if (position >= (int8_t)(NUM_OUTPUTS - 1) || position <= 0) {
        direction = -direction;
        delay(100);
    } else {
        delay(80);
    }
}
