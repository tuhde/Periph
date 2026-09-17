#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "L3G4200D.h"

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .flags = { .enable_internal_pullup = true },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x68,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);

    // --- Rotation detector: 200 Hz, ±500 dps, FIFO stream with watermark 10 ---
    // 200 Hz ODR gives 5 ms per sample — fast enough to catch hand motion but
    // not so noisy that the FIFO drains before the watermark is reached.
    L3G4200DFull chip(connection, false);                 // Create L3G4200D driver, (connection, spi=false)
    chip.configure(1, 0, 500);                             // Configure chip, (odr=200Hz, bandwidth=0, full_scale=500) → None
    chip.enable_highpass(0, 4);                            // Enable high-pass, (mode=0, cutoff=4) → None
                                                            // cutoff index 4 at 200 Hz ODR ≈ 1 Hz; strips DC drift
    chip.enable_fifo(L3G4200DFull::FIFO_STREAM, 10);        // Enable FIFO, (mode=2=stream, watermark=10) → None

    float threshold_rad_s = 90.0f * (3.141592653589793f / 180.0f);
    int alerts = 0;

    // --- Loop: wait for FIFO watermark, drain, compute mean, alert on threshold ---
    // Stream mode keeps the oldest samples; the FIFO never blocks but the host
    // only acts once per watermark crossing to amortise I²C overhead.
    for (int n = 0; n < 50; n++) {
        while (chip.fifo_samples() < 10) {                 // Read FIFO count, () → int
            vTaskDelay(pdMS_TO_TICKS(5));
        }
        float x, y, z;
        chip.angular_rate(x, y, z);                        // Read X/Y/Z angular rate, () → (float, float, float) rad/s
        if (fabsf(x) > threshold_rad_s || fabsf(y) > threshold_rad_s || fabsf(z) > threshold_rad_s) {
            alerts++;
            printf("ALERT  X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        } else {
            printf("       X=%.2f Y=%.2f Z=%.2f rad/s\n", x, y, z);
        }
        vTaskDelay(pdMS_TO_TICKS(20));
    }

    printf("Total alerts: %d / 50\n", alerts);
    printf("===DONE: 0 passed, 0 failed===\n");
}
