#include "INA3221.h"

INA3221Minimal::INA3221Minimal(Transport& transport, float r_shunt)
    : _transport(transport) {
    _r_shunt[0] = r_shunt;
    _r_shunt[1] = r_shunt;
    _r_shunt[2] = r_shunt;
}

INA3221Minimal::INA3221Minimal(Transport& transport, const float r_shunt[3])
    : _transport(transport) {
    _r_shunt[0] = r_shunt[0];
    _r_shunt[1] = r_shunt[1];
    _r_shunt[2] = r_shunt[2];
}

uint8_t INA3221Minimal::_channel_valid(uint8_t channel) {
    if (channel < 1 || channel > 3) {
        return 1;
    }
    return channel;
}

void INA3221Minimal::_write_reg(uint8_t reg, uint16_t value) {
    uint8_t buf[3] = { reg, (uint8_t)(value >> 8), (uint8_t)(value & 0xFF) };
    _transport.write(buf, 3);
}

uint16_t INA3221Minimal::_read_reg(uint8_t reg) {
    uint8_t buf[2];
    _transport.write_read(&reg, 1, buf, 2);
    return ((uint16_t)buf[0] << 8) | buf[1];
}

int16_t INA3221Minimal::_read_reg_signed(uint8_t reg) {
    return static_cast<int16_t>(_read_reg(reg));
}

float INA3221Minimal::voltage(uint8_t channel) {
    uint8_t ch = _channel_valid(channel);
    uint16_t raw = _read_reg(BUS_REGS[ch - 1]);
    return (raw >> 3) * 8e-3f;
}

float INA3221Minimal::shunt_voltage(uint8_t channel) {
    uint8_t ch = _channel_valid(channel);
    int16_t raw = _read_reg_signed(SHUNT_REGS[ch - 1]);
    return raw * 5e-6f;
}

float INA3221Minimal::current(uint8_t channel) {
    uint8_t ch = _channel_valid(channel);
    return shunt_voltage(ch) / _r_shunt[ch - 1];
}

float INA3221Minimal::power(uint8_t channel) {
    uint8_t ch = _channel_valid(channel);
    return voltage(ch) * current(ch);
}

// INA3221Full

INA3221Full::INA3221Full(Transport& transport, float r_shunt)
    : INA3221Minimal(transport, r_shunt) {}

INA3221Full::INA3221Full(Transport& transport, const float r_shunt[3])
    : INA3221Minimal(transport, r_shunt) {}

void INA3221Full::configure(uint8_t avg, uint8_t vbus_ct, uint8_t vsh_ct, uint8_t mode) {
    uint16_t cfg = _read_reg(REG_CONFIG);
    uint16_t config = ((uint16_t)(avg & 0x07) << 9)
                    | ((uint16_t)(vbus_ct & 0x07) << 6)
                    | ((uint16_t)(vsh_ct & 0x07) << 3)
                    | (mode & 0x07);
    config |= (cfg & 0x7000);
    _mode = mode & 0x07;
    _write_reg(REG_CONFIG, config);
}

void INA3221Full::enable_channel(uint8_t channel, bool enabled) {
    uint8_t ch = _channel_valid(channel);
    uint16_t cfg = _read_reg(REG_CONFIG);
    uint8_t bit = 14 - (ch - 1);
    if (enabled) {
        cfg |= (1u << bit);
    } else {
        cfg &= ~(1u << bit);
    }
    _write_reg(REG_CONFIG, cfg);
}

bool INA3221Full::channel_enabled(uint8_t channel) {
    uint8_t ch = _channel_valid(channel);
    uint16_t cfg = _read_reg(REG_CONFIG);
    uint8_t bit = 14 - (ch - 1);
    return (cfg & (1u << bit)) != 0;
}

bool INA3221Full::conversion_ready() {
    return (_read_reg(REG_MASK_EN) & CVRF) != 0;
}

void INA3221Full::set_critical_alert(uint8_t channel, float limit_v, bool latch) {
    uint8_t ch = _channel_valid(channel);
    uint16_t raw = (uint16_t)((int)(limit_v / 40e-6f) << 3) & 0xFFF8u;
    _write_reg(CRIT_REGS[ch - 1], raw);
    uint16_t cfg = _read_reg(REG_MASK_EN);
    if (latch) {
        cfg |= 0x0400u;
    } else {
        cfg &= ~0x0400u;
    }
    _write_reg(REG_MASK_EN, cfg);
}

void INA3221Full::set_warning_alert(uint8_t channel, float limit_v, bool latch) {
    uint8_t ch = _channel_valid(channel);
    uint16_t raw = (uint16_t)((int)(limit_v / 40e-6f) << 3) & 0xFFF8u;
    _write_reg(WARN_REGS[ch - 1], raw);
    uint16_t cfg = _read_reg(REG_MASK_EN);
    if (latch) {
        cfg |= 0x0800u;
    } else {
        cfg &= ~0x0800u;
    }
    _write_reg(REG_MASK_EN, cfg);
}

uint16_t INA3221Full::alert_flags() {
    return _read_reg(REG_MASK_EN);
}

void INA3221Full::set_summation_channels(const uint8_t* channels, uint8_t n, float limit_v) {
    uint16_t cfg = _read_reg(REG_MASK_EN);
    cfg &= ~0xE000u;
    for (uint8_t k = 0; k < n; k++) {
        uint8_t ch = _channel_valid(channels[k]);
        cfg |= 1u << (15 - (ch - 1));
    }
    _write_reg(REG_MASK_EN, cfg);
    uint16_t raw = (uint16_t)((int)(limit_v / 40e-6f) << 1) & 0xFFFEu;
    _write_reg(REG_SUM_LIMIT, raw);
}

float INA3221Full::summation_value() {
    int16_t raw = _read_reg_signed(REG_SUM);
    return raw * 5e-6f;
}

void INA3221Full::set_power_valid_limits(float upper_v, float lower_v) {
    uint16_t raw_upper = (uint16_t)((int)(upper_v / 8e-3f) << 3) & 0xFFF8u;
    uint16_t raw_lower = (uint16_t)((int)(lower_v / 8e-3f) << 3) & 0xFFF8u;
    _write_reg(REG_PV_UPPER, raw_upper);
    _write_reg(REG_PV_LOWER, raw_lower);
}

bool INA3221Full::power_valid() {
    return (_read_reg(REG_MASK_EN) & PVF) != 0;
}

void INA3221Full::shutdown() {
    uint16_t cfg = _read_reg(REG_CONFIG);
    _mode = cfg & 0x07;
    _write_reg(REG_CONFIG, cfg & 0xFFF8u);
}

void INA3221Full::wake() {
    uint16_t cfg = _read_reg(REG_CONFIG);
    _write_reg(REG_CONFIG, (cfg & 0xFFF8u) | _mode);
}

void INA3221Full::reset() {
    _write_reg(REG_CONFIG, 0x8000u);
}

uint16_t INA3221Full::manufacturer_id() {
    return _read_reg(REG_MFR_ID);
}

uint16_t INA3221Full::die_id() {
    return _read_reg(REG_DIE_ID);
}