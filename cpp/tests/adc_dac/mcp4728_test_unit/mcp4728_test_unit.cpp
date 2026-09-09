#include <stdio.h>
#include "I2CConnectionMock.h"
#include "MCP4728.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionMock connection;
    MCP4728Full dac(connection);
    check_true(true, "init");

    // set_voltage(channel=1, 0.5) -> code=2048 (0x800). Multi-Write:
    // byte1 = 0x40 | (1<<1) = 0x42, byte2 = code>>8 = 0x08, byte3 = code&0xFF = 0x00.
    dac.set_voltage(1, 0.5f);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x42, 0x08, 0x00}), "set_voltage");

    dac.set_voltage(1, 2.0f);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x42, 0x0F, 0xFF}), "set_voltage_clamps_high");

    // set_raw(channel=3, code=4095) -> byte1=0x46, byte2=0x0F, byte3=0xFF.
    dac.set_raw(3, 4095);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x46, 0x0F, 0xFF}), "set_raw");

    dac.set_raw(9, 9000);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x46, 0x0F, 0xFF}), "set_raw_clamps");

    // set_all([0.0, 1.0, 0.5, 0.25]) -> Fast Write, 8 bytes, PD=00.
    float fractions[4] = {0.0f, 1.0f, 0.5f, 0.25f};
    dac.set_all(fractions);
    check_true(connection.writes().back() ==
        std::vector<uint8_t>({0x00, 0x00, 0x0F, 0xFF, 0x08, 0x00, 0x04, 0x00}), "set_all");

    // set_voltage_eeprom(channel=2, 0.5, vref=1, gain=2) -> code=2048.
    // Single Write byte1=0x58|(2<<1)=0x5C, byte2=(1<<7)|(1<<4)|0x08=0x98, byte3=0x00.
    dac.set_voltage_eeprom(2, 0.5f, 1, 2);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x5C, 0x98, 0x00}), "set_voltage_eeprom");

    // set_raw_eeprom(channel=0, code=4095, vref=0, gain=1) -> byte1=0x58, byte2=0x0F, byte3=0xFF.
    dac.set_raw_eeprom(0, 4095, 0, 1);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x58, 0x0F, 0xFF}), "set_raw_eeprom");

    // set_all_eeprom: fractions=[0.0,1.0,0.5,0.25], vrefs=[0,1,0,1], gains=[1,2,1,2].
    float f2[4] = {0.0f, 1.0f, 0.5f, 0.25f};
    uint8_t vrefs[4] = {0, 1, 0, 1};
    uint8_t gains[4] = {1, 2, 1, 2};
    dac.set_all_eeprom(f2, vrefs, gains);
    check_true(connection.writes().back() == std::vector<uint8_t>(
        {0x50, 0x00, 0x00, 0x9F, 0xFF, 0x08, 0x00, 0x94, 0x00}), "set_all_eeprom");

    // set_vref(1, 0, 1, 0) -> byte1 = 0x80|(1<<3)|(1<<1) = 0x8A.
    dac.set_vref(1, 0, 1, 0);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x8A}), "set_vref");

    // set_gain(1, 2, 1, 2) -> byte1 = 0xC0|(1<<2)|1 = 0xC5.
    dac.set_gain(1, 2, 1, 2);
    check_true(connection.writes().back() == std::vector<uint8_t>({0xC5}), "set_gain");

    // set_power_down(0, 1, 2, 3) -> byte1=0xA2, byte2=0x58.
    dac.set_power_down(0, 1, 2, 3);
    check_true(connection.writes().back() == std::vector<uint8_t>({0xA2, 0x58}), "set_power_down");

    // read(): 24-byte response, no register-select write (plain read()).
    // Channel A input: vref=0,pd=0,gain=0,code=0x123 -> byte1=0x01, byte2=0x23.
    // Channel A EEPROM: vref=1,pd=0,gain=1,code=0x0AB -> byte1=0x90, byte2=0xAB.
    connection.queueRead({0x80, 0x01, 0x23, 0,0,0,0,0,0,0,0,0,
                           0, 0x90, 0xAB, 0,0,0,0,0,0,0,0,0});
    MCP4728Full::ReadResult r = dac.read();
    check_true(r.eeprom_ready == true, "read_eeprom_ready");
    check_true(r.channel[0].code == 0x123, "read_ch_a_code");
    check_true(r.channel[0].vref == 0, "read_ch_a_vref");
    check_true(r.channel[0].gain == 1, "read_ch_a_gain");
    check_true(r.channel[0].power_down == 0, "read_ch_a_power_down");
    check_true(r.channel[0].eeprom_code == 0xAB, "read_ch_a_eeprom_code");
    check_true(r.channel[0].eeprom_vref == 1, "read_ch_a_eeprom_vref");
    check_true(r.channel[0].eeprom_gain == 2, "read_ch_a_eeprom_gain");

    connection.queueRead({0x80});
    check_true(dac.is_eeprom_ready() == true, "is_eeprom_ready_true");
    connection.queueRead({0x00});
    check_true(dac.is_eeprom_ready() == false, "is_eeprom_ready_false");

    // software_update()/wake_up()/reset(): General Call commands.
    dac.software_update();
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00, 0x08}), "software_update");
    dac.wake_up();
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00, 0x09}), "wake_up");
    dac.reset();
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00, 0x06}), "reset");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
