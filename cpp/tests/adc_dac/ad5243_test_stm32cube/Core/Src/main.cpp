#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AD5243.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, AD5243Minimal::I2C_ADDRESS);
    AD5243Minimal pot(connection);                          // Create AD5243 driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    pot.setWiper(0, 100);                                   // Set wiper position, (channel 0–1, code 0–255) → bool
    check_true(pot.getWiper(0) == 100, "wiper0_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    pot.setWiper(1, 200);
    check_true(pot.getWiper(1) == 200, "wiper1_roundtrip");
    check_true(pot.getWiper(0) == 100, "wiper0_independent");
    pot.setWiper(0, 300);
    check_true(pot.getWiper(0) == 255, "wiper_clamps_255");
    pot.setResistance(0, 5000.0f);                          // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(pot.getResistance(0) - 5000.0f) < 50.0f, "set_resistance"); // Read W–B resistance, (channel 0–1) → float Ω

    AD5243Full full(connection);                            // Create AD5243 Full driver, (connection, rAb=10000.0 Ω, rWiper=160.0 Ω)
    full.setBoth(10, 250);                                  // Write both wipers, (code1 0–255, code2 0–255) → void
    check_true(full.getWiper(0) == 10 && full.getWiper(1) == 250, "set_both");
    full.setRatio(0, 0.5f);                                 // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    check_true(full.getWiper(0) == 128 && fabsf(full.getRatio(0) - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, (channel 0–1) → float
    full.setResistanceWa(1, 3000.0f);                       // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(full.getResistanceWa(1) - 3000.0f) < 50.0f, "set_resistance_wa"); // Read W–A resistance, (channel 0–1) → float Ω
    full.setWiper(0, 100);
    check_true(full.increment(0, 5) == 105 && full.getWiper(0) == 105, "increment"); // Step the wiper, (channel 0–1, steps=1) → uint8_t
    check_true(full.increment(0, -500) == 0, "increment_clamps");

    full.setWiper(1, 77);
    full.shutdown(1);                                       // Shut channel down, (channel 0–1) → void
    check_true(full.isShutdown(1), "is_shutdown");          // Query shutdown state, (channel 0–1) → bool
    check_true(full.getWiper(1) == 77, "shutdown_keeps_rdac");
    full.resume(1);                                         // Leave shutdown, (channel 0–1) → void
    check_true(!full.isShutdown(1) && full.getWiper(1) == 77, "resume");

    full.setBoth(1, 2);
    full.reset();                                           // Restore power-up preset, () → void
    check_true(full.getWiper(0) == 128 && full.getWiper(1) == 128, "reset_midscale");


    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
