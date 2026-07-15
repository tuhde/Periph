#include <Wire.h>
#include "I2CTransport.h"
#include "24AA02UID.h"

I2CTransport transport(Wire, 0x50);
EEPROM24AA02UIDMinimal eeprom(transport);                      // Create 24AA02UID driver, (transport, addr=0x50) → void

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    uint8_t uid[4];
    eeprom.read_uid(uid);                                       // Read 32-bit unique serial number, (buf[4]) → void
    Serial.print("UID: ");
    for (uint8_t i = 0; i < 4; i++) {
        if (uid[i] < 0x10) Serial.print('0');
        Serial.print(uid[i], HEX);
    }
    Serial.println();
    delay(2000);
}
