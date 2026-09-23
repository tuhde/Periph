#ifdef __linux__
#include "InputPinLinux.h"
#include <chrono>

InputPinLinux::InputPinLinux(const char* chip_path, unsigned int offset,
                             unsigned pollIntervalMs, bool pull_up)
    : _line(chip_path, offset,
            pull_up ? GpiodLineLinux::Mode::InputPullUp : GpiodLineLinux::Mode::Input,
            false, "periph-int"),
      _pollIntervalMs(pollIntervalMs), _trigger(kFalling), _last(_line.get()) {}

InputPinLinux::~InputPinLinux() {
    _running = false;
    if (_thread.joinable()) _thread.join();
}

bool InputPinLinux::onEdge(Handler handler, uint8_t trigger) {
    _trigger = trigger;
    bool added = addHandler(_handlers, handler);
    if (added && !_running) {
        _running = true;
        _thread = std::thread(&InputPinLinux::pollLoop, this);
    }
    return added;
}

void InputPinLinux::offEdge(Handler handler) {
    removeHandler(_handlers, handler);
}

void InputPinLinux::pollLoop() {
    while (_running) {
        int value = _line.get();
        if (value != _last) {
            bool rising  = value == 1 && _last == 0;
            bool falling = value == 0 && _last == 1;
            if (_trigger == kChange || (_trigger == kRising && rising) || (_trigger == kFalling && falling)) {
                dispatch(_handlers);
            }
            _last = value;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(_pollIntervalMs));
    }
}
#endif
