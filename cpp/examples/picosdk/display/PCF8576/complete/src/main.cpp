#include <stdio.h>
#include <math.h>
#include <hardware/gpio.h>
#include "pico/stdlib.h"
#include "I2CTransportPicoSDK.h"
#include "PCF8576.h"

int main(void) {
    // I2C0 on GP4 (SDA) / GP5 (SCL) — pico-sdk default I2C pins
    i2c_init(i2c0, 100 * 1000);
    gpio_set_function(4, GPIO_FUNC_I2C);
    gpio_set_function(5, GPIO_FUNC_I2C);
    gpio_pull_up(4);
    gpio_pull_up(5);
    I2CTransportPicoSDK transport(i2c0, 0x70);
    PCF8576Full lcd(transport);

    stdio_init_all();
    sleep_ms(2000);

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

    printf("===DONE: 0 passed, 0 failed===\n");
    while (true) {
    sleep_ms(1000); 
        sleep_ms(10);
    }

    return 0;
}
