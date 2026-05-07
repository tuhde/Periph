#pragma once
#include <stdint.h>
#include "../../transport/Transport.h"

class PCF8576Minimal {
public:
    PCF8576Minimal(Transport& transport, uint8_t address = 0x38);

    void clear();
    void write_raw(uint8_t address, const uint8_t* data, size_t len);
    void set_digit_7seg(uint8_t position, uint8_t segments);

    static const uint8_t SEG_7SEG[11];

protected:
    static constexpr uint8_t CMD_MODE_SET = 0x80;
    static constexpr uint8_t CMD_LOAD_DP  = 0x20;
    static constexpr uint8_t CMD_DEV_SEL  = 0x40;

    Transport& _transport;
    uint8_t _address;
    uint8_t _subaddress;

    void _write_cmd(const uint8_t* data, size_t len);
};

class PCF8576Full : public PCF8576Minimal {
public:
    static constexpr uint8_t MUX_STATIC = 0;
    static constexpr uint8_t MUX_1_2    = 1;
    static constexpr uint8_t MUX_1_3    = 3;
    static constexpr uint8_t MUX_1_4    = 2;

    static constexpr uint8_t BLINK_OFF  = 0;
    static constexpr uint8_t BLINK_2HZ  = 1;
    static constexpr uint8_t BLINK_1HZ  = 2;
    static constexpr uint8_t BLINK_05HZ = 3;

    PCF8576Full(Transport& transport, uint8_t address = 0x38);

    void enable();
    void disable();
    void set_mode(uint8_t backplanes, uint8_t bias = 0);
    void set_blink(uint8_t frequency, bool alternate_bank = false);
    void set_bank(uint8_t input_bank, uint8_t output_bank);
    void device_select(uint8_t subaddress);

private:
    static constexpr uint8_t CMD_BANK  = 0x60;
    static constexpr uint8_t CMD_BLINK = 0x70;

    uint8_t _build_mode_set(uint8_t backplanes, uint8_t bias, bool enable);
};