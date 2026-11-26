#include "Si1133.h"

#ifdef __linux__
#include <unistd.h>
static void periph_delay_ms(unsigned ms) { usleep(ms * 1000); }
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static void periph_delay_ms(unsigned ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static void periph_delay_ms(unsigned ms) { vTaskDelay(pdMS_TO_TICKS(ms) > 0 ? pdMS_TO_TICKS(ms) : 1); }
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static void periph_delay_ms(unsigned ms) { HAL_Delay(ms); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static void periph_delay_ms(unsigned ms) { sleep_ms(ms); }
#else
#include <Arduino.h>
static void periph_delay_ms(unsigned ms) { delay(ms); }
#endif

// --- Minimal ---

Si1133Minimal::Si1133Minimal(RegisterConnection& connection)
    : _connection(connection) {}

bool Si1133Minimal::_read_reg(uint8_t reg, uint8_t& value) {
    _connection.read(reg, &value, 1);
    return _connection.ok();
}

bool Si1133Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
    return _connection.ok();
}

bool Si1133Minimal::init() {
    periph_delay_ms(25);
    uint8_t part;
    if (!_read_reg(REG_PART_ID, part) || part != PART_ID) return false;
    if (!_software_reset()) return false;
    return _apply_defaults();
}

bool Si1133Minimal::_software_reset() {
    if (!_write_reg(REG_COMMAND, CMD_RESET_SW)) return false;
    periph_delay_ms(10);
    _chan_list = 0;
    _bits24 = 0;
    _irq_enable = 0;
    for (unsigned i = 0; i < CMD_POLLS; i++) {
        uint8_t r;
        if (!_read_reg(REG_RESPONSE0, r)) return false;
        if (r & RESP_SLEEP) return true;
        periph_delay_ms(1);
    }
    return false;
}

bool Si1133Minimal::_apply_defaults() {
    if (!_set_channel(0, MUX_UV, 9, 0, 0, false, true, 0)) return false;
    if (!_set_channel(1, MUX_WHITE, 7, 0, 0, false, true, 0)) return false;
    if (!_param_set(PARAM_CHAN_LIST, 0x03)) return false;
    _chan_list = 0x03;
    _irq_enable = 0x3F;
    return _write_reg(REG_IRQ_ENABLE, _irq_enable);
}

bool Si1133Minimal::_counter(uint8_t& counter) {
    uint8_t r;
    if (!_read_reg(REG_RESPONSE0, r)) return false;
    if (r & RESP_CMD_ERR) {
        if (!_write_reg(REG_COMMAND, CMD_RESET_CMD_CTR)) return false;
        if (!_read_reg(REG_RESPONSE0, r)) return false;
    }
    counter = r & 0x0F;
    return true;
}

bool Si1133Minimal::_wait_counter(uint8_t before) {
    for (unsigned i = 0; i < CMD_POLLS; i++) {
        uint8_t r;
        if (!_read_reg(REG_RESPONSE0, r)) return false;
        if (r & RESP_CMD_ERR) {
            _write_reg(REG_COMMAND, CMD_RESET_CMD_CTR);
            return false;
        }
        if ((r & 0x0F) != before) return true;
        periph_delay_ms(1);
    }
    return false;
}

bool Si1133Minimal::_command(uint8_t cmd) {
    uint8_t before;
    if (!_counter(before)) return false;
    if (!_write_reg(REG_COMMAND, cmd)) return false;
    return _wait_counter(before);
}

bool Si1133Minimal::_param_set(uint8_t address, uint8_t value) {
    uint8_t before;
    if (!_counter(before)) return false;
    uint8_t b[2] = {value, (uint8_t)(CMD_PARAM_SET | (address & 0x3F))};
    _connection.write(REG_HOSTIN0, b, 2);
    if (!_connection.ok()) return false;
    return _wait_counter(before);
}

bool Si1133Minimal::_param_query(uint8_t address, uint8_t& value) {
    if (!_command((uint8_t)(CMD_PARAM_QUERY | (address & 0x3F)))) return false;
    return _read_reg(REG_RESPONSE1, value);
}

bool Si1133Minimal::_set_channel(uint8_t channel, uint8_t adcmux, uint8_t hw_gain,
                                 uint8_t sw_gain, uint8_t decim_rate, bool high_signal,
                                 bool bits24, uint8_t postshift) {
    uint8_t base = (uint8_t)(4 * channel);
    if (!_param_set(PARAM_ADCCONFIG0 + base, (uint8_t)((decim_rate << 5) | adcmux))) return false;
    if (!_param_set(PARAM_ADCSENS0 + base,
                    (uint8_t)((high_signal ? 0x80 : 0) | (sw_gain << 4) | hw_gain))) return false;
    if (!_param_set(PARAM_ADCPOST0 + base, (uint8_t)((bits24 ? 0x40 : 0) | (postshift << 3))))
        return false;
    if (bits24) _bits24 |= (uint8_t)(1 << channel);
    else _bits24 &= (uint8_t)~(1 << channel);
    return true;
}

bool Si1133Minimal::_read_outputs(int32_t* values, size_t& count) {
    size_t n = 0;
    for (uint8_t ch = 0; ch < 6; ch++)
        if (_chan_list & (1 << ch)) n += (_bits24 & (1 << ch)) ? 3 : 2;
    count = 0;
    if (n == 0) return true;
    uint8_t d[18];
    _connection.read(REG_HOSTOUT0, d, n);
    if (!_connection.ok()) return false;
    size_t pos = 0;
    for (uint8_t ch = 0; ch < 6; ch++) {
        if (!(_chan_list & (1 << ch))) continue;
        int32_t v;
        if (_bits24 & (1 << ch)) {
            uint32_t u = ((uint32_t)d[pos] << 16) | ((uint32_t)d[pos + 1] << 8) | d[pos + 2];
            v = (u & 0x800000) ? (int32_t)u - 0x1000000 : (int32_t)u;
            pos += 3;
        } else {
            v = (int32_t)(((uint16_t)d[pos] << 8) | d[pos + 1]);
            pos += 2;
        }
        values[count++] = v;
    }
    return true;
}

bool Si1133Minimal::_measure(int32_t* values, size_t& count) {
    uint8_t s;
    if (!_read_reg(REG_IRQ_STATUS, s)) return false;  // clear stale flags
    if (!_command(CMD_FORCE)) return false;
    uint8_t wait = _chan_list & _irq_enable;
    if (wait == 0) {
        periph_delay_ms(100);
    } else {
        uint8_t seen = 0;
        unsigned i = 0;
        for (;;) {
            if (!_read_reg(REG_IRQ_STATUS, s)) return false;
            seen |= s;
            if ((seen & wait) == wait) break;
            if (++i >= MEASURE_POLLS) return false;
            periph_delay_ms(1);
        }
    }
    return _read_outputs(values, count);
}

bool Si1133Minimal::read(int32_t& uv, int32_t& white) {
    if ((_chan_list & 0x03) != 0x03) return false;
    int32_t v[6];
    size_t n;
    if (!_measure(v, n)) return false;
    uv = v[0];
    white = v[1];
    return true;
}

// --- Full ---

Si1133Full* Si1133Full::_activeInstance = nullptr;

Si1133Full::Si1133Full(RegisterConnection& connection)
    : Si1133Minimal(connection) {}

bool Si1133Full::read_id(uint8_t& part_id, uint8_t& hw_id, uint8_t& rev_id) {
    uint8_t b[3];
    _connection.read(REG_PART_ID, b, 3);
    if (!_connection.ok()) return false;
    part_id = b[0];
    hw_id = b[1];
    rev_id = b[2];
    return true;
}

bool Si1133Full::configure_channel(uint8_t channel, uint8_t adcmux, uint8_t hw_gain,
                                   uint8_t sw_gain, uint8_t decim_rate, bool high_signal,
                                   bool bits24, uint8_t postshift) {
    if (channel > 5 || adcmux > 0x1F || hw_gain > 11 || sw_gain > 7 || decim_rate > 3 ||
        postshift > 7) return false;
    return _set_channel(channel, adcmux, hw_gain, sw_gain, decim_rate, high_signal, bits24,
                        postshift);
}

bool Si1133Full::enable_channels(uint8_t mask) {
    if (mask > 0x3F) return false;
    if (!_param_set(PARAM_CHAN_LIST, mask)) return false;
    _chan_list = mask;
    return true;
}

bool Si1133Full::read_channels(int32_t* values, size_t& count, bool force) {
    return force ? _measure(values, count) : _read_outputs(values, count);
}

bool Si1133Full::_scratch_read(uint8_t adcmux, uint8_t hw_gain, int32_t& value) {
    uint8_t saved = _chan_list;
    if (!_set_channel(SCRATCH, adcmux, hw_gain, 0, 0, false, true, 0)) return false;
    if (_counter_index[SCRATCH]) {
        if (!_param_set(PARAM_MEASCONFIG0 + 4 * SCRATCH, 0)) return false;
        _counter_index[SCRATCH] = 0;
    }
    if (!_param_set(PARAM_CHAN_LIST, 1 << SCRATCH)) return false;
    _chan_list = 1 << SCRATCH;
    int32_t v[6];
    size_t n = 0;
    bool ok = _measure(v, n) && n == 1;
    if (ok) value = v[0];
    bool restored = _param_set(PARAM_CHAN_LIST, saved);
    _chan_list = saved;
    return ok && restored;
}

bool Si1133Full::read_uv(int32_t& value) { return _scratch_read(MUX_UV, 9, value); }

bool Si1133Full::read_white(int32_t& value) { return _scratch_read(MUX_WHITE, 7, value); }

bool Si1133Full::read_ir(int32_t& value, uint8_t photodiode) {
    if (photodiode > MUX_LARGE_IR) return false;
    return _scratch_read(photodiode, 7, value);
}

bool Si1133Full::force() { return _command(CMD_FORCE); }

bool Si1133Full::set_channel_counter(uint8_t channel, uint8_t index) {
    if (channel > 5 || index > 3) return false;
    if (!_param_set(PARAM_MEASCONFIG0 + 4 * channel, (uint8_t)(index << 6))) return false;
    _counter_index[channel] = index;
    return true;
}

bool Si1133Full::start_autonomous(float rate_s, uint8_t count0, uint8_t count1, uint8_t count2) {
    float t = rate_s / 800e-6f + 0.5f;
    if (!(t >= 1.0f) || t >= 65536.0f) return false;
    uint16_t ticks = (uint16_t)t;
    if (count0 == 0 || count1 == 0 || count2 == 0) return false;
    if (!_param_set(PARAM_MEASRATE_H, (uint8_t)(ticks >> 8))) return false;
    if (!_param_set(PARAM_MEASRATE_L, (uint8_t)(ticks & 0xFF))) return false;
    if (!_param_set(PARAM_MEASCOUNT0, count0)) return false;
    if (!_param_set(PARAM_MEASCOUNT0 + 1, count1)) return false;
    if (!_param_set(PARAM_MEASCOUNT0 + 2, count2)) return false;
    for (uint8_t ch = 0; ch < 6; ch++)
        if ((_chan_list & (1 << ch)) && !_counter_index[ch])
            if (!set_channel_counter(ch, 1)) return false;
    return _command(CMD_START);
}

bool Si1133Full::pause() { return _command(CMD_PAUSE); }

bool Si1133Full::set_burst(uint8_t count) {
    if (count > 127) return false;
    return _param_set(PARAM_BURST, count ? (uint8_t)(0x80 | count) : 0);
}

bool Si1133Full::set_threshold(uint8_t index, uint16_t value) {
    if (index > 2) return false;
    uint8_t addr = (uint8_t)(PARAM_THRESHOLD0_H + 2 * index);
    return _param_set(addr, (uint8_t)(value >> 8)) && _param_set(addr + 1, (uint8_t)(value & 0xFF));
}

bool Si1133Full::set_channel_threshold(uint8_t channel, uint8_t index) {
    if (channel > 5 || index > 3) return false;
    uint8_t addr = (uint8_t)(PARAM_ADCPOST0 + 4 * channel);
    uint8_t v;
    if (!_param_query(addr, v)) return false;
    return _param_set(addr, (uint8_t)((v & 0xFC) | index));
}

bool Si1133Full::read_parameter(uint8_t address, uint8_t& value) {
    if (address > 0x3F) return false;
    return _param_query(address, value);
}

bool Si1133Full::write_parameter(uint8_t address, uint8_t value) {
    if (address > 0x3F) return false;
    return _param_set(address, value);
}

bool Si1133Full::read_response(uint8_t& state, bool& error, uint8_t& counter) {
    uint8_t r;
    if (!_read_reg(REG_RESPONSE0, r)) return false;
    state = r & 0xE0;
    error = (r & RESP_CMD_ERR) != 0;
    counter = r & 0x0F;
    return true;
}

bool Si1133Full::data_ready(uint8_t& mask) {
    uint8_t s;
    if (!_read_reg(REG_IRQ_STATUS, s)) return false;
    mask = s & 0x3F;
    return true;
}

bool Si1133Full::set_i2c_address(uint8_t address) {
    if (address < 0x08 || address > 0x77) return false;
    uint8_t r;
    if (!_read_reg(REG_RESPONSE0, r)) return false;
    uint8_t b[2] = {address, (uint8_t)(CMD_PARAM_SET | PARAM_I2C_ADDR)};
    _connection.write(REG_HOSTIN0, b, 2);
    return _connection.ok();
}

bool Si1133Full::reset() {
    if (!_software_reset()) return false;
    for (uint8_t& c : _counter_index) c = 0;
    return _apply_defaults();
}

bool Si1133Full::enable_interrupt(uint8_t source) {
    _irq_enable = (uint8_t)((_irq_enable | source) & 0x3F);
    return _write_reg(REG_IRQ_ENABLE, _irq_enable);
}

bool Si1133Full::disable_interrupt(uint8_t source) {
    _irq_enable = (uint8_t)(_irq_enable & ~source & 0x3F);
    return _write_reg(REG_IRQ_ENABLE, _irq_enable);
}

uint8_t Si1133Full::poll_interrupt() {
    uint8_t m = 0;
    if (!data_ready(m)) return 0;
    return m;
}

void Si1133Full::on_interrupt(void (*callback)(uint8_t), InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p) return;  // no INT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&Si1133Full::_edgeTrampoline, InputPin::kFalling);
}

void Si1133Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&Si1133Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void Si1133Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void Si1133Full::_handleEdge() {
    uint8_t s = poll_interrupt();
    if (s && _callback) _callback(s);
}
