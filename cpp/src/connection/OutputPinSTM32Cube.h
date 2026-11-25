#pragma once
#include <stm32f4xx_hal.h>
#include "OutputPin.h"

/** @brief OutputPin for STM32Cube, backed by `HAL_GPIO_WritePin()`.
 *
 * The caller owns clock enable for the GPIO port; this class configures
 * the pin itself as a push-pull output at construction via `HAL_GPIO_Init()`.
 *
 * @param port GPIO port (e.g. `GPIOA`).
 * @param pin  GPIO pin (e.g. `GPIO_PIN_0`).
 */
class OutputPinSTM32Cube : public OutputPin {
public:
    OutputPinSTM32Cube(GPIO_TypeDef* port, uint16_t pin) : _port(port), _pin(pin) {
        GPIO_InitTypeDef gpioInit = {};
        gpioInit.Pin   = _pin;
        gpioInit.Mode  = GPIO_MODE_OUTPUT_PP;
        gpioInit.Pull  = GPIO_NOPULL;
        gpioInit.Speed = GPIO_SPEED_FREQ_LOW;
        HAL_GPIO_Init(_port, &gpioInit);
    }

    void set(bool high) override {
        HAL_GPIO_WritePin(_port, _pin, high ? GPIO_PIN_SET : GPIO_PIN_RESET);
    }

private:
    GPIO_TypeDef* _port;
    uint16_t       _pin;
};
