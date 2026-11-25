// UC1611s minimal example: EA DOGXL240-7, Zephyr (devicetree node label i2c0)
// Wiring: SDA/SCL = the i2c0 pins of the board; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), I2C: BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
#include <zephyr/kernel.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include "I2CConnectionZephyr.h"
#include "UC1611s.h"

#ifndef UC1611S_I2C_NODE
#define UC1611S_I2C_NODE DT_NODELABEL(i2c0)
#endif
#ifndef UC1611S_ADDR
#define UC1611S_ADDR 0x38
#endif

#define PRINT printk
#define DELAY_MS(ms) k_msleep(ms)

static void run(UC1611sMinimal& lcd) {
    lcd.clear();                                    // Clear framebuffer, ()
    for (int16_t x = 0; x < 240; x++) {
        lcd.set_pixel(x, 0);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, 127);                      // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(x, static_cast<int16_t>(x * 127 / 239));  // Set pixel, (x 0..239, y 0..127, on=true)
    }
    for (int16_t y = 0; y < 128; y++) {
        lcd.set_pixel(0, y);                        // Set pixel, (x 0..239, y 0..127, on=true)
        lcd.set_pixel(239, y);                      // Set pixel, (x 0..239, y 0..127, on=true)
    }
    lcd.show();                                     // Send framebuffer to the display, ()
    PRINT("done\n");
}

int main(void) {
    const struct device *dev = DEVICE_DT_GET(UC1611S_I2C_NODE);
    I2CConnectionZephyr cmdConn(dev, UC1611S_ADDR);                // Create command connection, (dev, addr=0x38)
    I2CConnectionZephyr dataConn(dev, UC1611S_ADDR + 1);           // Create data connection, (dev, addr=0x39)
    static UC1611sMinimal lcd(cmdConn, dataConn);  // Create UC1611s driver, (command, data, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
