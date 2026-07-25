#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4728.h"

I2CTransport transport(Wire, 0x60);
MCP4728Full dac(transport);

void setup() {
    Serial.begin(115200);
    Wire.begin();
}

void loop() {
    dac.set_voltage(0, 0.75f);
    dac.set_raw(2, 3000);
    float fractions[4] = {0.1f, 0.2f, 0.3f, 0.4f};
    dac.set_all(fractions);
    dac.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    dac.set_raw_eeprom(1, 2048, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    float fracs[4]    = {0.0f, 0.25f, 0.5f, 0.75f};
    uint8_t vrefs[4]  = {0, 0, 0, 0};
    uint8_t gains[4]  = {1, 1, 1, 1};
    dac.set_all_eeprom(fracs, vrefs, gains);
    dac.set_vref(0, 0, 0, 0);
    dac.set_gain(1, 1, 1, 1);
    dac.set_power_down(MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL,
                       MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL);
    MCP4728Full::ReadResult state = dac.read();
    Serial.print("ch0 code=");
    Serial.print(state.channel[0].code);
    Serial.print(" eeprom_ready=");
    Serial.println(state.eeprom_ready);
    dac.software_update();
    dac.wake_up();
    dac.reset();
    dac.is_eeprom_ready();
    delay(1000);
}
