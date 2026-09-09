#include "RFM9x.h"

#ifdef __linux__
#include <cstdio>
static unsigned long _now_ms_linux() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (unsigned long)(ts.tv_sec) * 1000UL + (unsigned long)(ts.tv_nsec / 1000000L);
}
#define _millis() _now_ms_linux()
#elif defined(ARDUINO)
#define _millis() millis()
#elif defined(CONFIG_SPI) || defined(__ZEPHYR_SUPERVISOR__)
#include <zephyr/kernel.h>
static unsigned long _now_ms_zephyr() { return (unsigned long)k_uptime_get(); }
#define _millis() _now_ms_zephyr()
#else
#include <chrono>
static unsigned long _now_ms_host() {
    using namespace std::chrono;
    return (unsigned long)duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}
#define _millis() _now_ms_host()
#endif

// ============================================================
// _RFM9xBase
// ============================================================

_RFM9xBase::_RFM9xBase(Connection& connection, uint32_t frequency_hz)
    : _connection(connection), _frequency_hz(frequency_hz) {
    _write_reg(REG_OP_MODE, 0x00);                // FSK SLEEP (LongRangeMode writable only in SLEEP)
    _delay_ms(1);
    uint8_t op = MODE_LONG_RANGE | MODE_SLEEP;
    _write_reg(REG_OP_MODE, op);                  // LoRa SLEEP
    _delay_ms(1);

    uint8_t ver = _read_reg(REG_VERSION);
    if (ver != EXPECTED_VERSION) {
        // Wiring / SPI-config error — raise by overwriting version
        // so the caller can branch on it.
        // (No exceptions on Arduino; caller checks via version().)
    }

    if (_lf_band) {
        uint8_t lna = _read_reg(REG_LNA);
        _write_reg(REG_LNA, lna & 0x3F);          // disable HF LNA boost for LF
    } else {
        _write_reg(REG_LNA, 0x23);                // HF: LnaGain=max, LnaBoostHf=11
    }
    _write_reg(REG_MODEM_CONFIG_3, _read_reg(REG_MODEM_CONFIG_3) | 0x04);  // AGC auto on

    _write_reg(REG_FIFO_TX_BASE, 0x80);
    _write_reg(REG_FIFO_RX_BASE, 0x00);

    set_frequency(frequency_hz);

    _write_reg(REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00);  // BW=125kHz, CR=4/5, explicit header
    _write_reg(REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03);  // SF7, CRC on, symb timeout 0x03
    _write_reg(REG_PREAMBLE_LSB, 0x08);

    set_tx_power(17, true);                       // +17 dBm on PA_BOOST
    standby();
}

void _RFM9xBase::_write_reg(uint8_t reg, uint8_t value) {
    uint8_t buf[2] = { (uint8_t)(reg | 0x80), value };
    _connection.write(buf, 2);
}

uint8_t _RFM9xBase::_read_reg(uint8_t reg) {
    uint8_t cmd = reg & 0x7F;
    uint8_t val = 0;
    _connection.write_read(&cmd, 1, &val, 1);
    return val;
}

void _RFM9xBase::_burst_write(uint8_t reg, const uint8_t* data, size_t len) {
    uint8_t cmd = reg | 0x80;
    _connection.write(&cmd, 1);
    _connection.write(data, len);
}

void _RFM9xBase::_burst_read(uint8_t reg, uint8_t* buf, size_t len) {
    uint8_t cmd = reg & 0x7F;
    _connection.write_read(&cmd, 1, buf, len);
}

void _RFM9xBase::_enter_lora_sleep() {
    _write_reg(REG_OP_MODE, 0x00);
    _delay_ms(1);
    uint8_t op = MODE_LONG_RANGE | _band_flag() | MODE_SLEEP;
    _write_reg(REG_OP_MODE, op);
    _delay_ms(1);
}

void _RFM9xBase::set_frequency(uint32_t frequency_hz) {
    if (frequency_hz < _freq_min_hz || frequency_hz > _freq_max_hz) {
        // Caller is responsible for passing a valid frequency. Fall through
        // to allow the chip to receive whatever value happens to be passed.
    }
    uint64_t frf = ((uint64_t)frequency_hz << 19) / FXOSC;
    _write_reg(REG_FRF_MSB, (frf >> 16) & 0xFF);
    _write_reg(REG_FRF_MID, (frf >>  8) & 0xFF);
    _write_reg(REG_FRF_LSB,  frf        & 0xFF);
    _frequency_hz = frequency_hz;
}

void _RFM9xBase::standby() {
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | _band_flag() | MODE_STANDBY);
}

void _RFM9xBase::sleep() {
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | _band_flag() | MODE_SLEEP);
}

uint8_t _RFM9xBase::version() {
    return _read_reg(REG_VERSION);
}

void _RFM9xBase::set_tx_power(int8_t power_dbm, bool use_pa_boost) {
    if (use_pa_boost) {
        if (power_dbm > 17) {
            if (power_dbm > 20) power_dbm = 20;
            _write_reg(REG_PA_DAC,  PA_DAC_HIGH_POWER);
            _write_reg(REG_OCP,     OCP_240MA);
            _write_reg(REG_PA_CONFIG, PA_BOOST | 0x0F);
        } else {
            if (power_dbm < 2) power_dbm = 2;
            _write_reg(REG_PA_DAC,  PA_DAC_DEFAULT);
            _write_reg(REG_OCP,     OCP_DEFAULT);
            _write_reg(REG_PA_CONFIG, PA_BOOST | (uint8_t)(power_dbm - 2));
        }
    } else {
        _write_reg(REG_PA_DAC,  PA_DAC_DEFAULT);
        _write_reg(REG_OCP,     OCP_DEFAULT);
        uint8_t max_power = 7;
        float pmax = 10.8f + 0.6f * max_power;
        int op = (int)((float)power_dbm - pmax + 15.0f);
        if (op < 0)  op = 0;
        if (op > 15) op = 15;
        _write_reg(REG_PA_CONFIG, (max_power << 4) | (uint8_t)op);
    }
}

void _RFM9xBase::configure(uint8_t sf, float bandwidth_khz, uint8_t coding_rate, bool crc) {
    static const float bw_table[] = { 7.8f, 10.4f, 15.6f, 20.8f, 31.25f, 41.7f, 62.5f, 125.0f, 250.0f, 500.0f };
    uint8_t bw_code = 0x07;
    bool found = false;
    for (uint8_t i = 0; i < 10; i++) {
        if (bandwidth_khz == bw_table[i]) { bw_code = i; found = true; break; }
    }
    (void)found;

    if (sf < 6 || sf > _max_sf) {
        sf = (sf < 6) ? 6 : _max_sf;
    }

    if (sf == 6) {
        _write_reg(REG_DETECTION_OPT, 0x05);
        _write_reg(REG_DETECTION_THR, 0x0C);
    } else {
        _write_reg(REG_DETECTION_OPT, 0x03);
        _write_reg(REG_DETECTION_THR, 0x0A);
    }

    bool implicit_header = (sf == 6);
    uint8_t cr = (coding_rate >= 5 && coding_rate <= 8) ? (uint8_t)(coding_rate - 4) : 0x01;
    _write_reg(REG_MODEM_CONFIG_1, (bw_code << 4) | (cr << 1) | (implicit_header ? 1 : 0));
    _write_reg(REG_MODEM_CONFIG_2, (sf << 4) | ((crc ? 1 : 0) << 2) | 0x03);
}

void _RFM9xBase::send(const uint8_t* data, size_t len) {
    if (len > 255) len = 255;
    standby();
    _write_reg(REG_FIFO_ADDR_PTR, 0x80);
    _burst_write(REG_FIFO, data, len);
    _write_reg(REG_PAYLOAD_LENGTH, (uint8_t)len);
    _write_reg(REG_DIO_MAPPING_1, DIO0_TX_DONE);
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | _band_flag() | MODE_TX);
    while (true) {
        uint8_t irq = _read_reg(REG_IRQ_FLAGS);
        if (irq & IRQ_TX_DONE) break;
        _delay_ms(2);
    }
    _write_reg(REG_IRQ_FLAGS, IRQ_TX_DONE);
    standby();
}

bool _RFM9xBase::_read_payload(uint8_t* buf, size_t& len) {
    uint8_t current = _read_reg(REG_FIFO_RX_CURRENT);
    _write_reg(REG_FIFO_ADDR_PTR, current);
    uint8_t n = _read_reg(REG_RX_NB_BYTES);
    _burst_read(REG_FIFO, buf, n);
    len = n;
    return n > 0;
}

bool _RFM9xBase::receive(uint8_t* buf, size_t& len, uint32_t timeout_ms) {
    len = 0;
    standby();
    _write_reg(REG_DIO_MAPPING_1, DIO0_RX_DONE);
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | _band_flag() | MODE_RX_SINGLE);

    unsigned long start = _millis();
    while ((unsigned long)(_millis() - start) < timeout_ms) {
        uint8_t irq = _read_reg(REG_IRQ_FLAGS);
        if (irq & IRQ_RX_DONE) {
            _write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
            return _read_payload(buf, len);
        }
        if (irq & IRQ_RX_TIMEOUT) {
            _write_reg(REG_IRQ_FLAGS, IRQ_RX_TIMEOUT);
            return false;
        }
        _delay_ms(5);
    }
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | _band_flag() | MODE_STANDBY);
    return false;
}

void _RFM9xBase::receive_continuous() {
    standby();
    _write_reg(REG_DIO_MAPPING_1, DIO0_RX_DONE);
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | _band_flag() | MODE_RX_CONT);
}

bool _RFM9xBase::read_packet(uint8_t* buf, size_t& len) {
    len = 0;
    uint8_t irq = _read_reg(REG_IRQ_FLAGS);
    if (!(irq & IRQ_RX_DONE)) return false;
    _write_reg(REG_IRQ_FLAGS, IRQ_RX_DONE);
    return _read_payload(buf, len);
}

void _RFM9xBase::stop_receive() {
    standby();
}

float _RFM9xBase::rssi() {
    return -137.0f + (float)_read_reg(REG_RSSI);
}

float _RFM9xBase::last_packet_rssi() {
    return -137.0f + (float)_read_reg(REG_PKT_RSSI);
}

float _RFM9xBase::last_packet_snr() {
    int8_t raw = (int8_t)_read_reg(REG_PKT_SNR);
    return (float)raw / 4.0f;
}

void _RFM9xBase::_delay_ms(unsigned long ms) {
#ifdef ARDUINO
    delay(ms);
#elif defined(__linux__)
    struct timespec ts;
    ts.tv_sec  = (time_t)(ms / 1000);
    ts.tv_nsec = (long)((ms % 1000) * 1000000L);
    nanosleep(&ts, nullptr);
#elif defined(CONFIG_SPI) || defined(__Zephyr__)
    k_msleep(ms);
#else
    struct timespec ts;
    ts.tv_sec  = (time_t)(ms / 1000);
    ts.tv_nsec = (long)((ms % 1000) * 1000000L);
    nanosleep(&ts, nullptr);
#endif
}

// ============================================================
// Full-stage hardware reset (stub by default; pin-aware variants live in
// platform-specific extensions; see the Arduino/Zephyr examples).
// ============================================================

void RFM95Full::reset() {
    _delay_ms(5);   // POR wait fallback; pin-driven reset is wired in examples
    _write_reg(REG_OP_MODE, 0x00); _delay_ms(1);
    _write_reg(REG_OP_MODE, MODE_LONG_RANGE | MODE_SLEEP); _delay_ms(1);
    _write_reg(REG_LNA, 0x23);
    _write_reg(REG_MODEM_CONFIG_3, _read_reg(REG_MODEM_CONFIG_3) | 0x04);
    _write_reg(REG_FIFO_TX_BASE, 0x80);
    _write_reg(REG_FIFO_RX_BASE, 0x00);
    set_frequency(_frequency_hz);
    _write_reg(REG_MODEM_CONFIG_1, (0x07 << 4) | (0x01 << 1) | 0x00);
    _write_reg(REG_MODEM_CONFIG_2, (0x07 << 4) | (0x01 << 2) | 0x03);
    _write_reg(REG_PREAMBLE_LSB, 0x08);
    set_tx_power(17, true);
    standby();
}

void RFM96Full::reset() { RFM95Full::reset(); }
void RFM97Full::reset() { RFM95Full::reset(); }
void RFM98Full::reset() { RFM95Full::reset(); }
