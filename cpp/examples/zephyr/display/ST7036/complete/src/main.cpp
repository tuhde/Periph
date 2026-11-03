// ST7036 complete example: EA DOGM162W-A (2 x 16), Zephyr (rpi_pico2, SPI0 + display-dc/res aliases from the board overlay), 3.3 V module
// Wiring: see cpp/boards/zephyr/rpi_pico2_rp2350a_m33.overlay; PSB=GND, E=3V3 (BUSY unused).
// Module capacitors: 0.1-1 uF between CAP1N and CAP1P, 0.47-2.2 uF from VOUT to GND, VIN = VDD.
// Complete: every Full method — text, cursor and blink, entry mode, shift, contrast, booster, follower, bias, custom characters, display on/off.
#include <zephyr/kernel.h>
#include <zephyr/sys/printk.h>
#include <zephyr/drivers/spi.h>
#include "DisplayConnectionZephyr.h"
#include "OutputPinZephyr.h"
#include "InputPinZephyr.h"
#include "ST7036.h"

#define DELAY_MS(ms) k_msleep(ms)
#define PRINT printk
#define NOW_MS() ((uint32_t)k_uptime_get())

static void run(ST7036Full<DisplayConnectionZephyr>& lcd) {
    lcd.clear();                                    // Clear display, ()
                                                    // writes spaces to all of DDRAM, cursor to home (2 ms)
    lcd.print_at(0, 0, "Complete example");         // Write text, (col, row, text)
    lcd.set_cursor(0, 1);                           // Move cursor, (col, row)
    lcd.write("Cursor + blink");                    // Write text at the cursor, (text)
    lcd.cursor(true, true);                         // Cursor, (on, blink=false)
    DELAY_MS(2000);
    lcd.cursor(false, false);                       // Cursor, (on, blink=false)

    static const uint8_t heart[8] = {0x00, 0x0A, 0x1F, 0x1F, 0x0E, 0x04, 0x00, 0x00};
    static const uint8_t arrow[8] = {0x04, 0x02, 0x1F, 0x02, 0x04, 0x00, 0x00, 0x00};
    lcd.create_char(0, heart);                      // Define user character, (index 0..7, rows[8])
                                                    // 8 rows of 5 bits into CGRAM, DDRAM address restored
    lcd.create_char(1, arrow);                      // Define user character, (index 0..7, rows[8])
    lcd.clear();                                    // Clear display, ()
    lcd.set_cursor(0, 0);                           // Move cursor, (col, row)
    lcd.write("I ");                                // Write text at the cursor, (text)
    lcd.write_char(0);                              // Write raw character code, (code)
    lcd.write(" custom ");                          // Write text at the cursor, (text)
    lcd.write_char(1);                              // Write raw character code, (code)
    DELAY_MS(2000);

    lcd.set_contrast(0x08);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 1, "low contrast");             // Write text, (col, row, text)
    DELAY_MS(1500);
    lcd.set_contrast(0x30);                         // Set contrast, (value 0..63)
    lcd.print_at(0, 1, "high contrast");            // Write text, (col, row, text)
    DELAY_MS(1500);
    lcd.set_contrast(0x18);                         // Set contrast, (value 0..63)
                                                    // 0x18 is the DOGM162 3.3 V preset
    lcd.set_bias(false);                            // LCD bias, (quarter=false) 1/5 bias
    lcd.set_follower(true, 5);                      // Voltage follower, (on, ratio 0..7) 1 + Rb/Ra = 2.5
    lcd.set_booster(true);                          // Voltage booster, (on)

    lcd.clear();                                    // Clear display, ()
    lcd.set_entry_mode(true, false);                // Entry mode, (increment, shift=false)
    lcd.print_at(0, 0, "Shift display ->");         // Write text, (col, row, text)
    lcd.print_at(0, 1, "0123456789ABCDEF");         // Write text, (col, row, text)
    DELAY_MS(1000);
    for (int i = 0; i < 6; i++) {
        lcd.shift_display(true);                    // Shift display, (right)
        DELAY_MS(300);
    }
    lcd.return_home();                              // Return home, () — undoes the display shift
    lcd.shift_cursor(true);                         // Shift cursor, (right)
    lcd.set_ddram_address(0x40);                    // Set DDRAM address, (address)
    DELAY_MS(1000);

    lcd.display(false);                             // Display on/off, (on)
    DELAY_MS(800);
    lcd.display(true);                              // Display on/off, (on)
    PRINT("done\n");
}

int main(void) {
    const struct device* spi_dev = DEVICE_DT_GET(DT_NODELABEL(spi0));

    // CS# is driven by the SPI driver from the spi0 node's cs-gpios.
    struct spi_config spi_cfg = {};
    spi_cfg.frequency = 1000000;
    spi_cfg.operation = SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_OP_MODE_MASTER;
    spi_cfg.cs.gpio   = GPIO_DT_SPEC_GET(DT_NODELABEL(spi0), cs_gpios);
    spi_cfg.cs.delay  = 0;

    const struct gpio_dt_spec dcSpec   = GPIO_DT_SPEC_GET(DT_ALIAS(display_dc), gpios);
    const struct gpio_dt_spec resSpec  = GPIO_DT_SPEC_GET(DT_ALIAS(display_res), gpios);

    OutputPinZephyr dc(dcSpec);                       // Create D/C# pin, (gpio_dt_spec)
    OutputPinZephyr res(resSpec);                     // Create RES# pin, (gpio_dt_spec)

    DisplayConnectionZephyr connection(spi_dev, spi_cfg, nullptr, &dc, &res, nullptr);   // Create display connection, (spi_dev, spi_cfg, cs=nullptr, dc, res, busy, wireMode=kSimplex)
    ST7036Full<DisplayConnectionZephyr> lcd(connection);  // Create ST7036 driver, (connection, lines=2, supply=SUPPLY_3V3, contrast=-1 preset)
    if (!lcd.valid()) {
        PRINT("ST7036 init failed (check wiring)\n");
        return 1;
    }
    run(lcd);
    return 0;
}
