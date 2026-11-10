// TMC2209 Minimal example: spin a stepper with the internal step generator.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include "UARTConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "TMC2209.h"

#define LOG(...) printk(__VA_ARGS__)
#define SLEEP_MS(ms) k_msleep(ms)

#ifndef TMC2209_UART_NODE
#define TMC2209_UART_NODE DT_NODELABEL(uart1)
#endif

static void example(Connection& connection) {
    TMC2209Minimal motor(connection, 0.11f);             // Create TMC2209 driver, (connection, r_sense_ohm=0.11 Ω, address=0, en_pin=nullptr, echo=true)
    if (!motor.ok()) { LOG("TMC2209 not found\n"); return; }

    motor.setCurrent(0.8f);                              // Set run current, (rms_a A RMS) → bool
    for (int i = 0; i < 2; i++) {
        motor.run(i == 0 ? 200.0f : -200.0f);            // Spin with the internal generator, (steps_per_s full steps/s, sign = direction) → void
        SLEEP_MS(3000);
        LOG("standstill=%d fault=%d\n", (int)motor.isStandstill(), (int)motor.readFault());  // isStandstill() → bool, readFault() → uint8_t bitmask
    }
    motor.stop();                                        // Stop the motor, () → void
}

int main(void) {
    const struct device* dev = DEVICE_DT_GET(TMC2209_UART_NODE);
    UARTConnectionZephyr connection(dev);
    example(connection);
    while (true) k_msleep(1000);
    return 0;
}
