#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

class INA219Minimal {
public:
    INA219Minimal(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

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

    Transport& _transport;
    float      _current_lsb;
    uint16_t   _cal;

    void     _write_reg(uint8_t reg, uint16_t value);
    uint16_t _read_reg(uint8_t reg);
    int16_t  _read_reg_signed(uint8_t reg);
};

class INA219Full : public INA219Minimal {
public:
    static constexpr uint8_t PGA_1  = 0;
    static constexpr uint8_t PGA_2  = 1;
    static constexpr uint8_t PGA_4  = 2;
    static constexpr uint8_t PGA_8  = 3;

    static constexpr uint8_t BRNG_16V = 0;
    static constexpr uint8_t BRNG_32V = 1;

    static constexpr uint8_t ADC_9BIT      = 0x00;
    static constexpr uint8_t ADC_10BIT     = 0x01;
    static constexpr uint8_t ADC_11BIT     = 0x02;
    static constexpr uint8_t ADC_12BIT     = 0x03;
    static constexpr uint8_t ADC_AVG_2     = 0x09;
    static constexpr uint8_t ADC_AVG_4     = 0x0A;
    static constexpr uint8_t ADC_AVG_8     = 0x0B;
    static constexpr uint8_t ADC_AVG_16    = 0x0C;
    static constexpr uint8_t ADC_AVG_32    = 0x0D;
    static constexpr uint8_t ADC_AVG_64    = 0x0E;
    static constexpr uint8_t ADC_AVG_128   = 0x0F;

    static constexpr uint8_t MODE_POWERDOWN       = 0;
    static constexpr uint8_t MODE_SHUNT_TRIG      = 1;
    static constexpr uint8_t MODE_BUS_TRIG        = 2;
    static constexpr uint8_t MODE_SHUNT_BUS_TRIG  = 3;
    static constexpr uint8_t MODE_ADC_OFF         = 4;
    static constexpr uint8_t MODE_SHUNT_CONT      = 5;
    static constexpr uint8_t MODE_BUS_CONT        = 6;
    static constexpr uint8_t MODE_SHUNT_BUS_CONT  = 7;

    INA219Full(Transport& transport, float r_shunt = 0.1f, float max_current = 2.0f);

    void     configure(uint8_t brng = 1, uint8_t pga = 3, uint8_t badc = 0x03, uint8_t sadc = 0x03, uint8_t mode = 7);
    bool     conversion_ready();
    bool     overflow();
    void     reset();
    void     shutdown();
    void     wake();
    void     trigger();

private:
    uint8_t  _mode = MODE_SHUNT_BUS_CONT;
    uint16_t _config = 0xFFFFu;
};
