#include <stdio.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "driver/i2c_master.h"
#include "I2CConnectionESPIDF.h"
#include "LPS22DF.h"

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
    LPS22DFFull chip(connection, false);                 // Create LPS22DF driver, (connection, spi=false)
    chip.configure(3, 0, false, 0, true);                  // Configure chip, (odr=10 Hz, avg=4, en_lpfp=false, lfpf_cfg=0, bdu=true) → None
    chip.oneshot();                                          // Trigger one-shot conversion, () → None
    float p = chip.pressure();                               // Read pressure, () → float Pa
    float t = chip.temperature();                            // Read temperature, () → float °C
    float alt = chip.altitude(101325.0);                     // Compute altitude, (sea_level_pa=101325.0) → float m
    chip.software_reset();                                   // Reset chip, () → None
    chip.set_pressure_offset(-50.0);                         // Set pressure offset, (offset_pa=-50.0) → None
    chip.set_pressure_threshold(102000.0);                   // Set pressure threshold, (threshold_pa=102000.0) → None
    chip.configure_interrupt(false, false, true, false, true, false, false, false);  // Configure interrupt, (int_h_l, pp_od, drdy, drdy_pls, int_en, int_f_wtm, int_f_full, int_f_ovr) → None
    chip.configure_pressure_event(true, false, false);      // Configure pressure event, (phe=true, ple=false, lir=false) → None
    chip.autozero();                                         // Capture AUTOZERO reference, () → None
    chip.reset_reference();                                  // Reset reference, () → None
    float ref = chip.reference_pressure();                   // Read reference pressure, () → float Pa
    chip.set_fifo_mode(LPS22DFFull::FIFO_FIFO);              // Set FIFO mode, (mode 0–5) → None
    chip.set_fifo_watermark(64);                             // Set FIFO watermark, (level 0–127) → None
    uint8_t count = chip.fifo_sample_count();                // Read FIFO sample count, () → int
    float samples[128];
    uint8_t n_read = chip.read_fifo(samples, 128);           // Read FIFO samples, (out_buf, max_samples) → int
    uint8_t src = chip.interrupt_source();                   // Read interrupt source, () → int
    printf("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%u/%u, src=0x%02X\n",
           t, p, alt, ref, n_read, count, src);
    while (1) vTaskDelay(pdMS_TO_TICKS(1000));
}