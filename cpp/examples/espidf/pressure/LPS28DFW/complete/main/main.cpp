#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LPS28DFW.h"

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
        .device_address  = 0x5C,
        .scl_speed_hz    = 400000,
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    LPS28DFWFull chip(connection);               // Create LPS28DFW driver, (connection)
    uint8_t cid = chip.chip_id();                // Read chip ID, () → uint8_t
                                                 // returns 0xB4 for LPS28DFW
    chip.configure(LPS28DFWFull::ODR_25_HZ, LPS28DFWFull::AVG_64,
                   LPS28DFWFull::FS_MODE_1, 1, LPS28DFWFull::LFPF_ODR_OVER_4);  // Configure chip, (odr 0–8, avg 0–7, fs_mode 0/1, lpf_en 0/1, lpf_cfg 0/1) → void
                                                 // sets output data rate, averaging, full-scale, IIR filter
    chip.set_threshold(1050.0, 1, 1);            // Set pressure threshold, (threshold_hpa, high, low) → void
                                                 // arms PH/PL when pressure crosses threshold_hPa
    chip.set_offset(0.5);                        // Set one-point calibration, (offset_hpa) → void
                                                 // subtracts 0.5 hPa from subsequent readings
    uint8_t ready = chip.is_data_ready();        // Check data ready, () → uint8_t
                                                 // reads STATUS.P_DA
    float p = 0.0f, t = 0.0f;
    chip.read(p, t);                             // Read both values, (pressure, temperature) → void
                                                 // burst-reads pressure+temperature
    chip.softreset();                            // Soft reset, () → void
                                                 // waits ~2 ms for reboot
    chip.fifo_configure(LPS28DFWFull::FIFO_FIFO, 16, 1);  // Configure FIFO, (mode 0–6, wtm 0–127, stop_on_wtm 0/1) → void
                                                 // enables 16-sample watermark FIFO
    uint8_t level = chip.fifo_level();           // FIFO unread count, () → uint8_t
    float samples[128] = {0};
    chip.fifo_read(level, samples);              // Drain FIFO, (count, buf) → void
    chip.read_oneshot(p, t);                     // One-shot read, (pressure, temperature) → void
                                                 // triggers a single measurement with ODR=0
    float alt = chip.altitude();                 // Compute altitude, (sea_level_hpa=1013.25) → float m
    printf("chip=0x%02X ready=%u p=%.2f t=%.2f alt=%.1f level=%u\n",
           cid, ready, p, t, alt, level);

    while (1) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}