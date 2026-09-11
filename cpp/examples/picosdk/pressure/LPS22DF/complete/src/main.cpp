#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "LPS22DF.h"

int main(void) {
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    LPS22DFFull lps(connection, /*spi=*/false);            // Create LPS22DF driver, (connection, spi=false)
    lps.configure(3, 0, false, 0, true);                     // Configure chip, (odr=10 Hz, avg=4, en_lpfp=false, lfpf_cfg=0, bdu=true) → None
    lps.oneshot();                                              // Trigger one-shot conversion, () → None
    float p = lps.pressure();                                  // Read pressure, () → float Pa
    float t = lps.temperature();                               // Read temperature, () → float °C
    float alt = lps.altitude(101325.0);                        // Compute altitude, (sea_level_pa=101325.0) → float m
    lps.software_reset();                                      // Reset chip, () → None
    lps.set_pressure_offset(-50.0);                            // Set pressure offset, (offset_pa=-50.0) → None
    lps.set_pressure_threshold(102000.0);                      // Set pressure threshold, (threshold_pa=102000.0) → None
    lps.configure_interrupt(false, false, true, false, true, false, false, false);  // Configure interrupt, (int_h_l, pp_od, drdy, drdy_pls, int_en, int_f_wtm, int_f_full, int_f_ovr) → None
    lps.configure_pressure_event(true, false, false);         // Configure pressure event, (phe=true, ple=false, lir=false) → None
    lps.autozero();                                            // Capture AUTOZERO reference, () → None
    lps.reset_reference();                                     // Reset reference, () → None
    float ref = lps.reference_pressure();                      // Read reference pressure, () → float Pa
    lps.set_fifo_mode(LPS22DFFull::FIFO_FIFO);                 // Set FIFO mode, (mode 0–5) → None
    lps.set_fifo_watermark(64);                                // Set FIFO watermark, (level 0–127) → None
    uint8_t count = lps.fifo_sample_count();                   // Read FIFO sample count, () → int
    float samples[128];
    uint8_t n_read = lps.read_fifo(samples, 128);              // Read FIFO samples, (out_buf, max_samples) → int
    uint8_t src = lps.interrupt_source();                      // Read interrupt source, () → int

    stdio_init_all();
    sleep_ms(2000);
    printf("T=%.2f C, P=%.0f Pa, alt=%.1f m, ref=%.0f Pa, fifo=%u/%u, src=0x%02X\n",
           t, p, alt, ref, n_read, count, src);
    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) sleep_ms(1000);
    return 0;
}