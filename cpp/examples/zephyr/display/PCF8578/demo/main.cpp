#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "PCF8578.h"

#ifndef PCF8578_I2C_NODE
#define PCF8578_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef PCF8578_ADDR
#define PCF8578_ADDR 0x3C
#endif

int main(void) {
    const struct device *dev = DEVICE_DT_GET(PCF8578_I2C_NODE);
    I2CConnectionZephyr connection(dev, PCF8578_ADDR);
    // --- Initialise a 16 x 24 dot-matrix panel ---
    // 1:16 multiplex gives 16 rows and 24 columns; the constructor clears the RAM
    // before switching the display on so no random start-up pattern is shown.
    PCF8578Full lcd(connection, 16);                          // Create PCF8578 driver, (connection, rows=16 8/16/24/32)

    // --- Draw a border and both diagonals ---
    // Lines and rectangles are rendered in the shadow buffer and sent as one
    // batch of changed bytes per run of columns.
    lcd.draw_rect(0, 0, 24, 16);                              // Draw rectangle outline, (x, y, w, h, on=true, fill=false) → void
    lcd.draw_line(0, 0, 23, 15);                              // Draw line, (x0, y0, x1, y1, on=true) → void
    lcd.draw_line(0, 15, 23, 0);                              // Draw line, (x0, y0, x1, y1, on=true) → void

    // --- Flash the display twice with inverse video ---
    // Inverse video only changes how the RAM is shown, so the picture is kept.
    for (int i = 0; i < 2; i++) {
        lcd.set_display(PCF8578Full::DISPLAY_INVERSE);        // Set display status, (status 0–3) → bool
        k_msleep(400);
        lcd.set_display(PCF8578Full::DISPLAY_NORMAL);         // Set display status, (status 0–3) → bool
        k_msleep(400);
    }

    // --- Fill all four RAM banks with distinct patterns ---
    // At 1:16 only banks 0 and 1 are visible; banks 2 and 3 act as off-screen
    // buffers that the start bank can scroll into view.
    static const uint8_t patterns[4] = {0x55, 0x33, 0x0F, 0xFF};
    for (int bank = 0; bank < 4; bank++) {
        uint8_t col[24];
        for (int i = 0; i < 24; i++) col[i] = patterns[bank];
        lcd.write_ram(16, bank, col, sizeof(col));            // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    }

    // --- Scroll through the banks with the hardware start bank ---
    // Stepping the start bank scrolls the display by 8 rows without rewriting RAM.
    for (int bank = 0; bank < 4; bank++) {
        lcd.set_start_bank(bank);                             // Set start bank, (bank 0–3) → bool
        uint8_t first = 0;
        lcd.read_ram(16, bank, &first, 1);                    // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
        printk("first column byte 0x%02X\n", (unsigned)(first));
        k_msleep(1000);
    }

    // --- Read back the first 8 RAM columns of bank 0 ---
    // RAM columns 0-7 are never displayed at 1:16, so they serve as scratch
    // storage; the chip supports reads in mixed mode, so the bytes can be verified.
    lcd.set_start_bank(0);                                    // Set start bank, (bank 0–3) → bool
    static const uint8_t scratch[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    lcd.write_ram(0, 0, scratch, sizeof(scratch));            // Write raw RAM, (x 0–39, bank 0–3, data, len, mode=0) → bool
    uint8_t back[8];
    lcd.read_ram(0, 0, back, sizeof(back));                   // Read raw RAM, (x 0–39, bank 0–3, buf, n, mode=0) → bool
    for (int i = 0; i < 8; i++) {
        printk("scratch byte 0x%02X\n", (unsigned)(back[i]));
    }

    printk("===DONE: 0 passed, 0 failed===\n");
    return 0;
}
