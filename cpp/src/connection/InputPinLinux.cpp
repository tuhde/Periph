#ifdef __linux__
#include "InputPinLinux.h"
#include <gpiod.h>
#include <chrono>

InputPinLinux::InputPinLinux(struct gpiod_line* line, unsigned pollIntervalMs)
    : _line(line), _pollIntervalMs(pollIntervalMs), _trigger(kFalling),
      _last(gpiod_line_get_value(line)) {}

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
        int value = gpiod_line_get_value(_line);
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
