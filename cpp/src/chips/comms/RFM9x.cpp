#include "RFM9x.h"
#include <string.h>

// Helper functions
static uint8_t _map_bw(uint8_t bandwidth_khz) {
    if (bandwidth_khz <= 7) return 0x00;
    if (bandwidth_khz <= 10) return 0x01;
    if (bandwidth_khz <= 15) return 0x02;
    if (bandwidth_khz <= 20) return 0x03;
    if (bandwidth_khz <= 31) return 0x04;
    if (bandwidth_khz <= 41) return 0x05;
    if (bandwidth_khz <= 62) return 0x06;
    if (bandwidth_khz <= 125) return 0x07;
    if (bandwidth_khz <= 250) return 0x08;
    return 0x09;
}

static uint8_t _map_cr(uint8_t coding_rate) {
    if (coding_rate == 5) return 1;
    if (coding_rate == 6) return 2;
    if (coding_rate == 7) return 3;
    return 4;
}

static void _delay_us(uint32_t us) {
    for (volatile uint32_t i = 0; i < us * 10; i++) { (void)i; }
}

static void _dummy_digital_write(uint8_t pin, uint8_t value) { (void)pin; (void)value; }
static void _dummy_pin_mode(uint8_t pin, uint8_t mode) { (void)pin; (void)mode; }

// ----- RFM95Minimal -----

RFM95Minimal::RFM95Minimal(Transport& transport, uint32_t frequency_hz)
    : _transport(transport), _frequency_hz(frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) { return; }
    _init();
}

void RFM95Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { static_cast<uint8_t>((reg & 0x7F) | 0x80), value };
    _transport.write(buf, 2);
}

void RFM95Minimal::_write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len) {
    uint8_t buf[256];
    buf[0] = static_cast<uint8_t>((reg & 0x7F) | 0x80);
    memcpy(buf + 1, data, len);
    _transport.write(buf, len + 1);
}

uint16_t RFM95Minimal::_read_reg(uint8_t reg) {
    uint8_t addr = reg & 0x7F;
    uint8_t buf[2] = { 0, 0 };
    _transport.write_read(&addr, 1, buf, 2);
    return (buf[0] << 8) | buf[1];
}

void RFM95Minimal::_set_mode(uint8_t mode) {
    uint8_t current = static_cast<uint8_t>(_read_reg(REG_OP_MODE));
    uint8_t new_val = (current & 0xF8) | (mode & 0x07);
    _write_reg(REG_OP_MODE, new_val);
}

void RFM95Minimal::_set_frequency(uint32_t frequency_hz) {
    uint32_t frf = (static_cast<uint64_t>(frequency_hz) * 524288) / FXOSC;
    _write_reg(REG_FRF_MSB, (frf >> 16) & 0xFF);
    _write_reg(REG_FRF_MID, (frf >> 8) & 0xFF);
    _write_reg(REG_FRF_LSB, frf & 0xFF);
}

void RFM95Minimal::_init() {
    _write_reg(REG_OP_MODE, 0x00);
    _write_reg(REG_OP_MODE, 0x80);
    _set_mode(MODE_SLEEP);
    _write_reg(REG_OP_MODE, 0x88);
    _write_reg(REG_FIFO_TX_BASE_ADDR, 0x80);
    _write_reg(REG_FIFO_RX_BASE_ADDR, 0x00);
    _write_reg(REG_LNA, 0x23);
    _write_reg(REG_MODEM_CONFIG3, 0x04);
    _set_frequency(_frequency_hz);
    _write_reg(REG_MODEM_CONFIG1, 0x72);
    _write_reg(REG_MODEM_CONFIG2, (7 << 4) | 0x04);
    _set_mode(MODE_STDBY);
}

bool RFM95Minimal::_poll_irq(uint8_t irq_mask, uint16_t timeout_ms) {
    for (uint16_t i = 0; i < timeout_ms; i++) {
        uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
        if (flags & irq_mask) return true;
        for (volatile uint32_t j = 0; j < 1000; j++) { }
    }
    return false;
}

void RFM95Minimal::send(const uint8_t* data, uint8_t len) {
    if (len > 255) return;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_ADDR_PTR, 0x80);
    uint8_t fifo_data[256];
    fifo_data[0] = len;
    memcpy(fifo_data + 1, data, len);
    _write_reg_burst(REG_FIFO, fifo_data, len + 1);
    _set_mode(MODE_TX);
    _poll_irq(IRQ_TX_DONE, 10000);
    _write_reg(REG_IRQ_FLAGS, IRQ_TX_DONE);
    _set_mode(MODE_STDBY);
}

uint8_t* RFM95Minimal::receive(uint16_t timeout_ms, uint8_t* out_len) {
    *out_len = 0;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_RX_CURRENT_ADDR, 0x00);
    _set_mode(MODE_RXSINGLE);
    if (!_poll_irq(IRQ_TX_DONE | IRQ_RX_TIMEOUT, timeout_ms)) {
        _set_mode(MODE_STDBY);
        return nullptr;
    }
    uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    if (flags & IRQ_RX_TIMEOUT) { _set_mode(MODE_STDBY); return nullptr; }
    if (!(flags & IRQ_RX_DONE)) { return nullptr; }
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    _set_mode(MODE_STDBY);
    return fifo_buf;
}

uint8_t RFM95Minimal::version() {
    return static_cast<uint8_t>(_read_reg(REG_VERSION));
}

void RFM95Minimal::standby() { _set_mode(MODE_STDBY); }
void RFM95Minimal::sleep() { _set_mode(MODE_SLEEP); }

// ----- RFM95Full -----

RFM95Full::RFM95Full(Transport& transport, uint32_t frequency_hz, uint8_t reset_pin, uint8_t dio0_pin)
    : RFM95Minimal(transport, frequency_hz), _reset_pin(reset_pin), _dio0_pin(dio0_pin) {
    if (reset_pin != 0) {
        _pin_mode(reset_pin, 1);
        _digital_write(reset_pin, 0);
        _delay_us(100);
        _digital_write(reset_pin, 1);
        _delay_us(5000);
    }
}

void RFM95Full::reset() {
    if (_reset_pin == 0) return;
    _digital_write(_reset_pin, 0);
    _delay_us(100);
    _digital_write(_reset_pin, 1);
    _delay_us(5000);
    _init();
}

void RFM95Full::configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc) {
    if (sf < 6 || sf > MAX_SF) return;
    _sf = sf;
    _crc = crc;
    _bw = bandwidth_khz;
    _cr = coding_rate;
    uint8_t bw_bits = _map_bw(bandwidth_khz);
    uint8_t cr_bits = _map_cr(coding_rate);
    _write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
    uint8_t implicit = (sf == 6) ? 1 : 0;
    _write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
    if (sf == 6) {
        _write_reg(0x31, 0x05);
        _write_reg(0x37, 0x0C);
    }
}

void RFM95Full::set_frequency(uint32_t frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) return;
    _frequency_hz = frequency_hz;
    _set_frequency(frequency_hz);
}

void RFM95Full::set_tx_power(int8_t power_dbm, bool use_pa_boost) {
    if (use_pa_boost) {
        if (power_dbm < 2 || power_dbm > 20) return;
        if (power_dbm > 17) {
            _write_reg(REG_PA_DAC, 0x87);
            _write_reg(REG_OCP, 0x3B);
        } else {
            _write_reg(REG_PA_DAC, 0x84);
            _write_reg(REG_OCP, 0x2B);
        }
        _write_reg(REG_PA_CONFIG, 0x80 | static_cast<uint8_t>((power_dbm - 2) & 0x0F));
    } else {
        if (power_dbm < -1 || power_dbm > 14) return;
        _write_reg(REG_PA_DAC, 0x84);
        _write_reg(REG_OCP, 0x2B);
        int8_t out_pwr = static_cast<int8_t>(power_dbm - 15 + 7);
        if (out_pwr < 0) out_pwr = 0;
        if (out_pwr > 15) out_pwr = 15;
        _write_reg(REG_PA_CONFIG, (7 << 4) | static_cast<uint8_t>(out_pwr & 0x0F));
    }
}

void RFM95Full::receive_continuous() {
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _set_mode(MODE_RXCONTINUOUS);
}

uint8_t* RFM95Full::read_packet(uint8_t* out_len) {
    *out_len = 0;
    if (!(_read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return nullptr;
    _write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    return fifo_buf;
}

void RFM95Full::stop_receive() { _set_mode(MODE_STDBY); }

float RFM95Full::rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM95Full::last_packet_rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_PKT_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM95Full::last_packet_snr() {
    int8_t s = static_cast<int8_t>(_read_reg(REG_PKT_SNR_VALUE));
    return s / 4.0f;
}

void RFM95Full::_delay_us(uint32_t us) { ::_delay_us(us); }
void RFM95Full::_digital_write(uint8_t pin, uint8_t value) { ::_dummy_digital_write(pin, value); }
void RFM95Full::_pin_mode(uint8_t pin, uint8_t mode) { ::_dummy_pin_mode(pin, mode); }

// ----- RFM96Minimal -----

RFM96Minimal::RFM96Minimal(Transport& transport, uint32_t frequency_hz)
    : _transport(transport), _frequency_hz(frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) { return; }
    _init();
}

void RFM96Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { static_cast<uint8_t>((reg & 0x7F) | 0x80), value };
    _transport.write(buf, 2);
}

void RFM96Minimal::_write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len) {
    uint8_t buf[256];
    buf[0] = static_cast<uint8_t>((reg & 0x7F) | 0x80);
    memcpy(buf + 1, data, len);
    _transport.write(buf, len + 1);
}

uint16_t RFM96Minimal::_read_reg(uint8_t reg) {
    uint8_t addr = reg & 0x7F;
    uint8_t buf[2] = { 0, 0 };
    _transport.write_read(&addr, 1, buf, 2);
    return (buf[0] << 8) | buf[1];
}

void RFM96Minimal::_set_mode(uint8_t mode) {
    uint8_t current = static_cast<uint8_t>(_read_reg(REG_OP_MODE));
    uint8_t new_val = (current & 0xF8) | (mode & 0x07);
    _write_reg(REG_OP_MODE, new_val);
}

void RFM96Minimal::_set_frequency(uint32_t frequency_hz) {
    uint32_t frf = (static_cast<uint64_t>(frequency_hz) * 524288) / FXOSC;
    _write_reg(REG_FRF_MSB, (frf >> 16) & 0xFF);
    _write_reg(REG_FRF_MID, (frf >> 8) & 0xFF);
    _write_reg(REG_FRF_LSB, frf & 0xFF);
}

void RFM96Minimal::_init() {
    _write_reg(REG_OP_MODE, 0x00);
    _write_reg(REG_OP_MODE, 0x80);
    _set_mode(MODE_SLEEP);
    _write_reg(REG_OP_MODE, 0x80);
    _write_reg(REG_FIFO_TX_BASE_ADDR, 0x80);
    _write_reg(REG_FIFO_RX_BASE_ADDR, 0x00);
    _write_reg(REG_MODEM_CONFIG3, 0x04);
    _set_frequency(_frequency_hz);
    _write_reg(REG_MODEM_CONFIG1, 0x72);
    _write_reg(REG_MODEM_CONFIG2, (7 << 4) | 0x04);
    _set_mode(MODE_STDBY);
}

bool RFM96Minimal::_poll_irq(uint8_t irq_mask, uint16_t timeout_ms) {
    for (uint16_t i = 0; i < timeout_ms; i++) {
        uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
        if (flags & irq_mask) return true;
        for (volatile uint32_t j = 0; j < 1000; j++) { }
    }
    return false;
}

void RFM96Minimal::send(const uint8_t* data, uint8_t len) {
    if (len > 255) return;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_ADDR_PTR, 0x80);
    uint8_t fifo_data[256];
    fifo_data[0] = len;
    memcpy(fifo_data + 1, data, len);
    _write_reg_burst(REG_FIFO, fifo_data, len + 1);
    _set_mode(MODE_TX);
    _poll_irq(IRQ_TX_DONE, 10000);
    _write_reg(REG_IRQ_FLAGS, IRQ_TX_DONE);
    _set_mode(MODE_STDBY);
}

uint8_t* RFM96Minimal::receive(uint16_t timeout_ms, uint8_t* out_len) {
    *out_len = 0;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_RX_CURRENT_ADDR, 0x00);
    _set_mode(MODE_RXSINGLE);
    if (!_poll_irq(IRQ_TX_DONE | IRQ_RX_TIMEOUT, timeout_ms)) {
        _set_mode(MODE_STDBY);
        return nullptr;
    }
    uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    if (flags & IRQ_RX_TIMEOUT) { _set_mode(MODE_STDBY); return nullptr; }
    if (!(flags & IRQ_RX_DONE)) { return nullptr; }
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    _set_mode(MODE_STDBY);
    return fifo_buf;
}

uint8_t RFM96Minimal::version() {
    return static_cast<uint8_t>(_read_reg(REG_VERSION));
}

void RFM96Minimal::standby() { _set_mode(MODE_STDBY); }
void RFM96Minimal::sleep() { _set_mode(MODE_SLEEP); }

// ----- RFM96Full -----

RFM96Full::RFM96Full(Transport& transport, uint32_t frequency_hz, uint8_t reset_pin, uint8_t dio0_pin)
    : RFM96Minimal(transport, frequency_hz), _reset_pin(reset_pin), _dio0_pin(dio0_pin) {
    if (reset_pin != 0) {
        _pin_mode(reset_pin, 1);
        _digital_write(reset_pin, 0);
        _delay_us(100);
        _digital_write(reset_pin, 1);
        _delay_us(5000);
    }
}

void RFM96Full::reset() {
    if (_reset_pin == 0) return;
    _digital_write(_reset_pin, 0);
    _delay_us(100);
    _digital_write(_reset_pin, 1);
    _delay_us(5000);
    _init();
}

void RFM96Full::configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc) {
    if (sf < 6 || sf > MAX_SF) return;
    if (IS_LF_BAND && bandwidth_khz > 62) return;
    _sf = sf;
    _crc = crc;
    _bw = bandwidth_khz;
    _cr = coding_rate;
    uint8_t bw_bits = _map_bw(bandwidth_khz);
    uint8_t cr_bits = _map_cr(coding_rate);
    _write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
    uint8_t implicit = (sf == 6) ? 1 : 0;
    _write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
    if (sf == 6) {
        _write_reg(0x31, 0x05);
        _write_reg(0x37, 0x0C);
    }
}

void RFM96Full::set_frequency(uint32_t frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) return;
    _frequency_hz = frequency_hz;
    _set_frequency(frequency_hz);
}

void RFM96Full::set_tx_power(int8_t power_dbm, bool use_pa_boost) {
    if (use_pa_boost) {
        if (power_dbm < 2 || power_dbm > 20) return;
        if (power_dbm > 17) {
            _write_reg(REG_PA_DAC, 0x87);
            _write_reg(REG_OCP, 0x3B);
        } else {
            _write_reg(REG_PA_DAC, 0x84);
            _write_reg(REG_OCP, 0x2B);
        }
        _write_reg(REG_PA_CONFIG, 0x80 | static_cast<uint8_t>((power_dbm - 2) & 0x0F));
    } else {
        if (power_dbm < -1 || power_dbm > 14) return;
        _write_reg(REG_PA_DAC, 0x84);
        _write_reg(REG_OCP, 0x2B);
        int8_t out_pwr = static_cast<int8_t>(power_dbm - 15 + 7);
        if (out_pwr < 0) out_pwr = 0;
        if (out_pwr > 15) out_pwr = 15;
        _write_reg(REG_PA_CONFIG, (7 << 4) | static_cast<uint8_t>(out_pwr & 0x0F));
    }
}

void RFM96Full::receive_continuous() {
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _set_mode(MODE_RXCONTINUOUS);
}

uint8_t* RFM96Full::read_packet(uint8_t* out_len) {
    *out_len = 0;
    if (!(_read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return nullptr;
    _write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    return fifo_buf;
}

void RFM96Full::stop_receive() { _set_mode(MODE_STDBY); }

float RFM96Full::rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM96Full::last_packet_rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_PKT_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM96Full::last_packet_snr() {
    int8_t s = static_cast<int8_t>(_read_reg(REG_PKT_SNR_VALUE));
    return s / 4.0f;
}

void RFM96Full::_delay_us(uint32_t us) { ::_delay_us(us); }
void RFM96Full::_digital_write(uint8_t pin, uint8_t value) { ::_dummy_digital_write(pin, value); }
void RFM96Full::_pin_mode(uint8_t pin, uint8_t mode) { ::_dummy_pin_mode(pin, mode); }

// ----- RFM97Minimal -----

RFM97Minimal::RFM97Minimal(Transport& transport, uint32_t frequency_hz)
    : _transport(transport), _frequency_hz(frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) { return; }
    _init();
}

void RFM97Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { static_cast<uint8_t>((reg & 0x7F) | 0x80), value };
    _transport.write(buf, 2);
}

void RFM97Minimal::_write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len) {
    uint8_t buf[256];
    buf[0] = static_cast<uint8_t>((reg & 0x7F) | 0x80);
    memcpy(buf + 1, data, len);
    _transport.write(buf, len + 1);
}

uint16_t RFM97Minimal::_read_reg(uint8_t reg) {
    uint8_t addr = reg & 0x7F;
    uint8_t buf[2] = { 0, 0 };
    _transport.write_read(&addr, 1, buf, 2);
    return (buf[0] << 8) | buf[1];
}

void RFM97Minimal::_set_mode(uint8_t mode) {
    uint8_t current = static_cast<uint8_t>(_read_reg(REG_OP_MODE));
    uint8_t new_val = (current & 0xF8) | (mode & 0x07);
    _write_reg(REG_OP_MODE, new_val);
}

void RFM97Minimal::_set_frequency(uint32_t frequency_hz) {
    uint32_t frf = (static_cast<uint64_t>(frequency_hz) * 524288) / FXOSC;
    _write_reg(REG_FRF_MSB, (frf >> 16) & 0xFF);
    _write_reg(REG_FRF_MID, (frf >> 8) & 0xFF);
    _write_reg(REG_FRF_LSB, frf & 0xFF);
}

void RFM97Minimal::_init() {
    _write_reg(REG_OP_MODE, 0x00);
    _write_reg(REG_OP_MODE, 0x80);
    _set_mode(MODE_SLEEP);
    _write_reg(REG_OP_MODE, 0x88);
    _write_reg(REG_FIFO_TX_BASE_ADDR, 0x80);
    _write_reg(REG_FIFO_RX_BASE_ADDR, 0x00);
    _write_reg(REG_LNA, 0x23);
    _write_reg(REG_MODEM_CONFIG3, 0x04);
    _set_frequency(_frequency_hz);
    _write_reg(REG_MODEM_CONFIG1, 0x72);
    _write_reg(REG_MODEM_CONFIG2, (7 << 4) | 0x04);
    _set_mode(MODE_STDBY);
}

bool RFM97Minimal::_poll_irq(uint8_t irq_mask, uint16_t timeout_ms) {
    for (uint16_t i = 0; i < timeout_ms; i++) {
        uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
        if (flags & irq_mask) return true;
        for (volatile uint32_t j = 0; j < 1000; j++) { }
    }
    return false;
}

void RFM97Minimal::send(const uint8_t* data, uint8_t len) {
    if (len > 255) return;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_ADDR_PTR, 0x80);
    uint8_t fifo_data[256];
    fifo_data[0] = len;
    memcpy(fifo_data + 1, data, len);
    _write_reg_burst(REG_FIFO, fifo_data, len + 1);
    _set_mode(MODE_TX);
    _poll_irq(IRQ_TX_DONE, 10000);
    _write_reg(REG_IRQ_FLAGS, IRQ_TX_DONE);
    _set_mode(MODE_STDBY);
}

uint8_t* RFM97Minimal::receive(uint16_t timeout_ms, uint8_t* out_len) {
    *out_len = 0;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_RX_CURRENT_ADDR, 0x00);
    _set_mode(MODE_RXSINGLE);
    if (!_poll_irq(IRQ_TX_DONE | IRQ_RX_TIMEOUT, timeout_ms)) {
        _set_mode(MODE_STDBY);
        return nullptr;
    }
    uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    if (flags & IRQ_RX_TIMEOUT) { _set_mode(MODE_STDBY); return nullptr; }
    if (!(flags & IRQ_RX_DONE)) { return nullptr; }
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    _set_mode(MODE_STDBY);
    return fifo_buf;
}

uint8_t RFM97Minimal::version() {
    return static_cast<uint8_t>(_read_reg(REG_VERSION));
}

void RFM97Minimal::standby() { _set_mode(MODE_STDBY); }
void RFM97Minimal::sleep() { _set_mode(MODE_SLEEP); }

// ----- RFM97Full -----

RFM97Full::RFM97Full(Transport& transport, uint32_t frequency_hz, uint8_t reset_pin, uint8_t dio0_pin)
    : RFM97Minimal(transport, frequency_hz), _reset_pin(reset_pin), _dio0_pin(dio0_pin) {
    if (reset_pin != 0) {
        _pin_mode(reset_pin, 1);
        _digital_write(reset_pin, 0);
        _delay_us(100);
        _digital_write(reset_pin, 1);
        _delay_us(5000);
    }
}

void RFM97Full::reset() {
    if (_reset_pin == 0) return;
    _digital_write(_reset_pin, 0);
    _delay_us(100);
    _digital_write(_reset_pin, 1);
    _delay_us(5000);
    _init();
}

void RFM97Full::configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc) {
    if (sf < 6 || sf > MAX_SF) return;
    _sf = sf;
    _crc = crc;
    _bw = bandwidth_khz;
    _cr = coding_rate;
    uint8_t bw_bits = _map_bw(bandwidth_khz);
    uint8_t cr_bits = _map_cr(coding_rate);
    _write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
    uint8_t implicit = (sf == 6) ? 1 : 0;
    _write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
    if (sf == 6) {
        _write_reg(0x31, 0x05);
        _write_reg(0x37, 0x0C);
    }
}

void RFM97Full::set_frequency(uint32_t frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) return;
    _frequency_hz = frequency_hz;
    _set_frequency(frequency_hz);
}

void RFM97Full::set_tx_power(int8_t power_dbm, bool use_pa_boost) {
    if (use_pa_boost) {
        if (power_dbm < 2 || power_dbm > 20) return;
        if (power_dbm > 17) {
            _write_reg(REG_PA_DAC, 0x87);
            _write_reg(REG_OCP, 0x3B);
        } else {
            _write_reg(REG_PA_DAC, 0x84);
            _write_reg(REG_OCP, 0x2B);
        }
        _write_reg(REG_PA_CONFIG, 0x80 | static_cast<uint8_t>((power_dbm - 2) & 0x0F));
    } else {
        if (power_dbm < -1 || power_dbm > 14) return;
        _write_reg(REG_PA_DAC, 0x84);
        _write_reg(REG_OCP, 0x2B);
        int8_t out_pwr = static_cast<int8_t>(power_dbm - 15 + 7);
        if (out_pwr < 0) out_pwr = 0;
        if (out_pwr > 15) out_pwr = 15;
        _write_reg(REG_PA_CONFIG, (7 << 4) | static_cast<uint8_t>(out_pwr & 0x0F));
    }
}

void RFM97Full::receive_continuous() {
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _set_mode(MODE_RXCONTINUOUS);
}

uint8_t* RFM97Full::read_packet(uint8_t* out_len) {
    *out_len = 0;
    if (!(_read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return nullptr;
    _write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    return fifo_buf;
}

void RFM97Full::stop_receive() { _set_mode(MODE_STDBY); }

float RFM97Full::rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM97Full::last_packet_rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_PKT_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM97Full::last_packet_snr() {
    int8_t s = static_cast<int8_t>(_read_reg(REG_PKT_SNR_VALUE));
    return s / 4.0f;
}

void RFM97Full::_delay_us(uint32_t us) { ::_delay_us(us); }
void RFM97Full::_digital_write(uint8_t pin, uint8_t value) { ::_dummy_digital_write(pin, value); }
void RFM97Full::_pin_mode(uint8_t pin, uint8_t mode) { ::_dummy_pin_mode(pin, mode); }

// ----- RFM98Minimal -----

RFM98Minimal::RFM98Minimal(Transport& transport, uint32_t frequency_hz)
    : _transport(transport), _frequency_hz(frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) { return; }
    _init();
}

void RFM98Minimal::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { static_cast<uint8_t>((reg & 0x7F) | 0x80), value };
    _transport.write(buf, 2);
}

void RFM98Minimal::_write_reg_burst(uint8_t reg, const uint8_t* data, uint8_t len) {
    uint8_t buf[256];
    buf[0] = static_cast<uint8_t>((reg & 0x7F) | 0x80);
    memcpy(buf + 1, data, len);
    _transport.write(buf, len + 1);
}

uint16_t RFM98Minimal::_read_reg(uint8_t reg) {
    uint8_t addr = reg & 0x7F;
    uint8_t buf[2] = { 0, 0 };
    _transport.write_read(&addr, 1, buf, 2);
    return (buf[0] << 8) | buf[1];
}

void RFM98Minimal::_set_mode(uint8_t mode) {
    uint8_t current = static_cast<uint8_t>(_read_reg(REG_OP_MODE));
    uint8_t new_val = (current & 0xF8) | (mode & 0x07);
    _write_reg(REG_OP_MODE, new_val);
}

void RFM98Minimal::_set_frequency(uint32_t frequency_hz) {
    uint32_t frf = (static_cast<uint64_t>(frequency_hz) * 524288) / FXOSC;
    _write_reg(REG_FRF_MSB, (frf >> 16) & 0xFF);
    _write_reg(REG_FRF_MID, (frf >> 8) & 0xFF);
    _write_reg(REG_FRF_LSB, frf & 0xFF);
}

void RFM98Minimal::_init() {
    _write_reg(REG_OP_MODE, 0x00);
    _write_reg(REG_OP_MODE, 0x80);
    _set_mode(MODE_SLEEP);
    _write_reg(REG_OP_MODE, 0x80);
    _write_reg(REG_FIFO_TX_BASE_ADDR, 0x80);
    _write_reg(REG_FIFO_RX_BASE_ADDR, 0x00);
    _write_reg(REG_MODEM_CONFIG3, 0x04);
    _set_frequency(_frequency_hz);
    _write_reg(REG_MODEM_CONFIG1, 0x72);
    _write_reg(REG_MODEM_CONFIG2, (7 << 4) | 0x04);
    _set_mode(MODE_STDBY);
}

bool RFM98Minimal::_poll_irq(uint8_t irq_mask, uint16_t timeout_ms) {
    for (uint16_t i = 0; i < timeout_ms; i++) {
        uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
        if (flags & irq_mask) return true;
        for (volatile uint32_t j = 0; j < 1000; j++) { }
    }
    return false;
}

void RFM98Minimal::send(const uint8_t* data, uint8_t len) {
    if (len > 255) return;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_ADDR_PTR, 0x80);
    uint8_t fifo_data[256];
    fifo_data[0] = len;
    memcpy(fifo_data + 1, data, len);
    _write_reg_burst(REG_FIFO, fifo_data, len + 1);
    _set_mode(MODE_TX);
    _poll_irq(IRQ_TX_DONE, 10000);
    _write_reg(REG_IRQ_FLAGS, IRQ_TX_DONE);
    _set_mode(MODE_STDBY);
}

uint8_t* RFM98Minimal::receive(uint16_t timeout_ms, uint8_t* out_len) {
    *out_len = 0;
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _write_reg(REG_FIFO_RX_CURRENT_ADDR, 0x00);
    _set_mode(MODE_RXSINGLE);
    if (!_poll_irq(IRQ_TX_DONE | IRQ_RX_TIMEOUT, timeout_ms)) {
        _set_mode(MODE_STDBY);
        return nullptr;
    }
    uint8_t flags = static_cast<uint8_t>(_read_reg(REG_IRQ_FLAGS));
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    if (flags & IRQ_RX_TIMEOUT) { _set_mode(MODE_STDBY); return nullptr; }
    if (!(flags & IRQ_RX_DONE)) { return nullptr; }
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    _set_mode(MODE_STDBY);
    return fifo_buf;
}

uint8_t RFM98Minimal::version() {
    return static_cast<uint8_t>(_read_reg(REG_VERSION));
}

void RFM98Minimal::standby() { _set_mode(MODE_STDBY); }
void RFM98Minimal::sleep() { _set_mode(MODE_SLEEP); }

// ----- RFM98Full -----

RFM98Full::RFM98Full(Transport& transport, uint32_t frequency_hz, uint8_t reset_pin, uint8_t dio0_pin)
    : RFM98Minimal(transport, frequency_hz), _reset_pin(reset_pin), _dio0_pin(dio0_pin) {
    if (reset_pin != 0) {
        _pin_mode(reset_pin, 1);
        _digital_write(reset_pin, 0);
        _delay_us(100);
        _digital_write(reset_pin, 1);
        _delay_us(5000);
    }
}

void RFM98Full::reset() {
    if (_reset_pin == 0) return;
    _digital_write(_reset_pin, 0);
    _delay_us(100);
    _digital_write(_reset_pin, 1);
    _delay_us(5000);
    _init();
}

void RFM98Full::configure(uint8_t sf, uint8_t bandwidth_khz, uint8_t coding_rate, bool crc) {
    if (sf < 6 || sf > MAX_SF) return;
    if (IS_LF_BAND && bandwidth_khz > 62) return;
    _sf = sf;
    _crc = crc;
    _bw = bandwidth_khz;
    _cr = coding_rate;
    uint8_t bw_bits = _map_bw(bandwidth_khz);
    uint8_t cr_bits = _map_cr(coding_rate);
    _write_reg(REG_MODEM_CONFIG1, (bw_bits << 4) | (cr_bits << 1));
    uint8_t implicit = (sf == 6) ? 1 : 0;
    _write_reg(REG_MODEM_CONFIG2, (sf << 4) | (crc ? 0x04 : 0x00) | implicit);
    if (sf == 6) {
        _write_reg(0x31, 0x05);
        _write_reg(0x37, 0x0C);
    }
}

void RFM98Full::set_frequency(uint32_t frequency_hz) {
    if (frequency_hz < FREQ_MIN_HZ || frequency_hz > FREQ_MAX_HZ) return;
    _frequency_hz = frequency_hz;
    _set_frequency(frequency_hz);
}

void RFM98Full::set_tx_power(int8_t power_dbm, bool use_pa_boost) {
    if (use_pa_boost) {
        if (power_dbm < 2 || power_dbm > 20) return;
        if (power_dbm > 17) {
            _write_reg(REG_PA_DAC, 0x87);
            _write_reg(REG_OCP, 0x3B);
        } else {
            _write_reg(REG_PA_DAC, 0x84);
            _write_reg(REG_OCP, 0x2B);
        }
        _write_reg(REG_PA_CONFIG, 0x80 | static_cast<uint8_t>((power_dbm - 2) & 0x0F));
    } else {
        if (power_dbm < -1 || power_dbm > 14) return;
        _write_reg(REG_PA_DAC, 0x84);
        _write_reg(REG_OCP, 0x2B);
        int8_t out_pwr = static_cast<int8_t>(power_dbm - 15 + 7);
        if (out_pwr < 0) out_pwr = 0;
        if (out_pwr > 15) out_pwr = 15;
        _write_reg(REG_PA_CONFIG, (7 << 4) | static_cast<uint8_t>(out_pwr & 0x0F));
    }
}

void RFM98Full::receive_continuous() {
    _set_mode(MODE_STDBY);
    _write_reg(REG_IRQ_FLAGS, 0xFF);
    _set_mode(MODE_RXCONTINUOUS);
}

uint8_t* RFM98Full::read_packet(uint8_t* out_len) {
    *out_len = 0;
    if (!(_read_reg(REG_IRQ_FLAGS) & IRQ_RX_DONE)) return nullptr;
    _write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
    uint8_t nb_bytes = static_cast<uint8_t>(_read_reg(REG_RX_NB_BYTES));
    _write_reg(REG_FIFO_ADDR_PTR, static_cast<uint8_t>(_read_reg(REG_FIFO_RX_CURRENT_ADDR)));
    static uint8_t fifo_buf[256];
    uint8_t addr = REG_FIFO;
    _transport.write_read(&addr, 1, fifo_buf, nb_bytes);
    *out_len = nb_bytes;
    return fifo_buf;
}

void RFM98Full::stop_receive() { _set_mode(MODE_STDBY); }

float RFM98Full::rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM98Full::last_packet_rssi() {
    uint8_t r = static_cast<uint8_t>(_read_reg(REG_PKT_RSSI_VALUE));
    return -137.0f + r * 0.5f;
}

float RFM98Full::last_packet_snr() {
    int8_t s = static_cast<int8_t>(_read_reg(REG_PKT_SNR_VALUE));
    return s / 4.0f;
}

void RFM98Full::_delay_us(uint32_t us) { ::_delay_us(us); }
void RFM98Full::_digital_write(uint8_t pin, uint8_t value) { ::_dummy_digital_write(pin, value); }
void RFM98Full::_pin_mode(uint8_t pin, uint8_t mode) { ::_dummy_pin_mode(pin, mode); }