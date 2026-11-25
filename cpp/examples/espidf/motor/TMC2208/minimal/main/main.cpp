// TMC2208 Minimal example: spin a stepper with the internal step generator.
// Wiring: MCU TX -> 1 kOhm -> PDN_UART, MCU RX -> PDN_UART directly (echo handling is on).
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/uart.h"
#include "driver/gpio.h"
#include "UARTConnectionESPIDF.h"
#include "OutputPinESPIDF.h"
#include "TMC2208.h"

#define LOG(...) printf(__VA_ARGS__)
#define SLEEP_MS(ms) vTaskDelay(pdMS_TO_TICKS(ms))

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

extern "C" void app_main(void) {
    uart_config_t uart_cfg = {};
    uart_cfg.baud_rate = 115200;
    uart_cfg.data_bits = UART_DATA_8_BITS;
    uart_cfg.parity = UART_PARITY_DISABLE;
    uart_cfg.stop_bits = UART_STOP_BITS_1;
    uart_cfg.flow_ctrl = UART_HW_FLOWCTRL_DISABLE;
    uart_driver_install(UART_NUM_1, 1024, 1024, 0, NULL, 0);
    uart_param_config(UART_NUM_1, &uart_cfg);
    uart_set_pin(UART_NUM_1, 17, 16, -1, -1);  // TX=17, RX=16

    UARTConnectionESPIDF connection(UART_NUM_1);
    example(connection);
    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}
