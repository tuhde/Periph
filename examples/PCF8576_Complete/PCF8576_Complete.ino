#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x38
#endif

#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/display/PCF8576.h"

void setup() {
    Serial.begin(115200);
    delay(2000);
    Wire.begin(TEST_SDA, TEST_SCL, 400000);
    I2CTransport transport(Wire, TEST_ADDR);
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

    Serial.println("===DONE: 0 passed, 0 failed===");
}

void loop() { delay(1000); }
