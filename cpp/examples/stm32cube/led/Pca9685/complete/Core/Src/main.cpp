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

    pwm.set_duty(0, 0.25f);                               // Set one channel's duty cycle, (channel 0–15, duty 0.0–1.0) → bool
    pwm.set_pwm(1, 409, 1228);                            // Phase-shifted PWM, (channel, on 0–4095, off 0–4095) → bool
    // 10 % delay, 20 % duty
    uint16_t on = 0, off = 0;
    pwm.get_pwm(1, on, off);                              // Read the ON/OFF counts, (channel, &on, &off) → bool
    printf("%d\n", (int)(on));
    printf("%d\n", (int)(off));
    pwm.set_full_on(2);                                   // Hold a channel high, (channel) → bool
    pwm.set_full_off(2);                                  // Hold a channel low, (channel) → bool
    pwm.set_all_duty(0.1f);                               // Same duty on all 16 channels, (duty 0.0–1.0) → bool
    pwm.set_all_pwm(0, 2048);                             // Same ON/OFF counts on all channels, (on, off) → bool
    pwm.set_all_duty(0.0f);
    pwm.set_frequency(50.0f);                             // PWM frequency, (hz 24–1526, osc_hz = 25 MHz) → bool
    printf("%d\n", (int)(pwm.get_frequency()));           // Programmed PWM frequency, (osc_hz = 25 MHz) → float Hz
    pwm.set_pulse_width(15, 0.0015f);                     // Servo pulse width, (channel, seconds) → bool
    // 1.5 ms at 50 Hz = servo centre
    HAL_Delay(1000);
    pwm.sleep();                                          // Low-power mode, outputs stop, () → void
    pwm.wake();                                           // Wake and restart running channels, () → void
    pwm.restart();                                        // Datasheet restart sequence, () → void
    pwm.set_output_mode(false, false, Pca9685Full::OFF_LOW);// MODE2 output driver, (open_drain, invert, off_state) → bool
    pwm.set_update_on_ack(false);                         // Outputs change on STOP (false) or ACK (true), (enabled) → void
    pwm.set_allcall(false, 0x70);                         // LED All Call address, (enabled, address) → bool
    pwm.set_subaddress(1, 0x71, false);                   // LED Sub Call address, (index 1–3, address, enabled) → bool
    uint8_t mode1 = 0, mode2 = 0;
    pwm.read_mode(mode1, mode2);                          // Raw MODE1/MODE2, (&mode1, &mode2) → void
    printf("%d\n", (int)(mode1));
    printf("%d\n", (int)(mode2));
    // pwm.use_external_clock();  Sticky EXTCLK switch, () → void (needs a clock on EXTCLK)
    // Pca9685Full::software_reset(general_call);  Reset every PCA9685 on the bus, (Connection at address 0x00) → void
    pwm.reset();                                          // Write power-on defaults, () → void

    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
