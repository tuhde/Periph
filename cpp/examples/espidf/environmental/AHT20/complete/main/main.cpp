#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "AHT20.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x38

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {};
    bus_cfg.i2c_port = I2C_NUM_0;
    bus_cfg.sda_io_num = static_cast<gpio_num_t>(I2C_SDA);
    bus_cfg.scl_io_num = static_cast<gpio_num_t>(I2C_SCL);
    bus_cfg.clk_source = I2C_CLK_SRC_DEFAULT;
    bus_cfg.glitch_ignore_cnt = 7;
    bus_cfg.flags.enable_internal_pullup = true;
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {};
    dev_cfg.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg.device_address = AHT_ADDR;
    dev_cfg.scl_speed_hz = 100000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    AHT20Full aht(connection);                                          // Create AHT20 driver, (connection, addr=0x38) → void

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

    uint32_t raw_rh, raw_t;
    aht.read_raw(raw_rh, raw_t);                                       // Read raw 20-bit values, (raw_humidity, raw_temperature) → bool
                                                                       // same measurement as read(), undecoded
    printf("%u %u\n", (unsigned)raw_rh, (unsigned)raw_t);
    bool crc_ok = false;
    aht.read_with_crc(t, h, crc_ok);                                   // Read with CRC check, (temperature_c, humidity_pct, crc_ok) → bool
                                                                       // reads 7 bytes, verifies CRC-8 (poly 0x31, init 0xFF)
    printf("crc_ok=%d\n", (int)crc_ok);


    printf("0x%02X\n", aht.read_status());                              // Read status byte, () → uint8_t
                                                                       // bit 7 BUSY, bit 3 CAL
    printf("%.2f\n", (double)aht.dew_point());                          // Dew point, () → float °C
                                                                       // one fresh measurement, Magnus formula

    aht.soft_reset();                                                  // Send soft reset command, () → void
                                                                     // sends 0xBA, waits 20 ms, re-runs the calibration check
}
