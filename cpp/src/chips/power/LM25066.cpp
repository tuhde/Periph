#include "LM25066.h"
#include <math.h>

// Out-of-class definitions for ODR-used static constexpr members (AVR builds as C++11).
constexpr uint8_t  LM25066Minimal::CMD_READ_VIN;
constexpr uint8_t  LM25066Minimal::CMD_READ_VOUT;
constexpr uint8_t  LM25066Minimal::CMD_READ_TEMP;
constexpr uint8_t  LM25066Minimal::CMD_READ_IIN;
constexpr uint8_t  LM25066Minimal::CMD_READ_PIN;
constexpr uint16_t LM25066Full::SOURCE_VOUT_UV_WARN;
constexpr uint16_t LM25066Full::SOURCE_IIN_WARN;
constexpr uint16_t LM25066Full::SOURCE_VIN_UV_WARN;
constexpr uint16_t LM25066Full::SOURCE_VIN_OV_WARN;
constexpr uint16_t LM25066Full::SOURCE_POWER_GOOD;
constexpr uint16_t LM25066Full::SOURCE_OT_WARN;
constexpr uint16_t LM25066Full::SOURCE_PIN_OP_WARN;
constexpr uint16_t LM25066Full::SOURCE_MOSFET_SHORTED;
constexpr uint16_t LM25066Full::SOURCE_VIN_UV_FAULT;
constexpr uint16_t LM25066Full::SOURCE_VIN_OV_FAULT;
constexpr uint16_t LM25066Full::SOURCE_IIN_FAULT;
constexpr uint16_t LM25066Full::SOURCE_OT_FAULT;
constexpr uint16_t LM25066Full::SOURCE_CML_FAULT;
constexpr uint16_t LM25066Full::SOURCE_CB_FAULT;

LM25066Full* LM25066Full::_activeInstance = nullptr;

LM25066Minimal::LM25066Minimal(RegisterConnection& connection, float rSenseMohm, bool currentLimitHigh)
    : _connection(connection), _rs(rSenseMohm) {
    _volt = {22070.0f, -1800.0f, -2};
    _vaux = {3546.0f, -3.0f, 0};
    _temp = {16000.0f, 0.0f, -3};
    _set_cl(currentLimitHigh);
}

void LM25066Minimal::_set_cl(bool high) {
    _clHigh = high;
    if (high) {
        _amp  = {6854.0f * _rs, -3100.0f, -2};
        _watt = {369.0f * _rs, -1900.0f, -2};
    } else {
        _amp  = {13661.0f * _rs, -5200.0f, -2};
        _watt = {736.0f * _rs, -3300.0f, -2};
    }
}

float LM25066Minimal::_from_y(uint16_t y, const Coef& c) {
    float scale = 1.0f;
    for (int i = 0; i < -c.r; i++) scale *= 10.0f;
    for (int i = 0; i < c.r; i++) scale /= 10.0f;
    return ((float)y * scale - c.b) / c.m;
}

uint16_t LM25066Minimal::_to_y(float x, const Coef& c) {
    float scale = 1.0f;
    for (int i = 0; i < c.r; i++) scale *= 10.0f;
    for (int i = 0; i < -c.r; i++) scale /= 10.0f;
    float y = roundf((c.m * x + c.b) * scale);
    if (y < 0.0f) y = 0.0f;
    if (y > 0x0FFE) y = 0x0FFE;
    return (uint16_t)y;
}

uint16_t LM25066Minimal::_read_word(uint8_t cmd) {
    uint8_t buf[2] = {0, 0};
    _connection.read(cmd, buf, 2);
    return (uint16_t)(buf[0] | (buf[1] << 8));
}

void LM25066Minimal::_write_word(uint8_t cmd, uint16_t value) {
    uint8_t buf[2] = {(uint8_t)(value & 0xFF), (uint8_t)(value >> 8)};
    _connection.write(cmd, buf, 2);
}

uint8_t LM25066Minimal::_read_byte(uint8_t cmd) {
    uint8_t b = 0;
    _connection.read(cmd, &b, 1);
    return b;
}

float LM25066Minimal::voltage_in()  { return _from_y(_read_y(CMD_READ_VIN), _volt); }
float LM25066Minimal::voltage_out() { return _from_y(_read_y(CMD_READ_VOUT), _volt); }
float LM25066Minimal::current()     { return _from_y(_read_y(CMD_READ_IIN), _amp); }
float LM25066Minimal::power()       { return _from_y(_read_y(CMD_READ_PIN), _watt); }
float LM25066Minimal::temperature() { return _temp_c(_read_word(CMD_READ_TEMP)); }

LM25066Full::LM25066Full(RegisterConnection& connection, float rSenseMohm, bool currentLimitHigh)
    : LM25066Minimal(connection, rSenseMohm, currentLimitHigh) {}

LM25066Block LM25066Full::_block(uint8_t cmd) {
    uint8_t raw[13] = {0};
    _connection.read(cmd, raw, 13);
    LM25066Block b = {0, 0, 0, 0, 0, 0};
    _valid = (raw[0] == 12);
    if (!_valid) return b;
    uint16_t w[6];
    for (int i = 0; i < 6; i++) w[i] = (uint16_t)(raw[1 + 2 * i] | (raw[2 + 2 * i] << 8));
    b.diagnostic  = w[0];
    b.current     = _from_y(w[1] & 0x0FFF, _amp);
    b.voltage_out = _from_y(w[2] & 0x0FFF, _volt);
    b.voltage_in  = _from_y(w[3] & 0x0FFF, _volt);
    b.power       = _from_y(w[4] & 0x0FFF, _watt);
    b.temperature = _temp_c(w[5]);
    return b;
}

void LM25066Full::_text(uint8_t cmd, uint8_t n, char* buf, size_t len) {
    uint8_t raw[10] = {0};
    _connection.read(cmd, raw, (size_t)n + 1);
    _valid = (raw[0] == n);
    size_t o = 0;
    if (_valid)
        for (uint8_t i = 1; i <= n && o + 1 < len; i++)
            if (raw[i] != 0) buf[o++] = (char)raw[i];
    if (len > 0) buf[o] = '\0';
}

float LM25066Full::voltage_aux()     { return _from_y(_read_y(CMD_READ_VAUX), _vaux); }
LM25066Block LM25066Full::read_block()   { return _block(CMD_BLOCK_READ); }
LM25066Block LM25066Full::read_average() { return _block(CMD_AVG_BLOCK_READ); }
LM25066Block LM25066Full::black_box()    { return _block(CMD_BLACK_BOX); }
float LM25066Full::voltage_in_avg()  { return _from_y(_read_y(CMD_READ_AVG_VIN), _volt); }
float LM25066Full::voltage_out_avg() { return _from_y(_read_y(CMD_READ_AVG_VOUT), _volt); }
float LM25066Full::current_avg()     { return _from_y(_read_y(CMD_READ_AVG_IIN), _amp); }
float LM25066Full::power_avg()       { return _from_y(_read_y(CMD_READ_AVG_PIN), _watt); }
float LM25066Full::power_peak()      { return _from_y(_read_y(CMD_READ_PIN_PEAK), _watt); }

void LM25066Full::clear_power_peak() { _connection.write(CMD_CLEAR_PIN_PEAK, nullptr, 0); }

bool LM25066Full::set_averaging(uint8_t exponent) {
    if (exponent > 12) return false;
    _connection.write(CMD_SAMPLES_FOR_AVG, &exponent, 1);
    return true;
}

uint8_t LM25066Full::get_averaging() { return _read_byte(CMD_SAMPLES_FOR_AVG); }

void LM25066Full::_set_limit(uint8_t cmd, float value, const Coef& c, uint16_t disabled) {
    _write_word(cmd, isnan(value) ? disabled : _to_y(value, c));
}

float LM25066Full::_get_limit(uint8_t cmd, const Coef& c, uint16_t disabled) {
    uint16_t y = _read_y(cmd);
    if (y == disabled) return NAN;
    return _from_y(y, c);
}

void LM25066Full::set_vin_ov_warn(float v)   { _set_limit(CMD_VIN_OV_WARN, v, _volt, 0x0FFF); }
void LM25066Full::set_vin_uv_warn(float v)   { _set_limit(CMD_VIN_UV_WARN, v, _volt, 0x0000); }
void LM25066Full::set_vout_uv_warn(float v)  { _set_limit(CMD_VOUT_UV_WARN, v, _volt, 0x0000); }
void LM25066Full::set_ot_warn(float c)       { _set_limit(CMD_OT_WARN, c, _temp, 0x0FFF); }
void LM25066Full::set_ot_fault(float c)      { _set_limit(CMD_OT_FAULT, c, _temp, 0x0FFF); }
void LM25066Full::set_iin_warn(float a)      { _set_limit(CMD_IIN_WARN, a, _amp, 0x0FFF); }
void LM25066Full::set_pin_warn(float w)      { _set_limit(CMD_PIN_WARN, w, _watt, 0x0FFF); }

LM25066Limits LM25066Full::get_limits() {
    LM25066Limits l;
    l.vin_ov_warn  = _get_limit(CMD_VIN_OV_WARN, _volt, 0x0FFF);
    l.vin_uv_warn  = _get_limit(CMD_VIN_UV_WARN, _volt, 0x0000);
    l.vout_uv_warn = _get_limit(CMD_VOUT_UV_WARN, _volt, 0x0000);
    l.ot_warn      = _get_limit(CMD_OT_WARN, _temp, 0x0FFF);
    l.ot_fault     = _get_limit(CMD_OT_FAULT, _temp, 0x0FFF);
    l.iin_warn     = _get_limit(CMD_IIN_WARN, _amp, 0x0FFF);
    l.pin_warn     = _get_limit(CMD_PIN_WARN, _watt, 0x0FFF);
    return l;
}

uint8_t  LM25066Full::status_byte()        { return _read_byte(CMD_STATUS_BYTE); }
uint16_t LM25066Full::status_word()        { return _read_word(CMD_STATUS_WORD); }
uint8_t  LM25066Full::status_vout()        { return _read_byte(CMD_STATUS_VOUT); }
uint8_t  LM25066Full::status_input()       { return _read_byte(CMD_STATUS_INPUT); }
uint8_t  LM25066Full::status_temperature() { return _read_byte(CMD_STATUS_TEMP); }
uint8_t  LM25066Full::status_cml()         { return _read_byte(CMD_STATUS_CML); }
uint8_t  LM25066Full::status_mfr()         { return _read_byte(CMD_STATUS_MFR); }
uint16_t LM25066Full::diagnostic_word()    { return _read_word(CMD_DIAGNOSTIC); }

void LM25066Full::clear_faults() { _connection.write(CMD_CLEAR_FAULTS, nullptr, 0); }

void LM25066Full::operation_on() {
    uint8_t v = 0x80;
    _connection.write(CMD_OPERATION, &v, 1);
}

void LM25066Full::operation_off() {
    uint8_t v = 0x00;
    _connection.write(CMD_OPERATION, &v, 1);
}

bool LM25066Full::is_on() {
    return _read_byte(CMD_OPERATION) == 0x80 && !(_read_byte(CMD_STATUS_BYTE) & 0x40);
}

uint8_t LM25066Full::device_setup() { return _read_byte(CMD_DEVICE_SETUP); }

bool LM25066Full::configure_device(int8_t retryCode, int8_t currentLimitHigh, int8_t cbRatioHigh) {
    if (retryCode > 7) return false;
    uint8_t value = 0;
    if (retryCode >= 0) value |= (uint8_t)(retryCode << 5);
    if (currentLimitHigh >= 0) value |= 0x04 | (currentLimitHigh ? 0x10 : 0);
    if (cbRatioHigh >= 0) value |= 0x02 | (cbRatioHigh ? 0x08 : 0);
    _connection.write(CMD_DEVICE_SETUP, &value, 1);
    if (currentLimitHigh >= 0) _set_cl(currentLimitHigh != 0);
    return true;
}

bool LM25066Full::set_gate_mask(uint8_t mask) {
    if (mask & ~0x34) return false;
    _connection.write(CMD_GATE_MASK, &mask, 1);
    return true;
}

uint8_t LM25066Full::get_gate_mask() { return _read_byte(CMD_GATE_MASK); }
uint8_t LM25066Full::capability()    { return _read_byte(CMD_CAPABILITY); }

void LM25066Full::manufacturer_id(char* buf, size_t len) { _text(CMD_MFR_ID, 3, buf, len); }
void LM25066Full::model(char* buf, size_t len)           { _text(CMD_MFR_MODEL, 8, buf, len); }
void LM25066Full::revision(char* buf, size_t len)        { _text(CMD_MFR_REVISION, 2, buf, len); }

void LM25066Full::enable_interrupt(uint16_t source) {
    _write_word(CMD_ALERT_MASK, (uint16_t)(_read_word(CMD_ALERT_MASK) & ~source));
}

void LM25066Full::disable_interrupt(uint16_t source) {
    _write_word(CMD_ALERT_MASK, (uint16_t)(_read_word(CMD_ALERT_MASK) | source));
}

void LM25066Full::onInterrupt(void (*callback)(uint16_t), InputPin* intPin) {
    _callback = callback;
    _intPinUsed = intPin ? intPin : _connection.intPin();
    if (!_intPinUsed) return;
    _activeInstance = this;
    _intPinUsed->onEdge(&LM25066Full::_edgeTrampoline, InputPin::kFalling);
}

void LM25066Full::offInterrupt() {
    if (_intPinUsed) {
        _intPinUsed->offEdge(&LM25066Full::_edgeTrampoline);
        _intPinUsed = nullptr;
    }
    _callback = nullptr;
}

uint16_t LM25066Full::pollInterrupt() { return _read_word(CMD_DIAGNOSTIC); }

void LM25066Full::_edgeTrampoline() {
    if (!_activeInstance) return;
    uint16_t status = _activeInstance->pollInterrupt();
    if (status && _activeInstance->_callback) _activeInstance->_callback(status);
}
