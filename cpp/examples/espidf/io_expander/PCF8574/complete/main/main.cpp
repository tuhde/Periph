#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "InputPinESPIDF.h"
#include "PCF8574.h"

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
                                                                 // initialises all pins as inputs; shadow = 0xFF

    PCF8574Full::IOExpanderPin p0 = chip.pin(0);                // Get full pin proxy, (n) → IOExpanderPin
                                                                 // returned by value; holds reference to chip
    p0.mode(OUTPUT);                                            // Set direction output, (mode=OUTPUT) → void
                                                                 // drives P0 low (initial state for output)
    p0.high();                                                  // Set high (release to quasi-input), () → void
                                                                 // shadow |= (1 << 0); writes full shadow byte
    p0.low();                                                   // Drive low, () → void
                                                                 // shadow &= ~(1 << 0); writes full shadow byte
    p0.toggle();                                                // Invert shadow bit, () → void
                                                                 // reads actual pin then flips shadow
    p0.write(HIGH);                                             // Write pin, (v=HIGH|LOW) → void
                                                                 // equivalent to high()
    uint8_t v = p0.read();                                      // Read actual level, () → uint8_t
                                                                 // reads full port byte, returns bit n
    printf("P0=%d\n", v);

    uint8_t mask = chip.read_port();                            // Read all 8 pins, (port=0) → uint8_t bitmask
                                                                 // P0 in bit 0, P7 in bit 7
    chip.write_port(0, 0b00001111);                             // Write all 8 pins, (port, mask) → void
                                                                 // P0–P3 low (outputs), P4–P7 high (inputs)
    printf("mask=0x%02X\n", mask);

    PCF8574Full::IOExpanderPin p4 = chip.pin(4);                // Get full pin proxy, (n) → IOExpanderPin
    p4.mode(INPUT);                                             // Set direction input, (mode=INPUT) → void
                                                                 // releases pin to quasi-input (shadow bit = 1)
    uint8_t state = p4.read();                                  // Read actual level, () → uint8_t
                                                                 // 0 if button pulls P4 low, 1 if floating
    printf("P4=%d\n", state);

    chip.onInterrupt([](uint8_t changed) {                      // Subscribe to INT line, (callback) → void
        printf("INT changed=0x%02X\n", changed);                // callback fires on any input change
    });

    uint8_t changed = chip.pollInterrupt();                     // Read and return changed bitmask, () → uint8_t
                                                                 // compares current byte to previous; clears INT
    printf("changed=0x%02X\n", changed);

    PCF8574Full::IOExpanderPin p5 = chip.pin(5);                // Get full pin proxy, (n) → IOExpanderPin
    p5.mode(INPUT);
    p5.watch([](PCF8574Full::IOExpanderPin* p) {                // Subscribe to pin edges, (handler, trigger) → void
        printf("P5 fell\n");                                    // fires when P5 transitions to match trigger
    }, InputPin::kFalling);
    p5.unwatch();                                                // Unsubscribe pin handler, () → void

    vTaskDelay(pdMS_TO_TICKS(1000));
}
