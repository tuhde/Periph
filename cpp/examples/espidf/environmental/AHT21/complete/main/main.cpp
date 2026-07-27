#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AHT21.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x38

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(I2C_SDA),
        .scl_io_num = static_cast<gpio_num_t>(I2C_SCL),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = AHT_ADDR,
        .scl_speed_hz    = 100000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AHT21Full aht(connection);                                          // Create AHT21 driver, (connection, addr=0x38) → void

    printf("Calibrated:    %d\n",       aht.is_calibrated());          // Check calibration status, () → bool
                                                                     // reads CAL bit from status byte
    printf("Busy:          %d\n",       aht.is_busy());                // Check busy status, () → bool
                                                                     // reads BUSY bit from status byte

    float t, h;
    aht.read(t, h);                                                    // Trigger measurement, (temperature_c, humidity_pct) → void
                                                                     // sends 0xAC trigger, waits 80 ms, decodes 6 bytes
    printf("Temperature:   %.2f C\n",   (double)t);
    printf("Humidity:      %.2f %%RH\n", (double)h);

    printf("Temperature:   %.2f C\n",   (double)aht.temperature());    // Read temperature only, () → float °C
                                                                     // triggers full measurement, returns temperature_c
    printf("Humidity:      %.2f %%RH\n", (double)aht.humidity());      // Read humidity only, () → float %RH
                                                                     // triggers full measurement, returns humidity_pct

    float tc, hc;
    bool crc_ok = aht.read_with_crc(tc, hc);                           // Read with CRC verification, (temperature_c, humidity_pct) → bool
                                                                     // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    printf("T: %.2f C  H: %.2f %%RH  CRC: %d\n", (double)tc, (double)hc, crc_ok);

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                     // sends 0xBA, waits 20 ms for recovery
}
