#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MCP4XXX.h"
#include <stdlib.h>

// Part under test; wire the board to this part and its address (see the address in the connection setup).
#ifndef TEST_PART
#define TEST_PART MCP4XXXPart::MCP4532
#endif

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

int passed = 0;
int failed = 0;

static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

static void check_near(float v, float lo, float hi, const char *label) {
    if (v >= lo && v <= hi) { printf("PASS %s\r\n", label); passed++; }
    else { printf("FAIL %s: %.4f not in [%.4f, %.4f]\r\n", label, (double)v, (double)lo, (double)hi); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x58);
    MCP4XXXFull pot(connection, TEST_PART, 10000.0f, 75.0f);

    pot.init();
    int max = (int)pot.steps() - 1;

    // --- Wiper write / readback ---
    pot.set_wiper(100 % (max + 1), 0);
    check_true(pot.get_wiper(0) == 100 % (max + 1), "set/get wiper");
    pot.set_wiper(9999, 0);
    check_true(pot.get_wiper(0) == max, "clamps high");
    pot.set_wiper(-5, 0);
    check_true(pot.get_wiper(0) == 0, "clamps low");
    if (pot.channels() == 2) {
        pot.set_both(10, 20);
        check_true(pot.get_wiper(0) == 10, "set_both ch0");
        check_true(pot.get_wiper(1) == 20, "set_both ch1");
    }

    // --- Resistance and ratio ---
    pot.set_resistance(5075.0f, 0);
    check_true(abs((int)pot.get_wiper(0) - max / 2) <= 1, "set_resistance ~mid");
    check_true(pot.get_resistance(0) > 4975.0f && pot.get_resistance(0) < 5175.0f, "get_resistance ~5075");
    pot.set_ratio(0.5f, 0);
    check_true(pot.get_ratio(0) > 0.49f && pot.get_ratio(0) < 0.51f, "get_ratio ~0.5");
    if (pot.is_potentiometer()) {
        pot.set_wiper(0, 0);
        check_true(pot.get_resistance_wa(0) > 9975.0f && pot.get_resistance_wa(0) < 10175.0f, "R_WA at zero scale ~RAB");
    }

    // --- Increment / decrement (continuous) ---
    pot.set_wiper(max - 3, 0);
    check_true(pot.increment(10, 0) == max, "increment saturates");
    pot.set_wiper(3, 0);
    check_true(pot.decrement(10, 0) == 0, "decrement saturates");
    check_true(pot.increment(1, 0) == 1, "increment 1");

    // --- Shutdown / terminals ---
    pot.set_wiper(max / 4, 0);
    pot.shutdown(0);
    check_true(pot.is_shutdown(0), "is_shutdown");
    pot.resume(0);
    check_true(!pot.is_shutdown(0), "not shutdown");
    check_true(pot.get_wiper(0) == max / 4, "wiper kept across shutdown");
    pot.set_terminals(true, false, true, 0);
    bool ta, tw, tb;
    pot.get_terminals(0, ta, tw, tb);
    check_true(ta && !tw && tb, "terminals");
    pot.set_terminals(true, true, true, 0);
    check_true((pot.get_tcon() & 0xFF) == 0xFF, "tcon restored");

    // --- Non-volatile parts ---
    if (pot.is_nonvolatile()) {
        MCP4XXXStatus status;
        pot.read_status(status);
        check_true(!status.eeprom_busy, "status no busy");
        pot.set_wiper(max / 2, 0);
        check_true(pot.store_wiper(0) == max / 2, "store_wiper");
        check_true(pot.get_nv_wiper(0) == max / 2, "get_nv_wiper");
        pot.write_data(0, 0x155);
        check_true(pot.read_data(0) == 0x155, "data roundtrip");
    }

    // --- Reset ---
    pot.set_wiper(1, 0);
    pot.reset();
    check_true(pot.get_wiper(0) == max / 2, "reset wiper");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
