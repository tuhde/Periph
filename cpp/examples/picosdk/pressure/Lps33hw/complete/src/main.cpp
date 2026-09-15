#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CConnectionPicoSDK.h"
#include "Lps33hw.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CConnectionPicoSDK connection(i2c0, 0x5C);
    LPS33HWFull lps(connection);

    static int passed = 0, failed = 0;

    stdio_init_all();
    sleep_ms(2000);

    lps.configure(LPS33HWFull::ODR_10_HZ, true, true, LPS33HWFull::LPFP_BW_ODR_20, false, false);  // Configure chip, (odr 0–5, bdu, en_lpfp, lpfp_cfg 0/1, lc_en, sim) → None
                                                        // sets CTRL_REG1 (ODR/BDU/LPF), RES_CONF (LPFP_CFG), CTRL_REG2 (SIM)
    float p_lp, t_lp;
    bool ok = lps.one_shot(p_lp, t_lp);                   // Trigger single measurement, () → (bool, &pressure_Pa, &temperature_C)
                                                        // requires ODR=0; returns false on timeout
    uint8_t st = lps.status();                            // Read STATUS register, () → uint8_t
                                                        // bit 0=P_DA, bit 1=T_DA, bit 4=P_OR, bit 5=T_OR
    lps.reset();                                          // Software reset via SWRESET, () → None
                                                        // restores CTRL_REG1 / CTRL_REG2 to defaults
    lps.reboot();                                         // Reload factory trimming via BOOT, () → None
                                                        // takes one ODR cycle to self-clear
    lps.set_pressure_offset(0.5f);                        // Apply one-point calibration, (offset_hPa) → None
                                                        // 1 RPDS LSB = 1/16 hPa
    lps.set_autozero();                                   // Set AUTOZERO, () → None
                                                        // current pressure is stored in REF_P
    lps.clear_autozero();                                 // Clear AUTOZERO and reset REF_P, () → None
    lps.set_autorifp();                                   // Set AUTORIFP, () → None
                                                        // next measurement value stored in RPDS
    lps.clear_autorifp();                                 // Clear AUTORIFP and reset RPDS, () → None

    lps.configure_interrupt(true, false, false, false, LPS33HWFull::INT_S_DATA_SIGNALS, false, false);  // Route events to INT_DRDY, (drdy, f_fth, f_ovr, f_fss5, int_s 0–3, active_low, open_drain) → None
                                                        // writes CTRL_REG3 (signal routing) and INTERRUPT_CFG (INT_S, active level, output mode)
    lps.configure_pressure_interrupt(true, false, 1050.0f, true);  // Configure pressure threshold interrupt, (high_en, low_en, threshold_hPa, latch) → None
                                                        // arms INT_DRDY when pressure exceeds threshold; latched until INT_SOURCE read
    uint8_t isrc = lps.interrupt_status();                // Read INT_SOURCE, () → uint8_t
                                                        // clears latched pressure interrupts

    lps.enable_fifo(LPS33HWFull::FIFO_MODE_FIFO, 16);     // Enable FIFO, (mode 0–7 excl. 5, watermark 0–31) → None
                                                        // writes FIFO_CTRL and sets F_EN in CTRL_REG2
    uint8_t fsts = lps.fifo_status();                     // Read FIFO_STATUS, () → uint8_t
                                                        // bit 7=FTH, bit 6=OVR, bits 5:0=FSS
    lps.disable_fifo();                                   // Disable FIFO and reset to Bypass, () → None
    lps.reset_lpf();                                      // Read LPFP_RES to flush transitory LPF state, () → None

    float p = lps.pressure();                             // Read pressure, () → float Pa
    float t = lps.temperature();                          // Read temperature, () → float °C

    printf("P=");
    printf("%.1f", p);
    printf(" Pa, T=");
    printf("%.2f", t);
    printf(" C\n");

    printf("===DONE: ");
    printf("%d", passed);
    printf(" passed, ");
    printf("%d", failed);
    printf(" failed===\n");
    while (true) {
    sleep_ms(1000);
        sleep_ms(10);
    }

    return 0;
}