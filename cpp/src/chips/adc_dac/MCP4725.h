#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

class _MCP47xxBase {
protected:
    static constexpr uint8_t  GENERAL_CALL_ADDR  = 0x00;
    static constexpr uint8_t  GEN_CALL_RESET      = 0x06;
    static constexpr uint8_t  GEN_CALL_WAKEUP     = 0x09;

    _MCP47xxBase(Transport& transport) : _transport(transport) {}

    void wake_up() {
        uint8_t buf[2] = { GENERAL_CALL_ADDR, GEN_CALL_WAKEUP };
        _transport.write(buf, 2);
    }

    void reset() {
        uint8_t buf[2] = { GENERAL_CALL_ADDR, GEN_CALL_RESET };
        _transport.write(buf, 2);
    }

    bool is_eeprom_ready() {
        uint8_t cmd[1] = {0x00};
        uint8_t raw[1] = {0};
        _transport.write_read(cmd, 1, raw, 1);
        return (raw[0] & 0x80) != 0;
    }

    Transport& _transport;
};

class MCP4725Minimal : protected _MCP47xxBase {
public:
    MCP4725Minimal(Transport& transport);

    void set_voltage(float fraction);
    void set_raw(uint16_t code);

protected:
    static constexpr uint8_t  FAST_WRITE      = 0x00;
    static constexpr uint8_t  WRITE_DAC       = 0x40;
    static constexpr uint8_t  WRITE_DAC_EEPROM = 0x60;
};

struct MCP4725ReadResult {
    uint16_t code;
    float voltage_fraction;
    uint8_t power_down;
    uint16_t eeprom_code;
    uint8_t eeprom_power_down;
    bool eeprom_ready;
    bool por;
};

class MCP4725Full : public MCP4725Minimal {
public:
    MCP4725Full(Transport& transport);

    void set_voltage_eeprom(float fraction);
    void set_raw_eeprom(uint16_t code);
    MCP4725ReadResult read();
    void set_power_down(uint8_t mode);

    using _MCP47xxBase::wake_up;
    using _MCP47xxBase::reset;
    using _MCP47xxBase::is_eeprom_ready;
};