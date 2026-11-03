// UC1611s minimal example (4-wire SPI): EA DOGXL240-7, Raspberry Pi Pico SDK (Pico / Pico W)
// Wiring: SI=GP19 (SPI0 TX), SCK=GP18, CS0=GP17, CD=GP20, RST=GP21; VDD=3V3 (no BUSY line).
// Module: EA DOGXL240-7 (240 x 128, 3.3 V only), 4-wire SPI: BM0 = D13 = VSS. Capacitors: 4 x 4.7 uF/5 V (VA0, VA1, VB0, VB1 pairs), 470 nF/25 V on VLCD.
// Minimal use: a border and a diagonal on the 240 x 128 panel.
#include <stdio.h>
#include <hardware/spi.h>
#include <hardware/gpio.h>
#include <pico/stdlib.h>
#include "DisplayConnectionPicoSDK.h"
#include "OutputPinPicoSDK.h"
#include "InputPinPicoSDK.h"
#include "UC1611s.h"

#define DELAY_MS(ms) sleep_ms(ms)
#define PRINT printf
#define NOW_MS() to_ms_since_boot(get_absolute_time())

static void run(UC1611sSpiMinimal<DisplayConnectionPicoSDK>& lcd) {
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

    spi_init(spi0, 1000 * 1000);
    gpio_set_function(18, GPIO_FUNC_SPI);   // SCK
    gpio_set_function(19, GPIO_FUNC_SPI);   // MOSI

    OutputPinPicoSDK cs(17);                  // Create CS# pin, (gpio)
    OutputPinPicoSDK dc(20);                  // Create D/C# pin, (gpio)
    OutputPinPicoSDK res(21);                 // Create RES# pin, (gpio)

    DisplayConnectionPicoSDK connection(spi0, &cs, &dc, &res, nullptr);   // Create display connection, (spi, cs, dc, res, busy, wireMode=kSimplex)
    UC1611sSpiMinimal<DisplayConnectionPicoSDK> lcd(connection);  // Create UC1611s driver, (connection, width=240, height=128)
    if (!lcd.valid()) {
        PRINT("UC1611s init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
