#include <Arduino.h>
#include <Wire.h>
#include "../../src/transport/I2CTransport.h"
#include "../../src/chips/display/PCF8576.h"

I2CTransport transport(Wire, 0x38);
PCF8576Full lcd(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
    Wire.setClock(400000);
    lcd.clear();
    lcd.set_blink(PCF8576Full::BLINK_OFF);
    lcd.enable();
    lcd.set_mode(4, 0);
    lcd.device_select(0);
    lcd.set_bank(0, 0);
    lcd.set_digit_7seg(0, PCF8576Full::SEG_7SEG[1]);
    lcd.set_digit_7seg(1, PCF8576Full::SEG_7SEG[2]);
    lcd.set_digit_7seg(2, PCF8576Full::SEG_7SEG[3]);
    lcd.set_digit_7seg(3, PCF8576Full::SEG_7SEG[4]);
    delay(1000);
    lcd.disable();
    delay(1000);
    lcd.enable();
    lcd.set_blink(PCF8576Full::BLINK_1HZ);
}

void loop() {}