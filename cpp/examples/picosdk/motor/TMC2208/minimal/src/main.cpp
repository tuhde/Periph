// TMC2208 Minimal example: spin a stepper with the internal step generator.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).
#include <stdio.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "UARTConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "TMC2208.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) sleep_ms(ms)

static void example(Connection& connection) {
    TMC2208Minimal motor(connection, 0.11f);             // Create TMC2208 driver, (connection, r_sense_ohm=0.11 Ω, en_pin=nullptr, echo=true)
    if (!motor.ok()) { LOG("TMC2208 not found\n"); return; }

    motor.setCurrent(0.8f);                              // Set run current, (rms_a A RMS) → bool
    for (int i = 0; i < 2; i++) {
        motor.run(i == 0 ? 200.0f : -200.0f);            // Spin with the internal generator, (steps_per_s full steps/s, sign = direction) → void
        SLEEP_MS(3000);
        LOG("standstill=%d fault=%d\n", (int)motor.isStandstill(), (int)motor.readFault());  // isStandstill() → bool, readFault() → uint8_t bitmask
    }
    motor.stop();                                        // Stop the motor, () → void
}

int main(void) {
    stdio_init_all();
    // UART0 on GP0 (TX) / GP1 (RX) — pico-sdk default UART pins.
    uart_init(uart0, 115200);
    gpio_set_function(0, GPIO_FUNC_UART);
    gpio_set_function(1, GPIO_FUNC_UART);
    UARTConnectionPicoSDK connection(uart0, 115200);
    example(connection);
    while (true) sleep_ms(1000);
    return 0;
}
