#pragma once
#ifdef __linux__
#include "InputPin.h"
#include <thread>
#include <atomic>

struct gpiod_line;

/** @brief InputPin for Linux GCC: a background thread polling a libgpiod line.
 *
 * @param line libgpiod line requested as input.
 * @param pollIntervalMs Polling period in milliseconds (default 5).
 */
class InputPinLinux : public InputPin {
public:
    explicit InputPinLinux(struct gpiod_line* line, unsigned pollIntervalMs = 5);
    ~InputPinLinux() override;

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override;
    void offEdge(Handler handler) override;

private:
    void pollLoop();

    struct gpiod_line* _line;
    unsigned _pollIntervalMs;
    uint8_t _trigger;
    Handler _handlers[kMaxHandlers] = {};
    std::thread _thread;
    std::atomic<bool> _running{false};
    int _last;
};
#endif
