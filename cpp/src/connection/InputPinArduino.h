#pragma once
#include <Arduino.h>
#include "InputPin.h"

/** @brief InputPin for Arduino, backed by attachInterrupt().
 *
 * Arduino's attachInterrupt() takes a plain function pointer with no context
 * argument, so this class claims one of a small fixed set of static
 * trampoline functions at onEdge() time — at most kMaxInstances
 * InputPinArduino objects may be active concurrently across the whole sketch.
 *
 * @param pin Arduino GPIO pin number; configured INPUT_PULLUP at construction.
 */
class InputPinArduino : public InputPin {
public:
    explicit InputPinArduino(uint8_t pin);

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override;
    void offEdge(Handler handler) override;

private:
    static constexpr uint8_t kMaxInstances = 8;

    static int claimSlot(InputPinArduino* self);

    static void t0();
    static void t1();
    static void t2();
    static void t3();
    static void t4();
    static void t5();
    static void t6();
    static void t7();

    static InputPinArduino* _instances[kMaxInstances];
    static void (*const kTrampolines[kMaxInstances])();

    uint8_t _pin;
    Handler _handlers[kMaxHandlers] = {};
    bool _armed = false;
};
