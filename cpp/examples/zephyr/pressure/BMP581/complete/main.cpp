#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "BMP581.h"

#ifndef BMP581_I2C_NODE
#define BMP581_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef BMP581_ADDR
#define BMP581_ADDR 0x46
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(BMP581_I2C_NODE);
    I2CConnectionZephyr connection(dev, BMP581_ADDR);
    BMP581Full bmp(connection);                           // Create BMP581 driver, (connection, spi=false)
    uint8_t cid = bmp.chip_id();                       // Read chip ID, () → int
    check_true(cid == 0x50, "chip_id");

    bmp.configure(0x1C, BMP581Full::OSR_1X, BMP581Full::OSR_1X, true);  // Configure chip, (odr 0x00–0x1F, osr_p 0–7, osr_t 0–7, press_en) → None
                                                        // writes OSR_CONFIG and ODR_CONFIG atomically
    bmp.set_mode(BMP581Full::MODE_NORMAL);               // Set power mode, (mode 0/1/2/3) → None
    bmp.set_iir_filter(BMP581Full::IIR_COEFF_3, BMP581Full::IIR_BYPASS);  // Set IIR filter, (coeff_p 0–7, coeff_t 0–7) → None
    bmp.configure_fifo(BMP581Full::FIFO_BOTH, BMP581Full::FIFO_STREAM, 8);  // Configure FIFO, (frame_sel 0–3, mode 0/1, threshold 0–31) → None
    uint8_t n = bmp.fifo_count();                        // Read FIFO frame count, () → int
    bmp.enable_drdy_interrupt(true);                     // Enable data-ready interrupt, (enable bool) → None
    bmp.data_ready();                                     // Check data ready, () → bool
    float p_f, t_f;
    bmp.forced(p_f, t_f);                                // Trigger FORCED measurement, (out p_pa, out t_c) → None
    float p_pa, t_c;
    bmp.both(p_pa, t_c);                                 // Read both atomically, (out p_pa, out t_c) → None
    float alt = bmp.altitude();                          // Compute altitude, (sea_level_pa=101325.0) → float m
    uint8_t st = bmp.status();                           // Read STATUS, () → int
    uint8_t ist = bmp.interrupt_status();                // Read INT_STATUS, () → int
    uint8_t op, ot;
    bmp.effective_osr(op, ot);                           // Read effective OSR, (out osr_p 0–7, out osr_t 0–7) → None
    bmp.set_oor_threshold(110000.0f, 200.0f, 1);         // Set OOR threshold, (threshold_pa, range_pa, count_limit 0–3) → None
    bmp.software_reset();                                // Soft reset chip, () → None

    printk("P=%.1f Pa, T=%.2f C, alt=%.1f m, frames=%d\n", p_pa, t_c, alt, n);
    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}