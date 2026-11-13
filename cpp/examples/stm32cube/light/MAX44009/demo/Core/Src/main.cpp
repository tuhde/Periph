#include <math.h>
#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MAX44009.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Full sensor(connection);                    // Create MAX44009 Full driver, (connection)
    // --- Configuration and first reading ---
    MAX44009Full::Config cfg = sensor.getConfig();      // Read CONFIG, () → Config
    float lx = sensor.lux();                            // Read illuminance, () → float lux
    float res = sensor.resolution();                    // Lux per LSB, () → float lux
    printf("manual %d, lux %.2f, resolution %.3f lx/LSB\n", (int)cfg.manual, (double)lx, (double)res);

    // --- Arm a window of +-30 % around the current illuminance ---
    // The captureless callback only records the status; the loop below reacts to it.
    static volatile uint8_t pending = 0;
    sensor.setThresholdDelay(2.0f);                     // Set the threshold delay, (seconds) → void
    sensor.setThresholds(lx * 0.7f, lx * 1.3f);         // Set the interrupt window, (lowLux, highLux) → bool
    sensor.enableInterrupt();                           // Enable the interrupt, () → void
    sensor.onInterrupt([](uint8_t status) { pending = status; });  // Subscribe, (callback(status), intPin=nullptr) → void

    // --- Run for 60 s: react to interrupts, print the raw reading once a second ---
    for (int s = 0; s < 60; s++) {
        sensor.service();                               // Poll once and dispatch the callback, () → void
        if (pending) {
            pending = 0;
            float now = sensor.lux();                   // Read illuminance, () → float lux
            float level = now < 0.1f ? 0.1f : (now > 10000.0f ? 10000.0f : now);
            float backlight = 10.0f + 90.0f * (log10f(level) + 1.0f) / 5.0f;
            printf("lux %.2f -> backlight %.0f %%\n", (double)now, (double)backlight);
            sensor.setThresholds(now * 0.7f, now * 1.3f);  // Set the interrupt window, (lowLux, highLux) → bool
        }
        uint8_t e = 0, m = 0;
        sensor.readRaw(e, m);                           // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
        printf("E=%d M=%d overrange=%d\n", (int)e, (int)m, (int)sensor.isOverrange());
        HAL_Delay(1000);
    }
    sensor.disableInterrupt();                          // Disable the interrupt, () → void
    sensor.offInterrupt();                              // Unsubscribe, () → void
    while (1) {}
}
