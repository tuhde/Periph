#include <stdio.h>
#include <math.h>
#include "I2CConnectionMock.h"
#include "MCP4725.h"

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\n", label); passed++; }
    else       { printf("FAIL %s\n", label); failed++; }
}

int main() {
    I2CConnectionMock connection;
    MCP4725Full dac(connection);
    check_true(true, "init");

    // set_voltage(0.5) -> code=2048 (0x800), PD=00. Fast Write byte1=0x08, byte2=0x00.
    dac.set_voltage(0.5f);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x08, 0x00}), "set_voltage");

    // set_voltage clamps to [0.0, 1.0].
    dac.set_voltage(2.0f);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x0F, 0xFF}), "set_voltage_clamps_high");
    dac.set_voltage(-1.0f);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00, 0x00}), "set_voltage_clamps_low");

    // set_raw(4095) -> byte1=0x0F, byte2=0xFF.
    dac.set_raw(4095);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x0F, 0xFF}), "set_raw");

    // set_raw clamps to [0, 4095].
    dac.set_raw(5000);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x0F, 0xFF}), "set_raw_clamps");

    // set_voltage_eeprom(0.5) -> code=2048. Write DAC+EEPROM: byte1=0x60, byte2=0x80, byte3=0x00.
    dac.set_voltage_eeprom(0.5f);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x60, 0x80, 0x00}), "set_voltage_eeprom");

    // set_raw_eeprom(4095) -> byte2=0xFF, byte3=0xF0.
    dac.set_raw_eeprom(4095);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x60, 0xFF, 0xF0}), "set_raw_eeprom");

    // read(): rdy_bsy=1, por=1, pd_dac=2, code=0x123, eeprom byte4=0x40
    // (0100_0000) -> PD1:PD0 at bits 6:5 = 2, eeprom_code=0xAB.
    connection.setRegister(0x00, {0xC8, 0x12, 0x30, 0x40, 0xAB});
    MCP4725Full::ReadResult r = dac.read();
    check_true(r.code == 0x123, "read_code");
    check_true(fabsf(r.voltage_fraction - (0x123 / 4095.0f)) < 1e-6f, "read_voltage_fraction");
    check_true(r.power_down == 2, "read_power_down");
    check_true(r.eeprom_code == 0xAB, "read_eeprom_code");
    check_true(r.eeprom_power_down == 2, "read_eeprom_power_down");
    check_true(r.eeprom_ready == true, "read_eeprom_ready");

    // set_power_down(2): reads current 2-byte DAC code (0x0AB), Fast Writes with PD=2.
    connection.setRegister(0x00, {0x00, 0xAB});
    dac.set_power_down(2);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x20, 0xAB}), "set_power_down");

    // set_power_down clamps mode to [0, 3].
    connection.setRegister(0x00, {0x00, 0x00});
    dac.set_power_down(9);
    check_true(connection.writes().back() == std::vector<uint8_t>({0x30, 0x00}), "set_power_down_clamps");

    // wake_up() / reset(): General Call commands.
    dac.wake_up();
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00, 0x09}), "wake_up");
    dac.reset();
    check_true(connection.writes().back() == std::vector<uint8_t>({0x00, 0x06}), "reset");

    // is_eeprom_ready(): RDY/BSY bit.
    connection.setRegister(0x00, {0x80});
    check_true(dac.is_eeprom_ready() == true, "is_eeprom_ready_true");
    connection.setRegister(0x00, {0x00});
    check_true(dac.is_eeprom_ready() == false, "is_eeprom_ready_false");

    printf("===DONE: %d passed, %d failed===\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
