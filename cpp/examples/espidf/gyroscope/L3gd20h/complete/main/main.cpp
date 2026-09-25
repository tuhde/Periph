#include <driver/i2c_master.h>
#include <I2CConnectionESPIDF.h>
#include <L3gd20h.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

static const char* TAG = "l3gd20h_complete";

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

    gyro.configure(L3gd20hFull::ODR_190_HZ, 0, 1);  // Configure, (odr 0-3, bw 0-3, full_scale 0-2) -> None

    gyro.configure_hp_filter(L3gd20hFull::HPM_NORMAL, 0);  // Configure HPF, (mode 0-3, cutoff 0-15) -> None
    gyro.enable_hp_filter(true);                            // Enable HPF, (enable=true) -> None

    gyro.configure_fifo(L3gd20hFull::FIFO_FIFO, 10);  // Configure FIFO, (mode 0/1/2/3/7, watermark 0-31) -> None
    gyro.enable_fifo(true);                            // Enable FIFO, (enable=true) -> None

    gyro.set_power_mode(L3gd20hFull::POWER_NORMAL);    // Set power mode, (mode='normal'/'sleep'/'power_down') -> None

    int8_t temp = gyro.temperature();                  // Read temperature, () -> int8_t
    ESP_LOGI(TAG, "Temperature: %d", temp);

    while (true) {
        if (gyro.data_ready()) {                       // Check data ready, () -> bool
            float x, y, z;
            gyro.gyro(x, y, z);                        // Read angular rate, () -> (float, float, float) rad/s
            ESP_LOGI(TAG, "x=%.3f y=%.3f z=%.3f rad/s", x, y, z);

            int16_t rx, ry, rz;
            gyro.gyro_raw(rx, ry, rz);                 // Read raw, () -> (int16_t, int16_t, int16_t)

            uint8_t level = gyro.fifo_level();         // FIFO level, () -> uint8_t
            if (level > 0) {
                float fx[32], fy[32], fz[32];
                uint8_t n = gyro.read_fifo(fx, fy, fz, level); // Read FIFO, (out_x, out_y, out_z, max) -> uint8_t
                ESP_LOGI(TAG, "FIFO: %d samples", n);
            }
        }
        vTaskDelay(pdMS_TO_TICKS(10));
    }
}