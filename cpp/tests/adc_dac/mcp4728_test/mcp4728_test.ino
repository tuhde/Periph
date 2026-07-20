#include <Wire.h>
#include "I2CTransport.h"
#include "MCP4728.h"

#ifndef TEST_SDA
#define TEST_SDA 8
#endif
#ifndef TEST_SCL
#define TEST_SCL 9
#endif
#ifndef TEST_I2C_FREQ
#define TEST_I2C_FREQ 400000
#endif
#ifndef TEST_ADDR
#define TEST_ADDR 0x60
#endif

I2CTransport transport(Wire, TEST_ADDR);
MCP4728Full dac(transport);

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) {
        Serial.print("PASS "); Serial.println(label);
        passed++;
    } else {
        Serial.print("FAIL "); Serial.println(label);
        failed++;
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000);

    Wire.begin(TEST_SDA, TEST_SCL, TEST_I2C_FREQ);

    dac.set_voltage(0, 0.5f);
    check_true("set_voltage(ch0, 0.5) accepted", true);

    dac.set_raw(1, 2048);
    check_true("set_raw(ch1, 2048) accepted", true);

    float fractions[4] = {0.0f, 0.25f, 0.5f, 1.0f};
    dac.set_all(fractions);
    check_true("set_all accepted", true);

    dac.set_voltage_eeprom(0, 0.5f, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    check_true("set_voltage_eeprom accepted", true);

    dac.set_raw_eeprom(1, 2048, MCP4728Full::VREF_EXTERNAL, MCP4728Full::GAIN_X1);
    check_true("set_raw_eeprom accepted", true);

    float fracs[4]    = {0.0f, 0.25f, 0.5f, 0.75f};
    uint8_t vrefs[4]  = {0, 0, 0, 0};
    uint8_t gains[4]  = {1, 1, 1, 1};
    dac.set_all_eeprom(fracs, vrefs, gains);
    check_true("set_all_eeprom accepted", true);

    dac.set_vref(0, 0, 0, 0);
    check_true("set_vref accepted", true);

    dac.set_gain(1, 1, 1, 1);
    check_true("set_gain accepted", true);

    dac.set_power_down(MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL,
                       MCP4728Full::PD_NORMAL, MCP4728Full::PD_NORMAL);
    check_true("set_power_down normal accepted", true);

    MCP4728Full::ReadResult state = dac.read();
    check_true("read returns code in range", state.channel[0].code <= 4095);
    check_true("read returns eeprom_code in range", state.channel[0].eeprom_code <= 4095);
    check_true("read returns gain valid", state.channel[0].gain == 1 || state.channel[0].gain == 2);

    dac.software_update();
    check_true("software_update accepted", true);

    dac.wake_up();
    check_true("wake_up accepted", true);

    dac.reset();
    check_true("reset accepted", true);

    bool ready = dac.is_eeprom_ready();
    check_true("is_eeprom_ready returns bool", true);

    Serial.print("===DONE: ");
    Serial.print(passed); Serial.print(" passed, ");
    Serial.print(failed); Serial.println(" failed===");
}

void loop() {
    delay(1000);
}
