#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4725.h"

I2CTransport transport(Wire, 0x60);
MCP4725Full dac(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
    dac.set_voltage(0.5);
    dac.set_raw(2048);
    dac.set_voltage_eeprom(0.75);
    dac.set_raw_eeprom(3000);
    MCP4725ReadResult result = dac.read();
    Serial.println(result.code);
    Serial.println(result.voltage_fraction);
    Serial.println(result.power_down);
    Serial.println(result.eeprom_code);
    Serial.println(result.eeprom_power_down);
    Serial.println(result.eeprom_ready);
    Serial.println(result.por);
    dac.set_power_down(1);
    dac.wake_up();
    dac.reset();
    dac.is_eeprom_ready();
}

void loop() {
    delay(1000);
}