#include "AD5248.h"
#include <math.h>
#include <stdlib.h>

AD5248Minimal::AD5248Minimal(RegisterConnection& connection, float r_ab, float r_wiper)
    : _connection(connection), _r_ab(r_ab), _r_wiper(r_wiper) {
    _shutdown[0] = false;
    _shutdown[1] = false;
}

void AD5248Minimal::_check_channel(uint8_t channel) {
    if (channel > 1) abort();
}

uint8_t AD5248Minimal::_instruction(uint8_t channel) {
    return (uint8_t)((channel << 7) | (_shutdown[channel] ? SD_BIT : 0));
}

void AD5248Minimal::set_wiper(uint8_t channel, int code) {
    _check_channel(channel);
    if (code < 0) code = 0;
    if (code > 255) code = 255;
    uint8_t value = (uint8_t)code;
    _connection.write(_instruction(channel), &value, 1);
}

uint8_t AD5248Minimal::get_wiper(uint8_t channel) {
    _check_channel(channel);
    uint8_t buf[1];
    _connection.read(_instruction(channel), buf, 1);
    return buf[0];
}

void AD5248Minimal::set_resistance(uint8_t channel, float ohms) {
    _check_channel(channel);
    float code = roundf((ohms - 2.0f * _r_wiper) / _r_ab * 256.0f);
    if (code < 0.0f) code = 0.0f;
    if (code > 255.0f) code = 255.0f;
    set_wiper(channel, (int)code);
}

float AD5248Minimal::get_resistance(uint8_t channel) {
    return (float)get_wiper(channel) / 256.0f * _r_ab + 2.0f * _r_wiper;
}

void AD5248Full::set_both(int code1, int code2) {
    set_wiper(0, code1);
    set_wiper(1, code2);
}

void AD5248Full::set_ratio(uint8_t channel, float ratio) {
    if (ratio < 0.0f) ratio = 0.0f;
    if (ratio > 1.0f) ratio = 1.0f;
    int code = (int)roundf(ratio * 256.0f);
    if (code > 255) code = 255;
    set_wiper(channel, code);
}

float AD5248Full::get_ratio(uint8_t channel) {
    return (float)get_wiper(channel) / 256.0f;
}

uint8_t AD5248Full::increment(uint8_t channel, int steps) {
    int code = (int)get_wiper(channel) + steps;
    if (code < 0) code = 0;
    if (code > 255) code = 255;
    set_wiper(channel, code);
    return (uint8_t)code;
}

void AD5248Full::shutdown(uint8_t channel) {
    _check_channel(channel);
    uint8_t code = get_wiper(channel);
    _shutdown[channel] = true;
    _connection.write(_instruction(channel), &code, 1);
}

void AD5248Full::resume(uint8_t channel) {
    _check_channel(channel);
    uint8_t code = get_wiper(channel);
    _shutdown[channel] = false;
    _connection.write(_instruction(channel), &code, 1);
}

bool AD5248Full::is_shutdown(uint8_t channel) {
    _check_channel(channel);
    return _shutdown[channel];
}

void AD5248Full::reset() {
    _shutdown[0] = false;
    _shutdown[1] = false;
    set_wiper(0, 0x80);
    set_wiper(1, 0x80);
}

void AD5248Full::set_rab(float r_ab, float r_wiper) {
    _r_ab = r_ab;
    if (r_wiper >= 0.0f) _r_wiper = r_wiper;
}
