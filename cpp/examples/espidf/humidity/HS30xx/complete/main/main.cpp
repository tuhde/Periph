#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "HS30xx.h"

#define I2C_SDA  21
#define I2C_SCL  22
#define AHT_ADDR 0x44

// Replace with code that cuts and restores the sensor supply (MOSFET or GPIO).
static void power_cycle() { vTaskDelay(pdMS_TO_TICKS(100)); }

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
    HS30xxFull sensor(connection);                                  // Create HS30xx driver, (connection) → void
    float t = 0, rh = 0;
    uint16_t rh_raw = 0, t_raw = 0;
    uint8_t status = 0, hb = 0, tb = 0;
    uint32_t id = 0;
    uint16_t reg = 0;
    bool ok = false;
    sensor.read(t, rh);                                             // Read temperature and humidity, (t, rh) → bool °C, %RH
                                                                    // request, 40 ms wait, 4-byte fetch; one retry on stale data
    printf("%.2f C, %.1f %%RH\n", t, rh);
    t = sensor.read_temperature();                                  // Read temperature only, () → float °C
                                                                    // NAN on failure
    printf("temperature %.2f C\n", t);
    rh = sensor.read_humidity();                                    // Read humidity only, () → float %RH
                                                                    // 2-byte fetch, NAN on failure
    printf("humidity %.1f %%RH\n", rh);
    sensor.read_raw(rh_raw, t_raw, status);                         // Read raw values, (humidity_raw, temperature_raw, status) → bool
                                                                    // 14-bit values; status 0 = valid, 1 = stale
    printf("raw RH %lu raw T %lu status %lu\n", (unsigned long)(rh_raw), (unsigned long)(t_raw), (unsigned long)(status));
    sensor.set_measurement_wait(60);                                // Override the post-request wait, (ms) → void
                                                                    // default 40 ms; lower resolutions need less
    sensor.set_measurement_wait(40);                                // Back to the default wait
    t = sensor.dew_point();                                         // Compute dew point, () → float °C
                                                                    // one measurement, Magnus formula
    printf("dew point %.2f C\n", t);
    // NVM access only works within 10 ms after power-up, so it needs the power_cycle helper above.
    ok = sensor.get_resolution(hb, tb, power_cycle);                // Get NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // bits are 8, 10, 12 or 14
    printf("resolution %lu/%lu bit (ok=%lu)\n", (unsigned long)(hb), (unsigned long)(tb), (unsigned long)(ok));
    sensor.set_resolution(12, 12, power_cycle);                     // Set NVM resolution, (humidity_bits, temperature_bits, power_cycle) → bool
                                                                    // 8, 10, 12 or 14 bit; 0 leaves a channel unchanged
    ok = sensor.read_id(id, power_cycle);                           // Read the 32-bit sensor ID, (id, power_cycle) → bool
    printf("id 0x%lX (ok=%lu)\n", (unsigned long)(id), (unsigned long)(ok));
    sensor.enter_programming_mode();                                // Enter programming mode, () → void
                                                                    // only within 10 ms after power-up
    ok = sensor.read_nvm(0x06, reg);                                // Read an NVM register, (reg, value) → bool
                                                                    // 0x06 humidity / 0x11 temperature resolution, 0x1E / 0x1F ID
    printf("NVM 0x06 = 0x%lX (ok=%lu)\n", (unsigned long)(reg), (unsigned long)(ok));
    sensor.exit_programming_mode();                                 // Leave programming mode, () → void
    while (true) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}
