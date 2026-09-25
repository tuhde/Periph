#include <driver/i2c_master.h>
#include <I2CConnectionESPIDF.h>
#include <L3gd20h.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <math.h>

static const char* TAG = "l3gd20h_demo";

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

    I2CConnectionESPIDF conn(dev);
    L3gd20hFull gyro(conn);

    // --- Configure for shake detection at 190 Hz, ±500 dps ---
    // 190 Hz ODR provides good temporal resolution for shake detection;
    // ±500 dps full scale gives 17.5 mdps/digit sensitivity, suitable for
    // detecting moderate to strong motion without clipping.
    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    ESP_LOGI(TAG, "L3GD20H shake detector running. Shake the device...");

    while (true) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            float magnitude = sqrtf(x*x + y*y + z*z);
            if (magnitude > 1.0) {
                ESP_LOGI(TAG, "SHAKE DETECTED: mag=%.3f (x=%.3f y=%.3f z=%.3f)", magnitude, x, y, z);
            } else {
                ESP_LOGI(TAG, "x=%.3f y=%.3f z=%.3f mag=%.3f", x, y, z, magnitude);
            }
        }
    }
}