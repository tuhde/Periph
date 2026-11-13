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
    // --- Power-up position ---
    // The constructor leaves the wiper where the IVR put it (factory default 0x40).
    uint8_t up = pot.getWiper();                        // Read wiper position, () → uint8_t 0–127
    printf("power-up wiper %d -> %.3f V\n", (int)up, (double)pot.getVoltage());

    // --- Set 6.0 V ---
    pot.setVoltage(6.0f);                               // Set RW output voltage, (volts V) → void
    float ratio = pot.getRatio();                       // Read divider ratio, () → float
    printf("code %d ratio %.3f voltage %.3f V\n", (int)pot.getWiper(), (double)ratio, (double)pot.getVoltage());

    // --- Sweep in 16 steps ---
    // The step size is (V_RH − V_RL)/127, about 118 mV at a 15 V span.
    for (int code = 0; code <= 127; code += 8) {
        pot.setWiper(code);                             // Set wiper position, (code 0–127) → void
        printf("code %d -> %.3f V\n", code, (double)pot.getVoltage());
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    pot.setWiper(127);

    // --- Step up and down ---
    pot.setVoltage(6.0f);
    uint8_t up4 = pot.increment(4);                     // Step the wiper, (steps=1) → uint8_t 0–127
    uint8_t dn4 = pot.increment(-4);
    printf("+4 -> %d, -4 -> %d\n", (int)up4, (int)dn4);

    // --- MODE ---
    // MODE = 1 (the working mode) keeps wiper writes in SRAM; storeWiper switches
    // to MODE = 0 only for the duration of the EEPROM write.
    bool eeprom = pot.getMode();                        // Read MODE, () → bool
    printf("eeprom mode %d\n", (int)eeprom);
    pot.setMode(false);                                 // Select SRAM-only writes, (eeprom bool) → void

    // --- Persist the chosen code ---
    // This consumes one of the EEPROM's limited write cycles.
    int code = pot.getWiper();
    bool stored = pot.storeWiper(code);                 // Store power-up position, (code=-1) → bool
    bool ready = pot.waitReady();                       // Acknowledge polling, (timeoutMs=30) → bool
    printf("stored %d, ready %d, read back %d\n", (int)stored, (int)ready, (int)pot.getWiper());
}
