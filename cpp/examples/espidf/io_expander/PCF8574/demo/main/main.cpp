/**
 * PCF8574 demo — button-controlled LED mirror.
 *
 * Hardware:
 *   P0–P3: LEDs (anode → VCC, cathode → pin; active-low sink)
 *   P4–P7: push buttons (pin → GND when pressed; internal pull-up keeps pin high)
 *   INT:   connected to GPIO_NUM_5 (active-low open-drain)
 */
#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "InputPinESPIDF.h"
#include "PCF8574.h"

static volatile bool irq_flag = false;

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x20,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    InputPinESPIDF intPin(GPIO_NUM_5);                          // Create INT pin, (pin=GPIO_NUM_5)
    I2CConnectionESPIDF connection(dev, &intPin);               // Create I2C connection, (dev, intPin)
    PCF8574Full chip(connection, 0x20);                         // Create PCF8574 full driver, (connection, addr=0x20)

    // --- Configure LED outputs (P0–P3 driven low initially) ---
    // LEDs are active-low: pin low → LED on; pin high → LED off.
    // Writing 0xF0 sets P0–P3 low (LEDs on) and P4–P7 high (button inputs).
    chip.write_port(0, 0xF0);                                   // Write all 8 pins, (port, mask) → void

    // --- Subscribe to INT line for responsive button detection ---
    // INT fires within ~10 µs of any input change; the handler sets a flag
    // so the main loop can react immediately rather than wait for the timer.
    chip.onInterrupt([](uint8_t) {                              // Subscribe to INT line, (callback) → void
        irq_flag = true;
    });

    printf("Running - press buttons on P4-P7\n");

    while (true) {
        if (irq_flag) {
            irq_flag = false;

            uint8_t port = chip.read_port();                    // Read all 8 pins, () → uint8_t bitmask

            // Buttons are in bits 4–7; pressed = 0 (pulled to GND)
            uint8_t buttons = (port >> 4) & 0x0F;
            // LEDs are active-low; invert button state: pressed → LED on (0)
            uint8_t led_bits = (~buttons) & 0x0F;
            chip.write_port(0, 0xF0 | led_bits);                // Write all 8 pins, (port, mask) → void

            printf("port=0x%02X  btn=0x%X  led=0x%X\n", port, buttons, led_bits);
        }
        vTaskDelay(pdMS_TO_TICKS(200));
    }
}
