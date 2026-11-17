#include "HT16K33.h"
#include <string.h>

HT16K33Minimal::HT16K33Minimal(Connection& connection)
    : _connection(connection), _brightness(15), _blink(0), _display(true), _row_int(0xA0) {
    memset(_ram, 0, sizeof(_ram));
    _command(CMD_SYSTEM | 0x01);
    _command(_row_int);
    _command(CMD_DIMMING | _brightness);
    show();
    _apply_display();
}

void HT16K33Minimal::_command(uint8_t byte) {
    _connection.write(&byte, 1);
}

void HT16K33Minimal::_apply_display() {
    _command(CMD_DISPLAY | (_blink << 1) | (_display ? 1 : 0));
}

void HT16K33Minimal::clear() {
    memset(_ram, 0, sizeof(_ram));
    show();
}

bool HT16K33Minimal::set_pixel(uint8_t com, uint8_t row, bool on) {
    if (com > 7 || row > 15) return false;
    uint8_t idx = 2 * com + (row >> 3);
    uint8_t mask = 1u << (row & 7);
    if (on) _ram[idx] |= mask;
    else    _ram[idx] &= (uint8_t)~mask;
    return true;
}

bool HT16K33Minimal::write_raw(const uint8_t* data, size_t len, uint8_t address) {
    if (len == 0 || address > 15 || (size_t)address + len > 16) return false;
    memcpy(&_ram[address], data, len);
    uint8_t buf[17];
    buf[0] = address;
    memcpy(&buf[1], data, len);
    _connection.write(buf, len + 1);
    return true;
}

void HT16K33Minimal::show() {
    uint8_t buf[17];
    buf[0] = 0x00;
    memcpy(&buf[1], _ram, 16);
    _connection.write(buf, 17);
}

void HT16K33Minimal::set_brightness(uint8_t level) {
    if (level > 15) return;
    _brightness = level;
    _command(CMD_DIMMING | level);
}

// HT16K33Full

HT16K33Full* HT16K33Full::_active_instance = nullptr;

HT16K33Full::HT16K33Full(Connection& connection)
    : HT16K33Minimal(connection), _callback(nullptr), _int_pin_used(nullptr) {}

void HT16K33Full::display_on()  { _display = true;  _apply_display(); }
void HT16K33Full::display_off() { _display = false; _apply_display(); }

bool HT16K33Full::set_blink(float frequency) {
    uint8_t code;
    if (frequency == 0.0f)      code = 0;
    else if (frequency == 2.0f) code = 1;
    else if (frequency == 1.0f) code = 2;
    else if (frequency == 0.5f) code = 3;
    else return false;
    _blink = code;
    _apply_display();
    return true;
}

void HT16K33Full::standby() { _command(CMD_SYSTEM); }

void HT16K33Full::wake() {
    _command(CMD_SYSTEM | 0x01);
    _command(_row_int);
    _command(CMD_DIMMING | _brightness);
    show();
    _apply_display();
}

void HT16K33Full::set_int_output(bool enable, bool active_high) {
    _row_int = enable ? (active_high ? 0xA3 : 0xA1) : 0xA0;
    _command(_row_int);
}

uint64_t HT16K33Full::read_keys() {
    uint8_t ptr = CMD_KEY_PTR;
    uint8_t b[6] = {0};
    _connection.write(&ptr, 1);
    _connection.read(b, 6);
    uint64_t mask = 0;
    for (uint8_t k = 0; k < 3; k++) {
        uint64_t word = (uint64_t)((b[2 * k] | (b[2 * k + 1] << 8)) & 0x1FFF);
        mask |= word << (13 * k);
    }
    return mask;
}

bool HT16K33Full::key_pressed() {
    uint8_t ptr = CMD_INT_PTR;
    uint8_t flag = 0;
    _connection.write(&ptr, 1);
    _connection.read(&flag, 1);
    return (flag & 0x01) != 0;
}

bool HT16K33Full::get_pixel(uint8_t com, uint8_t row) const {
    if (com > 7 || row > 15) return false;
    return ((_ram[2 * com + (row >> 3)] >> (row & 7)) & 1) != 0;
}

uint64_t HT16K33Full::poll_interrupt() {
    if (!key_pressed()) return 0;
    return read_keys();
}

void HT16K33Full::_edge_trampoline() {
    if (_active_instance) _active_instance->service();
}

void HT16K33Full::service() {
    uint64_t keys = poll_interrupt();
    if (keys && _callback) _callback(keys);
}

void HT16K33Full::on_interrupt(void (*callback)(uint64_t), InputPin* intPin) {
    _callback = callback;
    InputPin* pin = intPin ? intPin : _connection.intPin();
    _int_pin_used = pin;
    if (!pin) return;
    _active_instance = this;
    pin->onEdge(&HT16K33Full::_edge_trampoline,
                _row_int == 0xA3 ? InputPin::kRising : InputPin::kFalling);
}

void HT16K33Full::off_interrupt() {
    if (_int_pin_used) {
        _int_pin_used->offEdge(&HT16K33Full::_edge_trampoline);
        _int_pin_used = nullptr;
    }
    _callback = nullptr;
}
