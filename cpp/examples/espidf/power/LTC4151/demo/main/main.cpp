// Auto-generated ESP-IDF example for LTC4151 (Full).
// Mirrors the Arduino LTC4151_Minimal example using the
// I2CConnectionESPIDF connection.

#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LTC4151.h"

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
    dev_cfg.device_address = 0x6A;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    for (int n = 0; n < 20; n++) {
        LTC4151Full::Readings r;
        ltc.read_all(r);                                                // Consistent read of all channels, (out Readings) → void
        printf("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V\n", r.vin, r.sense_v * 1000.0f, r.current, r.power, r.adin);
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    static const char* const names[3] = { "SENSE", "VIN", "ADIN" };
    for (uint8_t ch = 0; ch < 3; ch++) {
        float f = 0;
        uint32_t t0 = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS);
        bool ok = ltc.snapshot(ch, f);                                  // One snapshot conversion, (channel 0–2, out V) → bool
        uint32_t dt = (uint32_t)(xTaskGetTickCount() * portTICK_PERIOD_MS) - t0;
        printf("%s snapshot %u, %.4f V in %u ms\n", names[ch], (unsigned)ok, f, (unsigned)dt);
    }
    printf("snapshot mode afterwards %u\n", (unsigned)ltc.is_snapshot_mode());  // Snapshot mode active, () → bool

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    ltc.set_stuck_bus_timer(false);                                     // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(false);                                             // I²C page read/write, (enabled) → void
    printf("CONTROL (timer off, page off) 0x%02X\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t
    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
    printf("CONTROL (restored) 0x%02X\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    printf("temperature %.1f C\n", LTC4151Full::temperature_from_adin(900, 1200));  // NTC example circuit, (adin_code, vin_code) → float °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    float pmin = 1e9f, pmax = 0, psum = 0;
    for (int n = 0; n < 25; n++) {
        float p = 0;
        p = ltc.power();
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        vTaskDelay(pdMS_TO_TICKS(200));
    }
    printf("power min %.3f W, max %.3f W, avg %.3f W\n", pmin, pmax, psum / 25.0f);

    while (true) vTaskDelay(pdMS_TO_TICKS(1000));
}
