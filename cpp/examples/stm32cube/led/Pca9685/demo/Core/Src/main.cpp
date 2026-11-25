#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Pca9685.h"

static UART_HandleTypeDef huart2;
static I2C_HandleTypeDef hi2c1;

// SysTick_Handler overrides the weak default in startup_stm32f411xe.s —
// without this, HAL_GetTick()/HAL_Delay() never advance.
extern "C" void SysTick_Handler(void) {
    HAL_IncTick();
}

// Retargets printf() to USART2 (PA2/PA3), which NUCLEO-F411RE's on-board
// ST-LINK exposes as a USB virtual COM port at 115200 baud.
extern "C" int _write(int file, char* ptr, int len) {
    (void)file;
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(ptr), static_cast<uint16_t>(len), HAL_MAX_DELAY);
    return len;
}

static void gpio_clocks_init(void) {
    __HAL_RCC_GPIOA_CLK_ENABLE();
    __HAL_RCC_GPIOB_CLK_ENABLE();
    __HAL_RCC_GPIOC_CLK_ENABLE();
}

static void uart2_init(void) {
    __HAL_RCC_USART2_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;  // PA2=TX, PA3=RX
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_LOW;
    gpioInit.Alternate = GPIO_AF7_USART2;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    huart2.Instance          = USART2;
    huart2.Init.BaudRate     = 115200;
    huart2.Init.WordLength   = UART_WORDLENGTH_8B;
    huart2.Init.StopBits     = UART_STOPBITS_1;
    huart2.Init.Parity       = UART_PARITY_NONE;
    huart2.Init.Mode         = UART_MODE_TX_RX;
    huart2.Init.HwFlowCtl    = UART_HWCONTROL_NONE;
    huart2.Init.OverSampling = UART_OVERSAMPLING_16;
    HAL_UART_Init(&huart2);
}

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;  // PB8=SCL, PB9=SDA
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;               // internal pull-up, in case the breakout has none
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hi2c1.Instance             = I2C1;
    hi2c1.Init.ClockSpeed      = 100000;
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);

    Pca9685Full pwm(connection);                      // Create PCA9685 driver, (connection)
    HAL_Delay(2000);

    // --- LED fader and servo sweep ---
    // Fade channel 0 at 1 kHz, show a phase-shifted pair, run a 16-channel chase,
    // switch to 50 Hz and sweep a servo on channel 15 from 1 ms to 2 ms, then show
    // all-channel writes, full ON/OFF, output modes, sleep/wake/restart with the
    // outputs preserved, the all-call and sub addresses, and finish with a reset.
    pwm.set_frequency(1000.0f);                           // 1 kHz PWM for LEDs, (hz) → bool
    for (int i = 0; i <= 100; i++) {
        pwm.set_duty(0, 0.5f - 0.5f * cosf(3.14159265f * i / 50.0f));// Fade up and down, (channel, duty) → bool
        HAL_Delay(20);
    }
    pwm.set_pwm(1, 0, 1024);                              // 25 % duty, no delay, (channel, on, off) → bool
    pwm.set_pwm(2, 2048, 3072);                           // Same duty, shifted by half a period
    uint16_t on = 0, off = 0;
    pwm.get_pwm(2, on, off);                              // Read back the counts, (channel, &on, &off) → bool
    printf("%d\n", (int)(on));
    printf("%d\n", (int)(off));
    HAL_Delay(1000);
    for (uint8_t ch = 0; ch < 16; ch++) {
        pwm.set_duty(ch, 1.0f);                           // Chase across all channels, (channel, duty) → bool
        HAL_Delay(50);
        pwm.set_duty(ch, 0.0f);
    }
    pwm.set_all_duty(0.2f);                               // All channels together, (duty) → bool
    HAL_Delay(500);
    pwm.set_all_pwm(0, 3072);                             // 75 % on all channels, (on, off) → bool
    HAL_Delay(500);
    pwm.set_all_duty(0.0f);
    pwm.set_full_on(3);                                   // Constant high, (channel) → bool
    HAL_Delay(500);
    pwm.set_full_off(3);                                  // Constant low, (channel) → bool
    pwm.set_frequency(50.0f);                             // Servo frequency, (hz) → bool
    for (int us = 1000; us <= 2000; us += 50) {
        pwm.set_pulse_width(15, us / 1e6f);               // Servo pulse 1–2 ms, (channel, seconds) → bool
        HAL_Delay(50);
    }
    printf("%d\n", (int)(pwm.get_frequency()));           // () → float Hz
    pwm.set_frequency(1000.0f);
    pwm.set_duty(4, 0.5f);
    pwm.set_output_mode(false, true, Pca9685Full::OFF_LOW);// Inverted output, (open_drain, invert, off_state) → bool
    HAL_Delay(500);
    pwm.set_output_mode(true, false, Pca9685Full::OFF_HIGHZ);// Open-drain, high-Z when OE = 1
    HAL_Delay(500);
    pwm.set_output_mode();                                // Back to totem-pole, not inverted
    pwm.set_update_on_ack(true);                          // Update on ACK, (enabled) → void
    pwm.set_update_on_ack(false);
    pwm.sleep();                                          // Outputs pause, PWM registers kept, () → void
    HAL_Delay(500);
    pwm.wake();                                           // Outputs resume, () → void
    pwm.restart();                                        // Restart sequence, () → void
    pwm.set_allcall(true);                                // Respond to all-call, (enabled, address) → bool
    pwm.set_allcall(false);
    pwm.set_subaddress(1, 0x71, true);                    // Respond to sub address 1, (index, address, enabled) → bool
    pwm.set_subaddress(1, 0x71, false);
    uint8_t mode1 = 0, mode2 = 0;
    pwm.read_mode(mode1, mode2);                          // () → (mode1, mode2)
    printf("%d\n", (int)(mode1));
    printf("%d\n", (int)(mode2));
    pwm.reset();                                          // Back to power-on state, () → void

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
