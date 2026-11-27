#include "Si1145.h"

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

Si1145Minimal::Si1145Minimal(RegisterConnection& connection)
    : _connection(connection) {}

bool Si1145Minimal::_read_reg(uint8_t reg, uint8_t& value) {
    _connection.read(reg, &value, 1);
    return _connection.ok();
}

bool Si1145Minimal::_write_reg(uint8_t reg, uint8_t value) {
    _connection.write(reg, &value, 1);
    return _connection.ok();
}

bool Si1145Minimal::init() {
    periph_delay_ms(25);
    uint8_t part;
    if (!_read_reg(REG_PART_ID, part) || part < 0x45 || part > 0x47) return false;
    _part_id = part;
    if (!_read_reg(REG_SEQ_ID, _seq_id)) return false;
    return _apply_defaults();
}

bool Si1145Minimal::_apply_defaults() {
    if (!_write_reg(REG_HW_KEY, HW_KEY)) return false;
    static const uint8_t ucoef[4] = {0x7B, 0x6B, 0x01, 0x00};
    _connection.write(REG_UCOEF0, ucoef, 4);
    if (!_connection.ok()) return false;
    const uint8_t chlist = CH_UV | CH_ALS_IR | CH_ALS_VIS | CH_PS1;
    if (!_param_set(PARAM_CHLIST, chlist)) return false;
    _chlist = chlist;
    if (!_param_set(PARAM_PSLED12_SELECT, 0x01)) return false;
    if (!_write_reg(REG_PS_LED21, 0x03)) return false;
    _irq_enable = SOURCE_ALS | SOURCE_PS1;
    return _write_reg(REG_IRQ_ENABLE, _irq_enable);
}

bool Si1145Minimal::_counter(uint8_t& counter) {
    uint8_t r;
    if (!_read_reg(REG_RESPONSE, r)) return false;
    if (r & 0x80) {
        if (!_write_reg(REG_COMMAND, CMD_NOP)) return false;
        if (!_read_reg(REG_RESPONSE, r)) return false;
    }
    counter = r & 0x0F;
    return true;
}

bool Si1145Minimal::_command(uint8_t cmd) {
    uint8_t before;
    if (!_counter(before)) return false;
    if (!_write_reg(REG_COMMAND, cmd)) return false;
    for (unsigned i = 0; i < CMD_POLLS; i++) {
        uint8_t r;
        if (!_read_reg(REG_RESPONSE, r)) return false;
        if (r & 0x80) {
            _write_reg(REG_COMMAND, CMD_NOP);
            return false;
        }
        if ((r & 0x0F) != before) return true;
        periph_delay_ms(1);
    }
    return false;
}

bool Si1145Minimal::_param_set(uint8_t offset, uint8_t value) {
    if (!_write_reg(REG_PARAM_WR, value)) return false;
    return _command((uint8_t)(CMD_PARAM_SET | (offset & 0x1F)));
}

bool Si1145Minimal::_param_query(uint8_t offset, uint8_t& value) {
    if (!_command((uint8_t)(CMD_PARAM_QUERY | (offset & 0x1F)))) return false;
    return _read_reg(REG_PARAM_RD, value);
}

bool Si1145Minimal::_clear_irq(uint8_t& status) {
    if (!_read_reg(REG_IRQ_STATUS, status)) return false;
    if (status) return _write_reg(REG_IRQ_STATUS, status);
    return true;
}

bool Si1145Minimal::_measure(bool proximity, bool als) {
    uint8_t cmd = proximity && als ? CMD_PSALS_FORCE : proximity ? CMD_PS_FORCE : CMD_ALS_FORCE;
    uint8_t s, c;
    if (!_clear_irq(s)) return false;
    if (!_counter(c)) return false;
    if (!_write_reg(REG_COMMAND, cmd)) return false;
    uint8_t wait = 0;
    if (als && (_chlist & CH_ALS)) wait |= SOURCE_ALS;
    if (proximity) wait |= (uint8_t)((_chlist & 0x07) << 2);
    wait &= _irq_enable;
    if (wait == 0) {
        periph_delay_ms(100);
        return true;
    }
    uint8_t seen = 0;
    for (unsigned i = 0; i < MEASURE_POLLS; i++) {
        if (!_clear_irq(s)) return false;
        seen |= s;
        if ((seen & wait) == wait) return true;
        periph_delay_ms(1);
    }
    return false;
}

bool Si1145Minimal::_read_data(uint16_t* values) {
    uint8_t b[12];
    _connection.read(REG_DATA, b, 12);
    if (!_connection.ok()) return false;
    for (int i = 0; i < 6; i++) values[i] = (uint16_t)(b[2 * i] | (b[2 * i + 1] << 8));
    return true;
}

bool Si1145Minimal::read(Si1145Reading& reading) {
    uint16_t v[6];
    if (!_measure(true, true) || !_read_data(v)) return false;
    reading.visible = v[0];
    reading.infrared = v[1];
    reading.proximity = v[2];
    reading.uv_index = v[5] / 100.0f;
    return true;
}

// --- Full ---

Si1145Full* Si1145Full::_activeInstance = nullptr;

Si1145Full::Si1145Full(RegisterConnection& connection) : Si1145Minimal(connection) {}

bool Si1145Full::read_id(uint8_t& part_id, uint8_t& rev_id, uint8_t& seq_id) {
    uint8_t b[3];
    _connection.read(REG_PART_ID, b, 3);
    if (!_connection.ok()) return false;
    part_id = b[0];
    rev_id = b[1];
    seq_id = b[2];
    return true;
}

bool Si1145Full::read_proximity(uint16_t& value, uint8_t channel) {
    if (channel < 1 || channel > _led_count()) return false;
    if (!(_chlist & (1 << (channel - 1)))) return false;
    uint16_t v[6];
    if (!_measure(true, false) || !_read_data(v)) return false;
    value = v[1 + channel];
    return true;
}

bool Si1145Full::read_uv_index(float& value) {
    if (!(_chlist & CH_UV)) return false;
    uint16_t v[6];
    if (!_measure(false, true) || !_read_data(v)) return false;
    value = v[5] / 100.0f;
    return true;
}

bool Si1145Full::read_visible(uint16_t& value) {
    if (!(_chlist & CH_ALS_VIS)) return false;
    uint16_t v[6];
    if (!_measure(false, true) || !_read_data(v)) return false;
    value = v[0];
    return true;
}

bool Si1145Full::read_infrared(uint16_t& value) {
    if (!(_chlist & CH_ALS_IR)) return false;
    uint16_t v[6];
    if (!_measure(false, true) || !_read_data(v)) return false;
    value = v[1];
    return true;
}

bool Si1145Full::read_raw(Si1145Raw& raw) {
    uint16_t v[6];
    if (!_read_data(v)) return false;
    raw.visible = v[0];
    raw.infrared = v[1];
    raw.ps1 = v[2];
    raw.ps2 = v[3];
    raw.ps3 = v[4];
    raw.aux = v[5];
    return true;
}

bool Si1145Full::_read_aux(uint8_t mux, uint16_t& value) {
    uint8_t saved = _chlist;
    if (!_param_set(PARAM_AUX_ADCMUX, mux)) return false;
    if (!_param_set(PARAM_CHLIST, CH_AUX)) return false;
    _chlist = CH_AUX;
    uint16_t v[6];
    bool ok = _measure(false, true) && _read_data(v);
    if (ok) value = v[5];
    bool restored = _param_set(PARAM_CHLIST, saved);
    _chlist = saved;
    return ok && restored;
}

bool Si1145Full::read_temperature_raw(uint16_t& value) { return _read_aux(AUX_TEMPERATURE, value); }

bool Si1145Full::read_vdd_raw(uint16_t& value) { return _read_aux(AUX_VDD, value); }

bool Si1145Full::set_channels(bool uv, bool visible, bool infrared, bool ps1, bool ps2, bool ps3,
                              bool aux) {
    if (uv && aux) return false;
    if ((ps2 && _led_count() < 2) || (ps3 && _led_count() < 3)) return false;
    uint8_t mask = (uint8_t)((uv ? CH_UV : 0) | (aux ? CH_AUX : 0) | (infrared ? CH_ALS_IR : 0) |
                             (visible ? CH_ALS_VIS : 0) | (ps3 ? CH_PS3 : 0) |
                             (ps2 ? CH_PS2 : 0) | (ps1 ? CH_PS1 : 0));
    if (!_param_set(PARAM_CHLIST, mask)) return false;
    _chlist = mask;
    return true;
}

bool Si1145Full::set_led_current(uint8_t led, uint8_t level) {
    if (led < 1 || led > _led_count() || level > 15) return false;
    if (led == 3) return _write_reg(REG_PS_LED3, level);
    uint8_t cur;
    if (!_read_reg(REG_PS_LED21, cur)) return false;
    cur = led == 1 ? (uint8_t)((cur & 0xF0) | level) : (uint8_t)((cur & 0x0F) | (level << 4));
    return _write_reg(REG_PS_LED21, cur);
}

bool Si1145Full::set_ps_led(uint8_t channel, uint8_t leds) {
    if (channel < 1 || channel > 3 || leds > (1 << _led_count()) - 1) return false;
    if (channel == 3) return _param_set(PARAM_PSLED3_SELECT, leds);
    uint8_t cur;
    if (!_param_query(PARAM_PSLED12_SELECT, cur)) return false;
    cur = channel == 1 ? (uint8_t)((cur & 0x70) | leds) : (uint8_t)((cur & 0x07) | (leds << 4));
    return _param_set(PARAM_PSLED12_SELECT, cur);
}

bool Si1145Full::set_ps_photodiode(uint8_t channel, bool large) {
    if (channel < 1 || channel > 3) return false;
    return _param_set((uint8_t)(PARAM_PS1_ADCMUX + channel - 1), large ? 0x03 : 0x00);
}

bool Si1145Full::set_gain(uint8_t visible, uint8_t infrared, uint8_t proximity) {
    if (visible > 7 || infrared > 7 || proximity > 7) return false;
    const uint8_t gains[3] = {visible, infrared, proximity};
    const uint8_t gain_off[3] = {PARAM_ALS_VIS_ADC_GAIN, PARAM_ALS_IR_ADC_GAIN, PARAM_PS_ADC_GAIN};
    const uint8_t count_off[3] = {PARAM_ALS_VIS_ADC_COUNTER, PARAM_ALS_IR_ADC_COUNTER,
                                  PARAM_PS_ADC_COUNTER};
    for (int i = 0; i < 3; i++) {
        if (!_param_set(gain_off[i], gains[i])) return false;
        if (!_param_set(count_off[i], (uint8_t)(((~gains[i]) & 0x07) << 4))) return false;
    }
    return true;
}

bool Si1145Full::set_high_range(bool visible, bool infrared, bool proximity) {
    return _param_set(PARAM_ALS_VIS_ADC_MISC, visible ? 0x20 : 0) &&
           _param_set(PARAM_ALS_IR_ADC_MISC, infrared ? 0x20 : 0) &&
           _param_set(PARAM_PS_ADC_MISC, proximity ? 0x24 : 0x04);
}

bool Si1145Full::set_alignment(bool visible, bool infrared, bool ps1, bool ps2, bool ps3) {
    return _param_set(PARAM_ALS_ENCODING, (uint8_t)((infrared ? 0x20 : 0) | (visible ? 0x10 : 0))) &&
           _param_set(PARAM_PS_ENCODING,
                      (uint8_t)((ps3 ? 0x40 : 0) | (ps2 ? 0x20 : 0) | (ps1 ? 0x10 : 0)));
}

bool Si1145Full::set_uv_coefficients(const uint8_t ucoef[4]) {
    _connection.write(REG_UCOEF0, ucoef, 4);
    return _connection.ok();
}

bool Si1145Full::read_calibration(uint8_t cal[12]) {
    if (!_command(CMD_GET_CAL)) return false;
    _connection.read(REG_DATA, cal, 12);
    return _connection.ok();
}

bool Si1145Full::start_autonomous(float interval_s, bool proximity, bool als) {
    long rate = (long)(interval_s / 31.25e-6f + 0.5f);
    if (rate < 1 || rate > 0xFFFF || !(proximity || als)) return false;
    if (_seq_id == 0x01) {
        if (!_write_reg(REG_PS_RATE, (uint8_t)(rate & 0xFF))) return false;
        if (!_write_reg(REG_MEAS_RATE0, (uint8_t)(rate >> 8))) return false;
    } else {
        uint8_t b[2] = {(uint8_t)(rate & 0xFF), (uint8_t)(rate >> 8)};
        _connection.write(REG_MEAS_RATE0, b, 2);
        if (!_connection.ok()) return false;
    }
    return _command(proximity && als ? CMD_PSALS_AUTO : proximity ? CMD_PS_AUTO : CMD_ALS_AUTO);
}

bool Si1145Full::pause() { return _command(CMD_PSALS_PAUSE); }

bool Si1145Full::force(bool proximity, bool als) {
    if (!(proximity || als)) return false;
    return _command(proximity && als ? CMD_PSALS_FORCE : proximity ? CMD_PS_FORCE : CMD_ALS_FORCE);
}

bool Si1145Full::read_response(uint8_t& counter, uint8_t& error) {
    uint8_t r;
    if (!_read_reg(REG_RESPONSE, r)) return false;
    if (r & 0x80) {
        counter = 0;
        error = r;
    } else {
        counter = r & 0x0F;
        error = 0;
    }
    return true;
}

bool Si1145Full::set_i2c_address(uint8_t address) {
    if (address < 0x08 || address > 0x77) return false;
    if (!_param_set(PARAM_I2C_ADDR, address)) return false;
    return _write_reg(REG_COMMAND, CMD_BUSADDR);
}

bool Si1145Full::reset() {
    if (!_write_reg(REG_COMMAND, CMD_RESET)) return false;
    periph_delay_ms(10);
    return _apply_defaults();
}

bool Si1145Full::enable_interrupt(uint8_t source) {
    _irq_enable = (uint8_t)((_irq_enable | source) & SOURCE_MASK);
    return _write_reg(REG_INT_CFG, 0x01) && _write_reg(REG_IRQ_ENABLE, _irq_enable);
}

bool Si1145Full::disable_interrupt(uint8_t source) {
    _irq_enable = (uint8_t)(_irq_enable & ~source & SOURCE_MASK);
    return _write_reg(REG_IRQ_ENABLE, _irq_enable);
}

uint8_t Si1145Full::poll_interrupt() {
    uint8_t s;
    if (!_clear_irq(s)) return 0;
    return (uint8_t)(s & _irq_enable);
}

void Si1145Full::on_interrupt(void (*callback)(uint8_t), InputPin* intPin) {
    InputPin* p = intPin ? intPin : _connection.intPin();
    if (!p) return;  // no INT line wired: the caller polls poll_interrupt()
    _callback = callback;
    _intPinUsed = p;
    _activeInstance = this;
    p->onEdge(&Si1145Full::_edgeTrampoline, InputPin::kFalling);
}

void Si1145Full::off_interrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&Si1145Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
    if (_activeInstance == this) _activeInstance = nullptr;
}

void Si1145Full::_edgeTrampoline() {
    if (_activeInstance) _activeInstance->_handleEdge();
}

void Si1145Full::_handleEdge() {
    uint8_t s = poll_interrupt();
    if (s && _callback) _callback(s);
}
