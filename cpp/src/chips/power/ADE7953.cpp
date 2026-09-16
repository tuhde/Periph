#include "ADE7953.h"

namespace {
    constexpr uint8_t  UART_CMD_READ  = 0x35;
    constexpr uint8_t  UART_CMD_WRITE = 0xCA;

    // IRQ source bit positions (channel A group).
    constexpr uint32_t AEHFA       = 1u << 0;
    constexpr uint32_t VAREHFA     = 1u << 1;
    constexpr uint32_t VAEHFA      = 1u << 2;
    constexpr uint32_t AEOFA       = 1u << 3;
    constexpr uint32_t VAREOFA     = 1u << 4;
    constexpr uint32_t VAEOFA      = 1u << 5;
    constexpr uint32_t AP_NOLOADA  = 1u << 6;
    constexpr uint32_t VAR_NOLOADA = 1u << 7;
    constexpr uint32_t VA_NOLOADA  = 1u << 8;
    constexpr uint32_t APSIGN_A    = 1u << 9;
    constexpr uint32_t VARSIGN_A   = 1u << 10;
    constexpr uint32_t ZXTO_IA     = 1u << 11;
    constexpr uint32_t ZXIA        = 1u << 12;
    constexpr uint32_t OIA         = 1u << 13;
    constexpr uint32_t ZXTO        = 1u << 14;
    constexpr uint32_t ZXV         = 1u << 15;
    constexpr uint32_t OV          = 1u << 16;
    constexpr uint32_t WSMP        = 1u << 17;
    constexpr uint32_t CYCEND      = 1u << 18;
    constexpr uint32_t SAG_BIT     = 1u << 19;
    constexpr uint32_t RESET_BIT   = 1u << 20;
    constexpr uint32_t CRC_BIT     = 1u << 21;
}


void ADE7953Minimal::_delayMs(uint32_t ms) {
    if (ms == 0) return;
    // Busy-wait ~ms milliseconds; portable across bare-metal targets.
    // 1 ms at >=1 MHz clock with no optimisation: ~1000 iterations.
    volatile uint32_t count = ms * 1000u;
    while (count--) { __asm__ volatile("nop"); }
}


void ADE7953Minimal::_initChip() {
    _delayMs(110);
    _writeU8(REG_INTERNAL_RES, REG_120_UNLOCK);
    _writeU16(REG_INTERNAL_RES, REG_120_VALUE);
}


void ADE7953Minimal::_read(uint16_t reg, uint8_t n, uint8_t* buf) {
    uint8_t tx[4];
    uint8_t tx_len = 0;
    uint8_t rx_skip = 0;

    if (_bus_type == BUS_SPI) {
        tx[0] = (reg >> 8) & 0x7F;
        tx[1] = (reg & 0xFF) | 0x80;  // READ bit
        tx[2] = 0x00;
        tx[3] = 0x00;
        tx_len = 4;
        rx_skip = 2;
    } else if (_bus_type == BUS_UART) {
        tx[0] = UART_CMD_READ;
        tx[1] = (reg >> 8) & 0xFF;
        tx[2] = (reg & 0xFF);
        tx_len = 3;
    } else {
        tx[0] = (reg >> 8) & 0xFF;
        tx[1] = (reg & 0xFF);
        tx_len = 2;
    }

    if (_bus_type == BUS_UART) {
        _connection.write(tx, tx_len);
        _delayMs(1);
        uint8_t tmp[4];
        if (n > 4) n = 4;
        _connection.read(tmp, n);
        // UART data is LSB-first.
        for (uint8_t i = 0; i < n; ++i) buf[i] = tmp[n - 1 - i];
    } else {
        uint8_t tmp[8];
        if (n > 8) n = 8;
        _connection.write_read(tx, tx_len, tmp, n);
        for (uint8_t i = 0; i < n; ++i) buf[i] = tmp[i + rx_skip];
    }
}


void ADE7953Minimal::_write(uint16_t reg, const uint8_t* payload, uint8_t n) {
    if (n > 4) n = 4;
    uint8_t frame[8];
    uint8_t frame_len = 0;

    if (_bus_type == BUS_UART) {
        frame[0] = UART_CMD_WRITE;
        frame[1] = (reg >> 8) & 0xFF;
        frame[2] = (reg & 0xFF);
        // UART data is LSB-first.
        for (uint8_t i = 0; i < n; ++i) frame[3 + i] = payload[n - 1 - i];
        frame_len = 3 + n;
    } else {
        frame[0] = (reg >> 8) & 0xFF;
        frame[1] = (reg & 0xFF);
        memcpy(&frame[2], payload, n);
        frame_len = 2 + n;
    }
    _connection.write(frame, frame_len);
}


void ADE7953Minimal::_writeU8(uint16_t reg, uint8_t value) {
    _write(reg, &value, 1);
}


void ADE7953Minimal::_writeU16(uint16_t reg, uint16_t value) {
    uint8_t payload[2];
    payload[0] = (value >> 8) & 0xFF;
    payload[1] = (value & 0xFF);
    _write(reg, payload, 2);
}


void ADE7953Minimal::_writeU24(uint16_t reg, uint32_t value) {
    uint8_t payload[3];
    payload[0] = (value >> 16) & 0xFF;
    payload[1] = (value >> 8) & 0xFF;
    payload[2] = (value & 0xFF);
    _write(reg, payload, 3);
}


void ADE7953Minimal::_writeU32(uint16_t reg, uint32_t value) {
    uint8_t payload[4];
    payload[0] = (value >> 24) & 0xFF;
    payload[1] = (value >> 16) & 0xFF;
    payload[2] = (value >> 8) & 0xFF;
    payload[3] = (value & 0xFF);
    _write(reg, payload, 4);
}


uint16_t ADE7953Minimal::_readU16(uint16_t reg) {
    uint8_t buf[2];
    _read(reg, 2, buf);
    return (uint16_t(buf[0]) << 8) | buf[1];
}


int16_t ADE7953Minimal::_readS16(uint16_t reg) {
    uint16_t v = _readU16(reg);
    if (v & 0x8000) return int16_t(v) - 0x10000;
    return int16_t(v);
}


uint32_t ADE7953Minimal::_readU24(uint16_t reg) {
    uint8_t buf[3];
    _read(reg, 3, buf);
    return (uint32_t(buf[0]) << 16) | (uint32_t(buf[1]) << 8) | uint32_t(buf[2]);
}


int32_t ADE7953Minimal::_readS24(uint16_t reg) {
    uint32_t v = _readU24(reg);
    if (v & 0x800000u) return int32_t(v) - 0x1000000;
    return int32_t(v);
}


uint32_t ADE7953Minimal::_readU32(uint16_t reg) {
    uint8_t buf[4];
    _read(reg, 4, buf);
    return (uint32_t(buf[0]) << 24) | (uint32_t(buf[1]) << 16) |
           (uint32_t(buf[2]) << 8) | uint32_t(buf[3]);
}


float ADE7953Minimal::_voltageScale() const {
    return (ADC_FS_VOLTS * _voltage_gain) / float(ADC_FS_CODE * _pga_v);
}


float ADE7953Minimal::_currentScale(float gain) const {
    return (ADC_FS_VOLTS * gain) / float(ADC_FS_CODE);
}


float ADE7953Minimal::_powerScale(float gain) const {
    return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * _voltage_gain * gain) / float(POWER_FS_CODE);
}


float ADE7953Minimal::_energyScale(float gain) const {
    return ((ADC_FS_VOLTS * ADC_FS_VOLTS) * _voltage_gain * gain * T_SAMPLE) / 3600.0f;
}


ADE7953Minimal::ADE7953Minimal(Connection& connection,
                               float voltage_gain,
                               float current_gain,
                               uint8_t bus_type)
    : _connection(connection),
      _bus_type(bus_type),
      _voltage_gain(voltage_gain),
      _current_gain_a(current_gain),
      _current_gain_b(current_gain),
      _pga_a(1),
      _pga_b(1),
      _pga_v(1) {
    _initChip();
}


float ADE7953Minimal::voltage() {
    return float(_readU24(REG_VRMS)) * _voltageScale();
}


float ADE7953Minimal::current() {
    return float(_readU24(0x21A)) * _currentScale(_current_gain_a);
}


float ADE7953Minimal::activePower() {
    return float(_readS24(REG_AWATT)) * _powerScale(_current_gain_a);
}


float ADE7953Minimal::activeEnergy() {
    return float(_readS24(REG_AENERGYA)) * _energyScale(_current_gain_a);
}


// ----------------------------------------------------------------------------
// Full interface


ADE7953Full::ADE7953Full(Connection& connection,
                         float voltage_gain,
                         float current_gain,
                         uint8_t bus_type)
    : ADE7953Minimal(connection, voltage_gain, current_gain, bus_type) {}


void ADE7953Full::configureChannelB(float current_gain_b) {
    _current_gain_b = current_gain_b;
}


float ADE7953Full::currentB() {
    return float(_readU24(0x21B)) * _currentScale(_current_gain_b);
}


float ADE7953Full::activePowerB() {
    return float(_readS24(0x213)) * _powerScale(_current_gain_b);
}


float ADE7953Full::activeEnergyB() {
    return float(_readS24(0x21F)) * _energyScale(_current_gain_b);
}


float ADE7953Full::reactivePower() {
    return float(_readS24(0x214)) * _powerScale(_current_gain_a);
}


float ADE7953Full::reactivePowerB() {
    return float(_readS24(0x215)) * _powerScale(_current_gain_b);
}


float ADE7953Full::reactiveEnergy() {
    return float(_readS24(0x220)) * _energyScale(_current_gain_a);
}


float ADE7953Full::reactiveEnergyB() {
    return float(_readS24(0x221)) * _energyScale(_current_gain_b);
}


float ADE7953Full::apparentPower() {
    return float(_readS24(0x210)) * _powerScale(_current_gain_a);
}


float ADE7953Full::apparentPowerB() {
    return float(_readS24(0x211)) * _powerScale(_current_gain_b);
}


float ADE7953Full::apparentEnergy() {
    return float(_readS24(0x222)) * _energyScale(_current_gain_a);
}


float ADE7953Full::apparentEnergyB() {
    return float(_readS24(0x223)) * _energyScale(_current_gain_b);
}


float ADE7953Full::powerFactor() {
    return float(_readS16(REG_PFA)) * PF_LSB;
}


float ADE7953Full::linePeriod() {
    return float(_readU16(REG_PERIOD) + 1) * ANGLE_LSB;
}


float ADE7953Full::lineFrequency() {
    return 1.0f / linePeriod();
}


void ADE7953Full::setPga(char channel, uint8_t gain) {
    uint8_t bits = 0;
    switch (gain) {
        case 1: bits = 0; break;
        case 2: bits = 1; break;
        case 4: bits = 2; break;
        case 8: bits = 3; break;
        case 16: bits = 4; break;
        case 22: bits = 5; break;
        default: return;
    }
    if (channel == 'a') { _writeU8(REG_PGA_IA, bits); _pga_a = gain; }
    else if (channel == 'b') { _writeU8(REG_PGA_IB, bits); _pga_b = gain; }
    else if (channel == 'v') { _writeU8(REG_PGA_V, bits); _pga_v = gain; }
}


void ADE7953Full::setPhaseCalibration(char channel, float delay_s) {
    uint32_t mag = (uint32_t)((delay_s < 0 ? -delay_s : delay_s) / PHASE_LSB + 0.5f);
    if (mag > 0x1FF) mag = 0x1FF;
    uint16_t raw = (uint16_t)(mag & 0x1FF);
    if (delay_s >= 0) raw |= 0x200;
    if (channel == 'a') _writeU16(REG_PHCALA, raw);
    else if (channel == 'b') _writeU16(REG_PHCALB, raw);
}


void ADE7953Full::setGainCalibration(uint16_t reg, uint32_t value) {
    _writeU24(reg, value & 0xFFFFFFu);
}


uint32_t ADE7953Full::gainCalibration(uint16_t reg) {
    return _readU24(reg);
}


void ADE7953Full::setOffsetCalibration(uint16_t reg, int32_t value) {
    _writeU24(reg, uint32_t(value) & 0xFFFFFFu);
}


int32_t ADE7953Full::offsetCalibration(uint16_t reg) {
    return _readS24(reg);
}


uint32_t ADE7953Full::checksum() {
    return _readU32(REG_CRC);
}


void ADE7953Full::enableChecksum(bool enabled) {
    uint16_t cfg = _readU16(REG_CONFIG);
    if (enabled) cfg |= (1u << 8);
    else cfg &= ~(1u << 8);
    _writeU16(REG_CONFIG, cfg);
}


void ADE7953Full::configureOvervoltage(float threshold) {
    float raw_f = (threshold * float(ADC_FS_CODE * _pga_v)) /
                  (ADC_FS_VOLTS * _voltage_gain);
    if (raw_f < 0) raw_f = 0;
    if (raw_f > float(0xFFFFFF)) raw_f = float(0xFFFFFF);
    _writeU24(REG_OVLVL, uint32_t(raw_f));
}


void ADE7953Full::configureOvercurrent(float threshold) {
    float raw_f = (threshold * float(ADC_FS_CODE)) / ADC_FS_VOLTS;
    if (raw_f < 0) raw_f = 0;
    if (raw_f > float(0xFFFFFF)) raw_f = float(0xFFFFFF);
    _writeU24(REG_OILVL, uint32_t(raw_f));
}


void ADE7953Full::reset() {
    uint16_t cfg = _readU16(REG_CONFIG);
    cfg |= (1u << 7);
    _writeU16(REG_CONFIG, cfg);
    _delayMs(110);
    _writeU8(REG_INTERNAL_RES, REG_120_UNLOCK);
    _writeU16(REG_INTERNAL_RES, REG_120_VALUE);
    _pga_a = 1;
    _pga_b = 1;
    _pga_v = 1;
}


uint8_t ADE7953Full::version() {
    uint8_t v = 0;
    _read(REG_VERSION, 1, &v);
    return v;
}


void ADE7953Full::setActiveEnergyMode(char channel, uint8_t mode) {
    uint32_t acc = _readU24(REG_ACCMODE);
    if (channel == 'a') {
        acc = (acc & ~0x3u) | (mode & 0x3);
    } else {
        acc = (acc & ~(0x3u << 2)) | ((uint32_t(mode) & 0x3) << 2);
    }
    _writeU24(REG_ACCMODE, acc);
}


void ADE7953Full::setReactiveEnergyMode(char channel, uint8_t mode) {
    uint32_t acc = _readU24(REG_ACCMODE);
    if (channel == 'a') {
        acc = (acc & ~(0x3u << 4)) | ((uint32_t(mode) & 0x3) << 4);
    } else {
        acc = (acc & ~(0x3u << 6)) | ((uint32_t(mode) & 0x3) << 6);
    }
    _writeU24(REG_ACCMODE, acc);
}