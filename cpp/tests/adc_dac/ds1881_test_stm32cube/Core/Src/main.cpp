#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "DS1881.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, DS1881Minimal::I2C_ADDRESS);
    DS1881Minimal pot(connection);                      // Create DS1881 driver, (connection)
    pot.init();                                         // Apply defaults, () → void
    pot.setAttenuation(0, 20.0f);                       // Set attenuation, (channel 0–1, db dB) → void
    pot.setAttenuation(1, 33.0f);
    check_true(pot.getPosition(0) == 20 && pot.getPosition(1) == 33, "position_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    check_true(pot.getAttenuation(0) == 20.0f && pot.getAttenuation(1) == 33.0f, "attenuation_roundtrip"); // Read attenuation, (channel 0–1) → float dB
    pot.mute(0);                                        // Mute channel, (channel 0–1) → void
    check_true(pot.getPosition(0) == 63 && pot.getAttenuation(0) == 80.0f, "mute");
    pot.setPosition(1, 5);                              // Set raw wiper position, (channel 0–1, position 0–63) → void
    check_true(pot.getPosition(1) == 5, "set_position");

    DS1881Full full(connection);                        // Create DS1881 Full driver, (connection)
    full.init();
    DS1881Config cfg = full.getConfig();                // Read configuration, () → DS1881Config
    check_true(cfg.option == 1 && cfg.zeroCrossing && !cfg.nonvolatile, "config_default");
    uint8_t p0, p1, c;
    full.setBoth(10, 40);                               // Set both wipers, (position0 0–63, position1 0–63) → void
    full.readAll(p0, p1, c);                            // Read everything, (&position0, &position1, &config) → void
    check_true(p0 == 10 && p1 == 40, "set_both");
    full.setAttenuationBoth(15.0f);                     // Set both attenuations, (db0 dB, db1=db0 dB) → void
    full.readAll(p0, p1, c);
    check_true(p0 == 15 && p1 == 15, "set_attenuation_both");
    full.setZeroCrossing(false);                        // Set zero-crossing, (enabled bool) → void
    check_true(!full.getConfig().zeroCrossing, "zero_crossing_off");
    full.setZeroCrossing(true);
    full.setOption(2);                                  // Select option, (option 1–2) → void
    check_true(full.getConfig().option == 2 && full.maxPosition() == 32, "option2");
    full.setBoth(13, 33);
    full.readAll(p0, p1, c);
    check_true(p0 == 13 && p1 == 33 && full.positionToDb(13) == 14.0f, "option2_positions");
    full.setOption(1);
    check_true(full.getConfig().option == 1, "option1_again");
    full.reset();                                       // Reset, () → void
    full.readAll(p0, p1, c);
    check_true(p0 == 63 && p1 == 63, "reset_mutes");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
