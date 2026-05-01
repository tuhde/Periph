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

class INA226Full : public INA226Minimal {
public:
    static constexpr uint16_t SOL  = 0x8000;
    static constexpr uint16_t SUL  = 0x4000;
    static constexpr uint16_t BOL  = 0x2000;
    static constexpr uint16_t BUL  = 0x1000;
    static constexpr uint16_t POL  = 0x0800;
    static constexpr uint16_t CNVR = 0x0400;

    INA226Full(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    void     configure(uint8_t avg = 0, uint8_t vbus_ct = 4, uint8_t vsh_ct = 4, uint8_t mode = 7);
    bool     conversion_ready();
    bool     overflow();
    void     set_alert(uint16_t function, float limit = 0.0f, bool polarity = false, bool latch = false);
    uint16_t alert_flags();
    void     reset();
    void     shutdown();
    void     wake();
    uint16_t manufacturer_id();
    uint16_t die_id();

private:
    static constexpr uint8_t REG_MASK   = 0x06;
    static constexpr uint8_t REG_ALERT  = 0x07;
    static constexpr uint8_t REG_MFR_ID = 0xFE;
    static constexpr uint8_t REG_DIE_ID = 0xFF;

    uint8_t _mode = 0x07;
};
