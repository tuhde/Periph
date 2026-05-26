#ifdef __linux__
#include "HX711TransportLinux.h"
#include <gpiod.h>
#include <unistd.h>

HX711TransportLinux::HX711TransportLinux(struct gpiod_line* dout, struct gpiod_line* pd_sck)
    : _dout(dout), _sck(pd_sck)
{
    gpiod_line_set_value(_sck, 0);
}

HX711TransportLinux::~HX711TransportLinux() {
    close();
}

bool HX711TransportLinux::is_ready() {
    return gpiod_line_get_value(_dout) == 0;
}

int32_t HX711TransportLinux::read_raw(uint8_t num_pulses) {
    if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
        return INT32_MIN;
    for (int polls = 0; gpiod_line_get_value(_dout) != 0; ++polls) {
        if (polls >= 1000) return INT32_MIN;
        usleep(1000);
    }
    uint32_t raw = 0;
    for (uint8_t i = 0; i < num_pulses; i++) {
        gpiod_line_set_value(_sck, 1);
        gpiod_line_set_value(_sck, 0);
        raw = (raw << 1) | static_cast<uint32_t>(gpiod_line_get_value(_dout));
    }
    raw >>= num_pulses - 24;
    if (raw & 0x800000u)
        return static_cast<int32_t>(raw) - 0x1000000;
    return static_cast<int32_t>(raw);
}

void HX711TransportLinux::power_down() {
    gpiod_line_set_value(_sck, 1);
    usleep(65);
}

void HX711TransportLinux::power_up() {
    gpiod_line_set_value(_sck, 0);
}

void HX711TransportLinux::close() {
    if (_dout)  { gpiod_line_release(_dout);  _dout = nullptr; }
    if (_sck)   { gpiod_line_release(_sck);   _sck  = nullptr; }
}
#endif // __linux__
