#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CTransportZephyr.h"
#include "PCF8576.h"

#ifndef PCF8576_I2C_NODE
#define PCF8576_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8576_ADDR
#define PCF8576_ADDR 0x38
#endif

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printk("PASS %s\n", label); passed++; }
    else       { printk("FAIL %s\n", label); failed++; }
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCF8576_I2C_NODE);
    I2CTransportZephyr transport(dev, PCF8576_ADDR);
    PCF8576Full lcd(transport);                          // Create PCF8576 driver, (transport)
    lcd.clear();                                         // Blank the display, () → void
                                                          // zeros all 40 columns of display RAM
    lcd.device_select(0);                                // Select device on the bus, (subaddress 0–7) → void
                                                          // sets the subaddress counter for cascaded use
    lcd.set_mode(PCF8576Full::BACKPLANES_4, PCF8576Full::BIAS_1_3);  // Set drive mode, (backplanes 1–4, bias 0/1) → void
                                                          // configures 1:4 multiplex with 1/3 bias
    lcd.set_blink(PCF8576Full::BLINK_2_HZ);              // Set blink frequency, (frequency 0–3) → void
                                                          // ~2 Hz blink for visual attention
    lcd.set_bank(PCF8576Full::BANK_0, PCF8576Full::BANK_0);  // Select RAM bank, (input_bank 0/1, output_bank 0/1) → void
                                                          // selects rows 0-1 for both input and output
    static const uint8_t digits[] = {5, 6, 7, 8};
    uint8_t out[4];
    for (uint8_t i = 0; i < 4; i++) {
        out[i] = PCF8576Full::SEVEN_SEG[digits[i]];     // Encode 7-segment digit, (digit 0–9) → uint8_t
    }
    lcd.write_raw(0, out, 4);                            // Write raw bytes, (address 0–39, data, len) → void
                                                          // sets data pointer to 0 and writes all four digits
    lcd.disable();                                        // Disable display output, () → void
                                                          // blanks the panel while keeping RAM contents
    lcd.enable();                                         // Enable display output, () → void
                                                          // resumes output from RAM with the prior configuration

    printk("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
