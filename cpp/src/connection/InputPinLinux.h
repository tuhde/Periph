#pragma once
#ifdef __linux__
#include "InputPin.h"
#include "GpiodLineLinux.h"
#include <thread>
#include <atomic>

/** @brief InputPin for Linux GCC: a background thread polling a libgpiod v2 line.
 *
 * @param chip_path GPIO chip device, e.g. "/dev/gpiochip0".
 * @param offset Line offset on that chip (as shown by gpioinfo).
 * @param pollIntervalMs Polling period in milliseconds (default 5).
 * @param pull_up Request the line with the internal pull-up bias (default
 *        true — INT lines are typically open-drain, active-low).
 */
class InputPinLinux : public InputPin {
public:
    InputPinLinux(const char* chip_path, unsigned int offset,
                  unsigned pollIntervalMs = 5, bool pull_up = true);
    ~InputPinLinux() override;

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override;
    void offEdge(Handler handler) override;

private:
    void pollLoop();

    GpiodLineLinux _line;
    unsigned _pollIntervalMs;
    uint8_t _trigger;
    Handler _handlers[kMaxHandlers] = {};
    std::thread _thread;
    std::atomic<bool> _running{false};
    int _last;
};
#endif
