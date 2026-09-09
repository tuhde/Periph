#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "BMP384.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

extern "C" void app_main(void) {
    i2c_master_bus_config_t bus_cfg = {
        .i2c_port = I2C_NUM_0,
        .sda_io_num = static_cast<gpio_num_t>(21),
        .scl_io_num = static_cast<gpio_num_t>(22),
        .clk_source = I2C_CLK_SRC_DEFAULT,
        .glitch_ignore_cnt = 7,
        .intr_priority = 0,
        .trans_queue_depth = 0,
        .flags = { .enable_internal_pullup = true, .allow_pd = false },
    };
    i2c_master_bus_handle_t bus;
    i2c_new_master_bus(&bus_cfg, &bus);

    i2c_device_config_t dev_cfg = {
        .dev_addr_length = I2C_ADDR_BIT_LEN_7,
        .device_address  = 0x76,
        .scl_speed_hz    = 400000,
        .scl_wait_us     = 0,
        .flags           = {},
    };
    i2c_master_dev_handle_t dev;
    i2c_master_bus_add_device(bus, &dev_cfg, &dev);

    I2CConnectionESPIDF connection(dev);
    BMP384Full bmp(connection, false);                      // Create BMP384 driver, (connection, spi=false)

    bmp.configure(4, 1, 2, 0x03);                          // Configure ADC and IIR filter, (osr_p 0–5, osr_t 0–5, iir_filter 0–7, odr_sel 0x00–0x11) → None
                                                            // sets oversampling, IIR coefficient, and output data rate
    bmp.set_mode(BMP384Full.MODE_NORMAL);                   // Set power mode, (mode 0/1/3) → None
    bool ready = bmp.is_data_ready();                       // Check data-ready flag, () → bool
                                                            // true if STATUS.drdy_press is set
    float t = bmp.temperature();                            // Read temperature, () → float °C
    float p = bmp.pressure();                               // Read pressure, () → float hPa
    float tp = 0.0f, tt = 0.0f;
    bmp.read(tp, tt);                                       // Read both values in one burst, (pressure_hpa, temperature_c) → None
    float fp = 0.0f, ft = 0.0f;
    bmp.read_forced(fp, ft);                                // Trigger forced measurement and read, (pressure_hpa, temperature_c) → None
    bmp.fifo_configure(true, true, 64);                     // Configure FIFO, (press_en bool, temp_en bool, wtm 0–511, stop_on_full=false) → None
                                                            // enables FIFO, sets watermark, arms pressure+temperature frames
    const char* types[16];
    double values[16];
    size_t n = bmp.fifo_read(types, values, 16);            // Read and parse FIFO frames, (types, values, max_frames) → size_t
    bmp.fifo_flush();                                       // Flush FIFO contents, () → None
    float alt = bmp.altitude();                             // Compute altitude, (sea_level_hpa=1013.25) → float m
    bmp.softreset();                                        // Soft reset chip, () → None

    check_true(t > -40.0f && t < 85.0f, "temperature_in_range");
    check_true(p > 300.0f && p < 1250.0f, "pressure_in_range");
    printf("T=%.1f C, P=%.1f hPa, ready=%d, frames=%d, alt=%.1f m\n",
        t, p, ready, (int)n, alt);

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
}
