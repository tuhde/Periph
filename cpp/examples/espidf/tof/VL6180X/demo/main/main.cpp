#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "VL6180X.h"

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
    dev_cfg.device_address = VL6180XMinimal::I2C_ADDRESS;
    dev_cfg.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev, nullptr, nullptr, 2);
    VL6180XFull sensor(connection);                         // Create VL6180X Full driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    // --- Identification ---
    uint8_t major = 0, minor = 0;
    sensor.revision(major, minor);                          // Model revision, (uint8_t& major, uint8_t& minor) → void
    printf("model 0x%02X revision %u.%u\n", (unsigned)sensor.model_id(), (unsigned)major, (unsigned)minor); // Model ID, () → uint8_t

    // --- Single-shot ranging at 5 Hz for 10 s ---
    uint8_t mm = 0, err = 0;
    for (int i = 0; i < 50; i++) {
        if (sensor.read_range(mm)) printf("%u mm\n", (unsigned)mm); // Single-shot range, (uint8_t& mm) → bool
        vTaskDelay(pdMS_TO_TICKS(200));
    }

    // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
    sensor.start_continuous_range(50);                      // Start continuous ranging, (uint16_t period_ms) → bool
    for (int i = 0; i < 40; i++) {
        bool ok = sensor.read_range_continuous(mm);         // Wait for next sample, (uint8_t& mm) → bool
        printf("%u mm (%s)\n", (unsigned)mm, ok ? "valid" : "error");
    }
    sensor.stop_continuous_range();                         // Stop continuous ranging, () → void
    vTaskDelay(pdMS_TO_TICKS(200));

    // --- Out-of-window interrupt for 30-80 mm ---
    sensor.set_range_thresholds(30, 80);                    // Range thresholds, (uint8_t low, uint8_t high) → bool
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW); // Enable an interrupt, (uint8_t source, uint8_t mode) → bool
    sensor.start_continuous_range(50);
    for (int i = 0; i < 50; i++) {
        if (sensor.poll_interrupt() & VL6180XFull::SOURCE_RANGE) printf("range out of window\n"); // Read and clear, () → uint8_t
        vTaskDelay(pdMS_TO_TICKS(100));
    }
    sensor.stop_continuous_range();
    vTaskDelay(pdMS_TO_TICKS(200));
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_NEW_SAMPLE_READY);

    // --- Light meter at gain 1 and 20 ---
    static const uint8_t gains[2] = { 6, 0 };
    for (int i = 0; i < 2; i++) {
        sensor.set_als_gain(gains[i]);                      // ALS gain code, (uint8_t code) → bool
        float lux = sensor.read_lux();                      // Single-shot ALS in lux, () → float lux
        printf("gain code %u: %d.%02d lux\n", (unsigned)gains[i], (int)lux, (int)(lux * 100) % 100);
    }
    sensor.set_als_gain(6);

    // --- Interleaved ALS + range for 5 s ---
    sensor.set_interleaved(true);                           // Interleaved ALS + range, (bool on) → void
    sensor.start_continuous_als(100);                       // Start continuous ALS, (uint16_t period_ms) → bool
    vTaskDelay(pdMS_TO_TICKS(5000));
    sensor.stop_continuous_als();                           // Stop continuous ALS, () → void
    sensor.set_interleaved(false);

    // --- Calibration and diagnostics ---
    sensor.set_range_offset(0);                             // Part-to-part offset, (int8_t mm) → void
    sensor.set_crosstalk_rate(0.0f);                        // Crosstalk rate, (float rate_mcps) → bool
    sensor.set_als_integration_time(100);                   // ALS integration time, (uint16_t ms) → bool
    sensor.set_als_scaler(1);                               // ALS result scaler, (uint8_t scaler) → bool
    sensor.recalibrate_vhv();                               // Recalibrate VHV, () → bool
    uint16_t raw = 0;
    sensor.read_als_raw(raw);                               // Single-shot ALS count, (uint16_t& raw) → bool
    sensor.read_range_status(mm, err);                      // Range and error code, (uint8_t& mm, uint8_t& err) → bool
    printf("als raw %u, last range %u mm (error %u), offset %d mm\n", (unsigned)raw, (unsigned)mm, (unsigned)err, (int)sensor.get_range_offset()); // Read the offset, () → int8_t mm
    uint16_t hist[8];
    sensor.read_history(hist);                              // History buffer, (uint16_t out[8]) → void

    // --- Change the device address and back (volatile) ---
    sensor.set_address(0x30);                               // Change address, (uint8_t address) → bool
    i2c_device_config_t dev_cfg2 = {};
    dev_cfg2.dev_addr_length = I2C_ADDR_BIT_LEN_7;
    dev_cfg2.device_address = 0x30;
    dev_cfg2.scl_speed_hz = 400000;
    i2c_master_dev_handle_t dev2;
    i2c_master_bus_add_device(bus, &dev_cfg2, &dev2);
    I2CConnectionESPIDF moved(dev2, nullptr, nullptr, 2);
    VL6180XFull sensorMoved(moved);
    sensorMoved.init();
    printf("on 0x30: model 0x%02X\n", (unsigned)sensorMoved.model_id());// Model ID at the new address, () → uint8_t
    sensorMoved.set_address(0x29);                          // Back to the power-on 0x29
}
