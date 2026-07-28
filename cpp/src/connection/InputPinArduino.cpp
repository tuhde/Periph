#include "InputPinArduino.h"

InputPinArduino* InputPinArduino::_instances[InputPinArduino::kMaxInstances] = {};
void (*const InputPinArduino::kTrampolines[InputPinArduino::kMaxInstances])() = {
    InputPinArduino::t0, InputPinArduino::t1, InputPinArduino::t2, InputPinArduino::t3,
    InputPinArduino::t4, InputPinArduino::t5, InputPinArduino::t6, InputPinArduino::t7,
};

InputPinArduino::InputPinArduino(uint8_t pin) : _pin(pin) {
    pinMode(_pin, INPUT_PULLUP);
}

bool InputPinArduino::onEdge(Handler handler, uint8_t trigger) {
    bool added = addHandler(_handlers, handler);
    if (added && !_armed) {
        int slot = claimSlot(this);
        if (slot >= 0) {
            // RHS RISING/CHANGE/FALLING are Arduino's own macros (attachInterrupt's
            // native mode values); LHS trigger/kRising/kChange are our InputPin
            // abstraction's values — the two encodings are unrelated numerically.
            int mode = trigger == kRising ? RISING : (trigger == kChange ? CHANGE : FALLING);
            attachInterrupt(digitalPinToInterrupt(_pin), kTrampolines[slot], mode);
            _armed = true;
        }
    }
    return added;
}

void InputPinArduino::offEdge(Handler handler) {
    removeHandler(_handlers, handler);
}

int InputPinArduino::claimSlot(InputPinArduino* self) {
    for (uint8_t i = 0; i < kMaxInstances; ++i) {
        if (_instances[i] == nullptr) {
            _instances[i] = self;
            return i;
        }
    }
    return -1;
}

#define PERIPH_DEFINE_TRAMPOLINE(N) \
    void InputPinArduino::t##N() { \
        if (_instances[N] != nullptr) dispatch(_instances[N]->_handlers); \
    }

PERIPH_DEFINE_TRAMPOLINE(0)
PERIPH_DEFINE_TRAMPOLINE(1)
PERIPH_DEFINE_TRAMPOLINE(2)
PERIPH_DEFINE_TRAMPOLINE(3)
PERIPH_DEFINE_TRAMPOLINE(4)
PERIPH_DEFINE_TRAMPOLINE(5)
PERIPH_DEFINE_TRAMPOLINE(6)
PERIPH_DEFINE_TRAMPOLINE(7)

#undef PERIPH_DEFINE_TRAMPOLINE
