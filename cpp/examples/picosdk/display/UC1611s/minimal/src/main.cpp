// UC1611s minimal example: EA DOGXL240-7, Raspberry Pi Pico SDK (Pico / Pico W, i2c0)
// Wiring: SDA=GP4, SCL=GP5; VDD = 3V3
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), I2C: BM0 = D13 = VDD, CD = A2 = A3 = VSS -> command 0x38 / data 0x39.
// Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD; pull-ups 4.7 k to 3.3 V.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
#include <stdio.h>
#include <hardware/i2c.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "I2CConnectionPicoSDK.h"
#include "UC1611s.h"

#define DELAY_MS(ms) sleep_ms(ms)
#define PRINT printf

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
    stdio_init_all();
    sleep_ms(2000);

    i2c_init(i2c0, 400 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);

    I2CConnectionPicoSDK cmdConn(i2c0, 0x38);             // Create command connection, (i2c, addr=0x38)
    I2CConnectionPicoSDK dataConn(i2c0, 0x39);            // Create data connection, (i2c, addr=0x39)
    static UC1611sMinimal lcd(cmdConn, dataConn);  // Create UC1611s driver, (command, data, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed\n");
        return 1;
    }
    run(lcd);
    return 0;
}
