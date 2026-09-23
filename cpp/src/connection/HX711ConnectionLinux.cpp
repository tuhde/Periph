#ifdef __linux__
#include "HX711ConnectionLinux.h"
#include <unistd.h>

HX711ConnectionLinux::HX711ConnectionLinux(const char* chip_path, unsigned int dout_line,
                                           unsigned int pd_sck_line)
    : _dout(chip_path, dout_line, GpiodLineLinux::Mode::Input, false, "hx711-dout"),
      _sck(chip_path, pd_sck_line, GpiodLineLinux::Mode::Output, false, "hx711-sck")
{
}

HX711ConnectionLinux::~HX711ConnectionLinux() {
    close();
}

bool HX711ConnectionLinux::is_ready() {
    return _dout.get() == 0;
}

int32_t HX711ConnectionLinux::read_raw(uint8_t num_pulses) {
    if (!_enabled) return 0;
    if (num_pulses != 25 && num_pulses != 26 && num_pulses != 27)
        return INT32_MIN;
    for (int polls = 0; _dout.get() != 0; ++polls) {
        if (polls >= 1000) return INT32_MIN;
        usleep(1000);
    }
    uint32_t raw = 0;
    for (uint8_t i = 0; i < num_pulses; i++) {
        _sck.set(true);
        _sck.set(false);
        raw = (raw << 1) | (_dout.get() == 1 ? 1u : 0u);
    }
    raw >>= num_pulses - 24;
    if (raw & 0x800000u)
        return static_cast<int32_t>(raw) - 0x1000000;
    return static_cast<int32_t>(raw);
}

void HX711ConnectionLinux::power_down() {
    _sck.set(true);
    usleep(65);
}

void HX711ConnectionLinux::power_up() {
    _sck.set(false);
}

void HX711ConnectionLinux::close() {
    _dout.release();
    _sck.release();
}
#endif // __linux__
