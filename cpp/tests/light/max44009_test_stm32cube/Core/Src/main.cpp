#include <stdio.h>
#include <math.h>
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

static int passed = 0, failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube connection(&hi2c1, MAX44009Minimal::I2C_ADDRESS);
    MAX44009Minimal sensor(connection);                    // Create MAX44009 driver, (connection)
    float lx = sensor.lux();                                // Read illuminance, () → float lux
    check_true(lx >= 0.0f && lx <= 188006.4f, "lux_in_range");

    MAX44009Full full(connection);                          // Create MAX44009 Full driver, (connection)
    uint8_t e = 0, m = 0;
    bool over = full.readRaw(e, m);                         // Read exponent and mantissa, (exponent&, mantissa&) → bool overrange
    check_true(e <= 15 && over == (e == 15), "read_raw_ranges");
    full.setContinuous(true);                               // Select measurement mode, (continuous bool) → void
    check_true(full.getConfig().continuous, "continuous_on"); // Read CONFIG, () → Config
    full.setContinuous(false);
    check_true(!full.getConfig().continuous, "continuous_off");
    full.setManual(100.0f, false);                          // Select manual range, (integrationMs, divideBy8=false) → void
    MAX44009Full::Config cfg = full.getConfig();
    check_true(cfg.manual && fabsf(cfg.integrationMs - 100.0f) < 0.01f && !cfg.divideBy8, "manual_100ms");
    full.setManual(25.0f, true);
    cfg = full.getConfig();
    check_true(fabsf(cfg.integrationMs - 25.0f) < 0.01f && cfg.divideBy8, "manual_25ms_divide");
    full.setAutomatic();                                    // Select automatic range, () → void
    check_true(!full.getConfig().manual, "automatic");
    full.setThresholds(50.0f, 500.0f);                      // Set the interrupt window, (lowLux, highLux) → bool
    float lo = 0, hi = 0;
    full.getThresholds(lo, hi);                             // Read the window, (lowLux&, highLux&) → void
    check_true(fabsf(lo - 50.0f) / 50.0f < 0.2f && fabsf(hi - 500.0f) / 500.0f < 0.2f, "thresholds_roundtrip");
    full.setThresholdDelay(2.0f);                           // Set the threshold delay, (seconds) → void
    check_true(fabsf(full.getThresholdDelay() - 2.0f) < 0.05f, "threshold_delay");
    full.enableInterrupt();                                 // Enable the interrupt, () → void
    full.disableInterrupt();                                // Disable the interrupt, () → void
    check_true(full.pollInterrupt() == 0, "poll_interrupt_cleared"); // Read and clear INT_STATUS, () → uint8_t

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
