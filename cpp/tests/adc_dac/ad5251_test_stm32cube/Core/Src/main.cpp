#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "AD5251.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, AD5251Minimal::I2C_ADDRESS);
    AD5251Minimal pot(connection);                          // Create AD5251 driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω)
    pot.setWiper(0, 40);                                   // Set wiper position, (channel 0–1, code 0–63) → bool
    check_true(pot.getWiper(0) == 40, "wiper0_roundtrip"); // Read wiper position, (channel 0–1) → uint8_t
    pot.setWiper(1, 60);
    check_true(pot.getWiper(1) == 60, "wiper1_roundtrip");
    check_true(pot.getWiper(0) == 40, "wiper0_independent");
    pot.setWiper(0, 300);
    check_true(pot.getWiper(0) == 63, "wiper_clamps_255");
    pot.setResistance(0, 25000.0f);                          // Set W–B resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(pot.getResistance(0) - 25000.0f) < 800.0f, "set_resistance"); // Read W–B resistance, (channel 0–1) → float Ω

    AD5251Full full(connection);                            // Create AD5251 Full driver, (connection, rAb=50000.0 Ω, rWiper=75.0 Ω)
    full.setBoth(10, 58);                                  // Write both wipers, (code0 0–63, code1 0–63) → void
    check_true(full.getWiper(0) == 10 && full.getWiper(1) == 58, "set_both");
    full.setRatio(0, 0.5f);                                 // Set divider ratio, (channel 0–1, ratio 0.0–1.0) → bool
    check_true(full.getWiper(0) == 32 && fabsf(full.getRatio(0) - 0.5f) < 0.01f, "ratio_half"); // Read divider ratio, (channel 0–1) → float
    full.setResistanceWa(1, 15000.0f);                       // Set W–A resistance, (channel 0–1, ohms Ω) → bool
    check_true(fabsf(full.getResistanceWa(1) - 15000.0f) < 800.0f, "set_resistance_wa"); // Read W–A resistance, (channel 0–1) → float Ω
    full.setWiper(0, 40);
    full.increment(0);                                      // Step up one code, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 21, "increment");
    full.decrement(0);                                      // Step down one code, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 40, "decrement");
    full.doubleCode(0);                                     // Raise by 6 dB, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 60, "double");
    full.halve(0);                                          // Lower by 6 dB, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 40, "halve");
    full.setBoth(10, 20);
    full.increment();
    check_true(full.getWiper(0) == 11 && full.getWiper(1) == 21, "increment_both");

    full.setBoth(40, 60);
    full.store();                                           // Store wipers in EEMEM, (channel=BOTH 0–1) → bool
    full.setBoth(1, 2);
    full.restore();                                         // Restore wipers from EEMEM, (channel=BOTH 0–1) → bool
    check_true(full.getWiper(0) == 40 && full.getWiper(1) == 60, "store_restore");
    full.setBoth(1, 2);
    full.reset();                                           // Reset from EEMEM, () → bool
    check_true(full.getWiper(0) == 40 && full.getWiper(1) == 60, "reset_restores_eemem");

    full.writeEemem(15, 0xA5);                              // Write user EEMEM byte, (index 4–15, value 0–63) → bool
    check_true(full.readEemem(15) == 0xA5, "eemem_roundtrip"); // Read user EEMEM byte, (index 4–15) → uint8_t
    float tol = full.readTolerance(0);                      // Read stored RAB tolerance, (channel 0–1) → float %
    check_true(tol > -50.0f && tol < 50.0f, "tolerance_range");
    check_true(fabsf(full.rabActual(0) - 50000.0f * (1.0f + tol / 100.0f)) < 0.5f, "rab_actual"); // (channel 0–1) → float Ω
    full.nop();                                             // Send NOP, () → void


    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
