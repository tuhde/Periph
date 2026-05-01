#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

class INA226Minimal {
public:
    INA226Minimal(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    float voltage();
    float shunt_voltage();
    float current();
    float power();

protected:
    static constexpr uint8_t  REG_CONFIG      = 0x00;
    static constexpr uint8_t  REG_SHUNT       = 0x01;
    static constexpr uint8_t  REG_BUS         = 0x02;
    static constexpr uint8_t  REG_POWER       = 0x03;
    static constexpr uint8_t  REG_CURRENT     = 0x04;
    static constexpr uint8_t  REG_CAL         = 0x05;
    static constexpr uint16_t CONFIG_DEFAULT  = 0x4127;

    Transport& _transport;
    float      _current_lsb;
    uint16_t   _cal;

    void     _write_reg(uint8_t reg, uint16_t value);
    uint16_t _read_reg(uint8_t reg);
    int16_t  _read_reg_signed(uint8_t reg);
};
