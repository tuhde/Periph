#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "DS3502.h"

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
    dev_cfg.device_address = DS3502Minimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    DS3502Full pot(connection, 15.0f, 0.0f);            // Create DS3502 Full driver, (connection, vHigh=1.0 V, vLow=0.0 V)
    pot.setWiper(100);                                  // Set wiper position, (code 0–127) → void
    // writes WR only; the EEPROM is not touched
    uint8_t w = pot.getWiper();                         // Read wiper position, () → uint8_t 0–127
    // reads WR; always the live wiper, never the IVR
    printf("wiper %d\n", (int)w);
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    // nearest code of V = V_RL + (V_RH − V_RL)·code/127
    float v = pot.getVoltage();                         // Read unloaded RW voltage, () → float V
    // evaluates the divider formula for the current code
    printf("voltage %.3f V\n", (double)v);
    pot.setRatio(0.25f);                                // Set divider ratio, (ratio 0.0–1.0) → void
    // code = round(ratio × 127)
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    // code / 127
    printf("ratio %.4f\n", (double)ratio);
    float rl = pot.getResistance('L');                  // Read wiper-to-RL resistance, (terminal='L') → float Ω
    // nominal, RTOTAL = 10 kΩ ±20 %
    float rh = pot.getResistance('H');                  // Read wiper-to-RH resistance, (terminal='L') → float Ω
    printf("R_WL %.1f ohm, R_WH %.1f ohm\n", (double)rl, (double)rh);
    uint8_t n = pot.increment(5);                       // Step the wiper, (steps=1) → uint8_t 0–127
    // read, add, clamp, write; returns the new code
    printf("incremented to %d\n", (int)n);
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    // true if writes to 0x00 also program the EEPROM
    printf("eeprom mode %d\n", (int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void
    // CR = 0x80; true would write CR = 0x00
    bool stored = pot.storeWiper(64);                   // Store power-up position, (code=-1) → bool
    // MODE = 0, write WR+IVR, wait for the EEPROM, MODE = 1
    printf("stored %d\n", (int)stored);
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    // false if the chip keeps NACKing
    printf("ready %d\n", (int)ready);
    pot.setEndpoints(12.0f, 2.0f);                      // Change RH / RL voltages, (vHigh V, vLow=0.0 V) → void
    // affects the conversions only; no bus traffic
}
