#pragma once
#include <stm32f4xx_hal.h>
#include "InputPin.h"

/** @brief InputPin for STM32Cube, backed by an EXTI line and
 * `HAL_GPIO_EXTI_Callback()`.
 *
 * `HAL_GPIO_EXTI_Callback(uint16_t GPIO_Pin)` is a single chip-wide weak
 * function supplied by the HAL, identifying the source only by pin
 * *number* — not port — exactly like Pico SDK's single global GPIO IRQ
 * callback. This is not a limitation specific to this class: the STM32
 * EXTI hardware itself multiplexes all GPIO ports sharing the same pin
 * number onto one EXTI line (PA5/PB5/PC5/... all route through EXTI
 * line 5), so only one port can use a given pin number as an interrupt
 * source at a time — the same small fixed-size pin->instance registry
 * `InputPinPicoSDK` uses is therefore sufficient here too.
 *
 * The interrupt is not armed at construction — `GPIO_Init` with the
 * right `GPIO_MODE_IT_*` trigger mode, the matching EXTI NVIC IRQ enable,
 * and this class's registry entry all happen in `onEdge()`, once the
 * trigger direction is known, mirroring `InputPinZephyr`/`InputPinPicoSDK`'s
 * deferred-arm pattern.
 *
 * The consuming project's `stm32f4xx_it.c`/`.cpp` must route each EXTIx
 * IRQ handler it uses to `HAL_GPIO_EXTI_IRQHandler(pin)`, which in turn
 * calls `HAL_GPIO_EXTI_Callback()` — the usual CubeMX-generated wiring.
 *
 * @param port GPIO port for the INT line (e.g. `GPIOC`).
 * @param pin  GPIO pin for the INT line (e.g. `GPIO_PIN_13`).
 */
class InputPinSTM32Cube : public InputPin {
public:
    InputPinSTM32Cube(GPIO_TypeDef* port, uint16_t pin) : _port(port), _pin(pin) {}

    bool onEdge(Handler handler, uint8_t trigger = kFalling) override {
        bool added = addHandler(_handlers, handler);
        if (added && !_armed) {
            GPIO_InitTypeDef gpioInit = {};
            gpioInit.Pin  = _pin;
            gpioInit.Mode = trigger == kRising ? GPIO_MODE_IT_RISING
                           : trigger == kChange  ? GPIO_MODE_IT_RISING_FALLING
                                                 : GPIO_MODE_IT_FALLING;
            gpioInit.Pull = GPIO_NOPULL;
            HAL_GPIO_Init(_port, &gpioInit);

            registerInstance(_pin, this);
            HAL_NVIC_EnableIRQ(_irqForPin(_pin));
            _armed = true;
        }
        return added;
    }

    void offEdge(Handler handler) override {
        removeHandler(_handlers, handler);
    }

    /** @brief Call from `HAL_GPIO_EXTI_Callback()` (directly, or after
     *  confirming `GPIO_Pin` matches, if the project also handles other
     *  EXTI sources there). Dispatches every handler registered on
     *  whichever `InputPinSTM32Cube` instance owns @p gpioPin. */
    static void dispatchFromISR(uint16_t gpioPin) {
        for (uint8_t i = 0; i < kMaxInstances; i++) {
            if (_instances[i] != nullptr && _instancePins[i] == gpioPin) {
                dispatch(_instances[i]->_handlers);
            }
        }
    }

private:
    static constexpr uint8_t kMaxInstances = 8;

    static void registerInstance(uint16_t pin, InputPinSTM32Cube* self) {
        for (uint8_t i = 0; i < kMaxInstances; i++) {
            if (_instances[i] == nullptr) {
                _instances[i] = self;
                _instancePins[i] = pin;
                return;
            }
        }
    }

    /** @brief Map a `GPIO_PIN_n` bitmask to its EXTI NVIC IRQ number. */
    static IRQn_Type _irqForPin(uint16_t pin) {
        switch (pin) {
            case GPIO_PIN_0: return EXTI0_IRQn;
            case GPIO_PIN_1: return EXTI1_IRQn;
            case GPIO_PIN_2: return EXTI2_IRQn;
            case GPIO_PIN_3: return EXTI3_IRQn;
            case GPIO_PIN_4: return EXTI4_IRQn;
            default:
                return (pin <= GPIO_PIN_9) ? EXTI9_5_IRQn : EXTI15_10_IRQn;
        }
    }

    static InputPinSTM32Cube* _instances[kMaxInstances];
    static uint16_t            _instancePins[kMaxInstances];

    GPIO_TypeDef* _port;
    uint16_t       _pin;
    Handler        _handlers[kMaxHandlers] = {};
    bool           _armed = false;
};

inline InputPinSTM32Cube* InputPinSTM32Cube::_instances[InputPinSTM32Cube::kMaxInstances] = {};
inline uint16_t InputPinSTM32Cube::_instancePins[InputPinSTM32Cube::kMaxInstances] = {};
