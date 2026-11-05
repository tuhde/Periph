// Auto-generated ESP-IDF example for MCP23008 (Minimal).
// Mirrors the Arduino MCP23008_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "MCP23008.h"

static MCP23008Full* g_chip = nullptr;

// Callbacks are plain function pointers (no std::function, no heap).
static void onChange(uint8_t status) {
    printf("interrupt, INTF=0x%02X\n", status);
}
static void onPin(MCP23008Full::IOExpanderPin*) {
    printf("GP4 falling\n");
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(21);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(22);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = 0x20;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    MCP23008Full chip(connection);                            // Create MCP23008 driver, (connection, addr=0x20)
                                                              // clears OLAT; all pins start as inputs
    g_chip = &chip;

    chip.configure_pullup(0xF0);                              // Enable pull-ups on GP4–GP7, (mask=0xF0) → void
                                                              // writes GPPU; only effective on inputs
    chip.configure_polarity(0x00);                            // Normal input polarity, (mask=0x00) → void
                                                              // writes IPOL; 1 bits invert the GPIO read
    chip.set_default_value(0x00);                             // Set DEFVAL compare value, (mask=0x00) → void
                                                              // reference for default-compare interrupts
    chip.configure_int_pin(false, false);                     // Configure INT output, (openDrain=false, activeHigh=false) → void
                                                              // push-pull, active-low (IOCON ODR/INTPOL)
    chip.set_sequential(true);                                // Keep auto-increment on, (enabled) → void
                                                              // IOCON SEQOP = 0 (driver default)

    for (uint8_t n = 0; n < 4; n++) {
        auto p = chip.pin(n);                                 // Get LED pin proxy, (n) → IOExpanderPin
                                                              // Full proxy with pull-up and watch support
        p.mode(OUTPUT);                                       // Set pin direction, (m INPUT/OUTPUT/INPUT_PULLUP) → void
                                                              // latches OLAT, then clears the IODIR bit
        p.high();                                             // Drive pin high, () → void
                                                              // sets the OLAT bit via the shadow
        p.toggle();                                           // Invert the output latch, () → void
                                                              // one OLAT write, no read-modify-write
    }

    chip.write_port(0, 0x05);                                 // Write the output latch, (port=0, mask) → void
                                                              // whole-port OLAT write, updates the shadow
    uint8_t port_val = chip.read_port();                      // Read all 8 pins, (port=0) → uint8_t bitmask
                                                              // reads GPIO (actual pin levels)
    printf("GPIO=0x%02X\n", port_val);
    uint8_t iodir = chip.read_register(0x00);                 // Read raw register, (reg) → uint8_t
                                                              // escape hatch for debugging
    printf("IODIR=0x%02X\n", iodir);
    chip.write_register(0x02, 0x10);                          // Write raw register, (reg, value) → void
                                                              // GPINTEN: arm GP4

    chip.enable_pin_interrupt(0xF0);                          // Arm GP4–GP7, (mask) → void
                                                              // writes GPINTEN
    chip.onInterrupt(onChange, nullptr, MCP23x08Full::INT_VS_DEFAULT);// Subscribe to INT, (callback, intPin=nullptr, mode) → bool
                                                              // uses connection.intPin() if wired; otherwise call service()
    chip.pin(4).watch(onPin, InputPin::kFalling);             // Watch GP4 falling edges, (handler, trigger) → bool
                                                              // arms GP4 in GPINTEN, filters the edge in software
    chip.service();                                           // Poll once and dispatch callbacks, () → void
                                                              // reads INTF/INTCAP; use when no INT pin is wired
    uint8_t flags = chip.pollInterrupt();                     // Read INTF and clear, () → uint8_t
                                                              // INTCAP is read and discarded
    uint8_t cap = chip.read_capture();                        // Read captured port state, () → uint8_t
                                                              // port snapshot at the interrupt
    printf("INTF=0x%02X INTCAP=0x%02X\n", flags, cap);
    chip.pin(4).unwatch();                                    // Stop watching GP4, () → void
                                                              // clears its GPINTEN bit
    chip.offInterrupt();                                      // Unsubscribe, () → void
                                                              // stops delivery
    chip.reset();                                             // Restore reset values, () → void
                                                              // rewrites all registers, clears shadows
}
