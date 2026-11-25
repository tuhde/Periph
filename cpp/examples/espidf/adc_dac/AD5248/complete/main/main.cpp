// Auto-generated ESP-IDF example for AD5248 (Complete).
// Mirrors the Arduino AD5248_Complete example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AD5248.h"

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
    dev_cfg.device_address = 0x2C;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AD5248Full pot(connection, 10000.0f, 160.0f);                // Create AD5248 driver, (connection, r_ab=10000 Ω, r_wiper=160 Ω)

    // --- Wiper position ---
    pot.set_wiper(0, 100);                                   // Set wiper, (channel 0-1, code 0-255) → void
                                                             // writes [channel << 7, code]; code clamped to 0-255
    uint8_t code = pot.get_wiper(0);                         // Read wiper, (channel 0-1) → uint8_t 0-255
                                                             // sends the instruction byte alone, then reads one byte
    printf("code=%d\n", (int)(code));
    pot.set_both(10, 20);                                    // Set both wipers, (code1 0-255, code2 0-255) → void
                                                             // two transactions, one per channel
    // --- Resistance ---
    pot.set_resistance(1, 2200.0f);                          // Set W-B resistance, (channel 0-1, ohms) → void
                                                             // nearest code of R = code/256*RAB + 2*RW, clamped
    float ohms = pot.get_resistance(1);                      // Read W-B resistance, (channel 0-1) → float Ω
                                                             // code/256*RAB + 2*RW
    printf("ch1 ohm=%.1f\n", (double)(ohms));
    // --- Ratio and increment ---
    pot.set_ratio(0, 0.5f);                                  // Set wiper ratio, (channel 0-1, ratio 0.0-1.0) → void
                                                             // code = round(ratio*256) clamped to 255; needs terminal A wired
    float ratio = pot.get_ratio(0);                          // Read wiper ratio, (channel 0-1) → float 0.0-1.0
                                                             // code / 256
    printf("ratio=%.1f\n", (double)(ratio));
    uint8_t next = pot.increment(0, 5);                      // Step wiper, (channel 0-1, steps=1) → uint8_t 0-255
                                                             // read-modify-write, clamped to 0-255; negative steps decrement
    printf("next=%d\n", (int)(next));
    // --- Shutdown ---
    pot.shutdown(1);                                         // Shut channel down, (channel 0-1) → void
                                                             // opens terminal A, shorts wiper to B, RDAC kept
    bool down = pot.is_shutdown(1);                          // Query driver-tracked state, (channel 0-1) → bool
                                                             // the chip cannot be read back
    printf("shutdown=%d\n", (int)(down));
    pot.resume(1);                                           // Leave shutdown, (channel 0-1) → void
                                                             // rewrites the RDAC with SD = 0
    // --- Model and reset ---
    pot.set_rab(10000.0f, 160.0f);                           // Set resistance model, (r_ab Ω, r_wiper Ω) → void
                                                             // no I²C traffic; affects conversions only
    pot.reset();                                             // Reset to power-up preset, () → void
                                                             // writes midscale 0x80 to both channels with SD = 0
}
