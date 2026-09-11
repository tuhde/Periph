#include <stdio.h>
#include <math.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "ADXL345.h"

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
        .device_address  = 0x53,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    ADXL345Minimal accel(connection);                       // Create ADXL345 driver, (connection, spi=false)

    // --- 50-sample stationary tilt characterization at 10 Hz ---
    // With the sensor flat and the Z axis up, gravity should project entirely
    // onto Z. Tilting the board visibly redistributes the 1 *g* magnitude
    // across X and Y; the total vector magnitude stays near 1 *g*.
    const int SAMPLES = 50;

    float mag_min = 1e9f, mag_max = -1e9f;

    for (int n = 0; n < SAMPLES; n++) {
        float x, y, z;
        accel.read(x, y, z);                                // Read 3-axis acceleration, (x, y, z) → g, g, g
        float mag = sqrtf(x * x + y * y + z * z);
        if (mag < mag_min) mag_min = mag;
        if (mag > mag_max) mag_max = mag;
        printf("%2d  x=%+.3f  y=%+.3f  z=%+.3f  |a|=%.3f g\n",
               n, (double)x, (double)y, (double)z, (double)mag);
        vTaskDelay(pdMS_TO_TICKS(100));
    }

    printf("min |a|=%.3f g  max |a|=%.3f g\n", (double)mag_min, (double)mag_max);
}