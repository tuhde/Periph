#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "TSL2561.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, TSL2561Minimal::I2C_ADDRESS);
    TSL2561Minimal sensor(connection);                     // Create TSL2561 driver, (connection)
    check_true(sensor.init(), "init");                     // Power up, 1× gain, 402 ms, () → bool
    float lx = sensor.readLux();                           // Read illuminance, () → float lux
    check_true(lx >= 0.0f && lx <= 40000.0f, "read_lux_in_range");

    TSL2561Full full(connection);                          // Create TSL2561 Full driver, (connection)
    full.init();
    int id = full.readId();                                // Read the ID register, () → int
    check_true(id >= 0 && (id >> 4) == 1, "read_id_partno");
    check_true(full.isPowered(), "is_powered");            // Power state, () → bool
    uint16_t ch0 = 0, ch1 = 0;
    check_true(full.readRaw(ch0, ch1), "read_raw");        // Read both channels, (ch0&, ch1&) → bool
    full.setGain(16);                                      // Set ADC gain, (gain 1|16) → bool
    full.setIntegrationTime(101);                          // Set integration time, (ms 14|101|402) → bool
    check_true(full.getGain() == 16 && full.getIntegrationTime() == 101, "gain_integration_roundtrip");
    full.setGain(1);
    full.setIntegrationTime(402);
    lx = full.autoRange();                                 // Auto-range and read lux, () → float lux
    check_true(lx >= 0.0f, "auto_range_lux");
    full.setThresholds(100, 30000);                        // Set channel-0 window, (low, high) → bool
    uint16_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);                            // Read the window, (low&, high&) → bool
    check_true(lo == 100 && hi == 30000, "thresholds_roundtrip");
    check_true(full.configureInterrupt(0, 0), "configure_interrupt");  // Interrupt mode, (mode, persist) → bool
    check_true(full.clearInterrupt(), "clear_interrupt");  // Release a level interrupt, () → bool
    full.pollInterrupt();                                  // Check ch0 against the window, () → bool
    full.powerDown();                                      // Power down, () → bool
    check_true(!full.isPowered(), "power_down");
    full.powerUp();                                        // Power up, () → bool
    check_true(full.isPowered(), "power_up");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
