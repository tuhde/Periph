#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "Lps33hw.h"

#ifndef LPS33HW_I2C_NODE
#define LPS33HW_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef LPS33HW_ADDR
#define LPS33HW_ADDR 0x5C
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(LPS33HW_I2C_NODE);
    I2CConnectionZephyr connection(dev, LPS33HW_ADDR);
    LPS33HWFull lps(connection);                           // Create LPS33HW driver, (connection)

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

    printk("P=%.1f Pa, T=%.2f C\n", p, t);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}