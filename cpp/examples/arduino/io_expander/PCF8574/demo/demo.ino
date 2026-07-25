/**
 * PCF8574 demo — button-controlled LED mirror.
 *
 * Hardware:
 *   P0–P3: LEDs (anode → VCC, cathode → pin; active-low sink)
 *   P4–P7: push buttons (pin → GND when pressed; internal pull-up keeps pin high)
 *   INT:   connected to Arduino pin 5 (active-low open-drain)
 *
 * The demo reads the button nibble (P4–P7) and mirrors it inverted to the
 * LED nibble (P0–P3) every 200 ms.
 */
#include <Wire.h>
#include "I2CTransport.h"
#include "PCF8574.h"

I2CTransport transport(Wire, 0x20);                            // Create I2C transport, (wire, addr=0x20)
PCF8574Full chip(transport);                                    // Create PCF8574 full driver, (transport, addr=0x20)

volatile bool irq_flag = false;

void setup() {
    Serial.begin(115200);
    Wire.begin();

    // --- Configure LED outputs (P0–P3 driven low initially) ---
    // LEDs are active-low: pin low → LED on; pin high → LED off.
    // Writing 0xF0 sets P0–P3 low (LEDs on) and P4–P7 high (button inputs).
    chip.write_port(0, 0xF0);                                  // Write all 8 pins, (port, mask) → void

    // --- Attach INT line for responsive button detection ---
    // INT fires within ~10 µs of any input change; the ISR sets a flag
    // so the main loop can react immediately rather than wait 200 ms.
    chip.configure_interrupt(5, [](uint8_t) {                  // Attach interrupt, (gpio_pin, callback) → void
        irq_flag = true;
    });

    Serial.println("Running - press buttons on P4-P7");
}

void loop() {
    if (irq_flag || true) {
        irq_flag = false;

        uint8_t port = chip.read_port();                       // Read all 8 pins, () → uint8_t bitmask

        // Buttons are in bits 4–7; pressed = 0 (pulled to GND)
        uint8_t buttons = (port >> 4) & 0x0F;
        // LEDs are active-low; invert button state: pressed → LED on (0)
        uint8_t led_bits = (~buttons) & 0x0F;
        chip.write_port(0, 0xF0 | led_bits);                   // Write all 8 pins, (port, mask) → void

        Serial.print("port=0x"); Serial.print(port, HEX);
        Serial.print("  btn=0b"); Serial.print(buttons, BIN);
        Serial.print("  led=0b"); Serial.println(led_bits, BIN);
    }
    delay(200);
}
