#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4725.h"

I2CTransport transport(Wire, 0x60);
MCP4725Full dac(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    dac.set_voltage(0.75);
    dac.set_raw(3000);
    dac.set_voltage_eeprom(0.5);
    dac.set_raw_eeprom(2048);
    MCP4725Full::ReadResult state = dac.read();
    Serial.print("code=");
    Serial.print(state.code);
    Serial.print(" ready=");
    Serial.println(state.eeprom_ready);
    dac.set_power_down(MCP4725Full.PD_100K_GND);
    dac.wake_up();
    dac.reset();
    dac.is_eeprom_ready();
    delay(1000);
}