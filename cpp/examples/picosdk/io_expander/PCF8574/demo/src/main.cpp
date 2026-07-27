#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "PCF8574.h"

static volatile bool irq_flag = false;

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    InputPinPicoSDK intPin(6);                                  // Create INT pin, (pin=6)
    I2CConnectionPicoSDK connection(i2c0, 0x20, &intPin);       // Create I2C connection, (i2c, addr=0x20, intPin)
    PCF8574Full chip(connection, /*addr=*/0x20);

    stdio_init_all();

    // --- Configure LED outputs (P0–P3 driven low initially) ---
    // LEDs are active-low: pin low → LED on; pin high → LED off.
    // Writing 0xF0 sets P0–P3 low (LEDs on) and P4–P7 high (button inputs).
    chip.write_port(0, 0xF0);                                  // Write all 8 pins, (port, mask) → void

    // --- Subscribe to INT line for responsive button detection ---
    // INT fires within ~10 µs of any input change; the handler sets a flag
    // so the main loop can react immediately rather than wait 200 ms.
    chip.onInterrupt([](uint8_t) {                             // Subscribe to INT line, (callback) → void
        irq_flag = true;
    });

    printf("Running - press buttons on P4-P7\n");
    while (true) {

    if (irq_flag || true) {
        irq_flag = false;

        uint8_t port = chip.read_port();                       // Read all 8 pins, () → uint8_t bitmask

        // Buttons are in bits 4–7; pressed = 0 (pulled to GND)
        uint8_t buttons = (port >> 4) & 0x0F;
        // LEDs are active-low; invert button state: pressed → LED on (0)
        uint8_t led_bits = (~buttons) & 0x0F;
        chip.write_port(0, 0xF0 | led_bits);                   // Write all 8 pins, (port, mask) → void

        printf("0x%X", (unsigned)"port=0x"); printf("%d", port);
        printf("  btn=0b"); printf("%d", buttons, BIN);
        printf("  led=0b"); printf("%d\n", led_bits, BIN);
    }
    sleep_ms(200);
        sleep_ms(10);
    }

    return 0;
}
