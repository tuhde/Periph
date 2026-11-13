#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCA9539.h"

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

#define CHK(c, l) check_true((c), l)

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x74);
    PCA9539Minimal chip(connection);

    CHK(chip._direction == 0xFFFF, "init_config");
    CHK(chip._shadow == 0xFFFF, "init_shadow");

    chip.write_port(0, 0x55);
    chip.write_port(1, 0xAA);
    CHK(chip._shadow == 0xAA55, "write_port_shadow");
    chip.write_port(0, 0xFF);
    chip.write_port(1, 0xFF);
    uint8_t port_val = chip.read_port(1);
    CHK(port_val <= 0xFF, "read_port_range");

    auto p15 = chip.pin(15);
    p15.mode(OUTPUT);
    p15.low();
    CHK((chip._shadow & 0x8000) == 0x0000, "pin15_off");
    p15.high();
    CHK((chip._shadow & 0x8000) == 0x8000, "pin15_on");
    p15.toggle();
    CHK((chip._shadow & 0x8000) == 0x0000, "pin15_toggle");
    CHK(!p15.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA9539Full full(connection);
    CHK(full._direction == 0xFFFF, "full_init_config");

    // Loopback: jumper port 0 to port 1 (P0_n <-> P1_n).
    full.set_direction(0xFF00);
    CHK(full.read_register(0x06) == 0x00, "config0_readback");
    CHK(full.read_register(0x07) == 0xFF, "config1_readback");
    full.write_port(0, 0x5A);
    CHK(full.read_port(1) == 0x5A, "loopback_0x5A");
    full.write_port(0, 0xA5);
    CHK(full.read_port(1) == 0xA5, "loopback_0xA5");
    full.write_all(0x0000);
    CHK(full.read_port(1) == 0x00, "loopback_0x00");
    full.write_all(0x00FF);
    CHK(full.read_all() == 0xFFFF, "read_all_pair");
    full.write_all(0x0000);

    full.configure_polarity(0xFF00);
    CHK(full.read_register(0x05) == 0xFF, "polarity1_readback");
    CHK(full.read_port(1) == 0xFF, "polarity_inverted");
    full.configure_polarity(0x0000);

    full.pollInterrupt();
    full.write_port(0, 0x01);
    CHK(full.pollInterrupt() == 0x0100, "poll_interrupt_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK(full.read_register(0x06) == 0xFF, "reset_config0");
    CHK(full.read_register(0x07) == 0xFF, "reset_config1");
    CHK(full.read_register(0x02) == 0xFF, "reset_output0");
    CHK(full.read_register(0x03) == 0xFF, "reset_output1");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
