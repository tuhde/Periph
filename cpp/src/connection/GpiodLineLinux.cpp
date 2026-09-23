#ifdef __linux__
#include "GpiodLineLinux.h"
#include <gpiod.h>

GpiodLineLinux::GpiodLineLinux(const char* chip_path, unsigned int offset, Mode mode,
                               bool initial_high, const char* consumer)
    : _chip(gpiod_chip_open(chip_path)), _request(nullptr), _offset(offset)
{
    if (!_chip) return;
    gpiod_line_settings* settings = gpiod_line_settings_new();
    if (mode == Mode::Output) {
        gpiod_line_settings_set_direction(settings, GPIOD_LINE_DIRECTION_OUTPUT);
        gpiod_line_settings_set_output_value(settings,
            initial_high ? GPIOD_LINE_VALUE_ACTIVE : GPIOD_LINE_VALUE_INACTIVE);
    } else {
        gpiod_line_settings_set_direction(settings, GPIOD_LINE_DIRECTION_INPUT);
        if (mode == Mode::InputPullUp)
            gpiod_line_settings_set_bias(settings, GPIOD_LINE_BIAS_PULL_UP);
    }
    gpiod_line_config* line_cfg = gpiod_line_config_new();
    gpiod_line_config_add_line_settings(line_cfg, &_offset, 1, settings);
    gpiod_request_config* req_cfg = gpiod_request_config_new();
    gpiod_request_config_set_consumer(req_cfg, consumer);
    _request = gpiod_chip_request_lines(_chip, req_cfg, line_cfg);
    gpiod_request_config_free(req_cfg);
    gpiod_line_config_free(line_cfg);
    gpiod_line_settings_free(settings);
}

GpiodLineLinux::~GpiodLineLinux() {
    release();
}

int GpiodLineLinux::get() {
    if (!_request) return -1;
    gpiod_line_value v = gpiod_line_request_get_value(_request, _offset);
    if (v == GPIOD_LINE_VALUE_ERROR) return -1;
    return v == GPIOD_LINE_VALUE_ACTIVE ? 1 : 0;
}

void GpiodLineLinux::set(bool high) {
    if (!_request) return;
    gpiod_line_request_set_value(_request, _offset,
        high ? GPIOD_LINE_VALUE_ACTIVE : GPIOD_LINE_VALUE_INACTIVE);
}

void GpiodLineLinux::release() {
    if (_request) { gpiod_line_request_release(_request); _request = nullptr; }
    if (_chip)    { gpiod_chip_close(_chip);              _chip = nullptr; }
}

#endif // __linux__
