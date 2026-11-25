#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCA9536.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x41);
    PCA9536Minimal chip(connection);

    CHK(chip._direction == 0xFF, "init_config");
    CHK(chip._shadow == 0xFF, "init_shadow");

    chip.write_port(0, 0x05);
    CHK((chip._shadow & 0x0F) == 0x05, "write_port_shadow");
    chip.write_port(0, 0x0F);
    uint8_t port_val = chip.read_port();
    CHK(port_val <= 0x0F, "read_port_range");

    auto p3 = chip.pin(3);
    p3.mode(OUTPUT);
    p3.low();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_off");
    p3.high();
    CHK((chip._shadow & 0x08) == 0x08, "pin3_on");
    p3.toggle();
    CHK((chip._shadow & 0x08) == 0x00, "pin3_toggle");
    CHK(!p3.mode(INPUT_PULLUP), "pull_up_rejected");

    PCA9536Full full(connection);
    CHK(full._direction == 0xFF, "full_init_config");

    // Loopback: jumper P0-P1 to P2-P3 (Pn <-> Pn+2).
    full.set_direction(0x0C);
    CHK((full.read_register(0x03) & 0x0F) == 0x0C, "config_readback");
    full.write_port(0, 0x01);
    CHK(((full.read_port() >> 2) & 0x03) == 0x01, "loopback_0x1");
    full.write_port(0, 0x02);
    CHK(((full.read_port() >> 2) & 0x03) == 0x02, "loopback_0x2");
    full.write_port(0, 0x00);
    CHK(((full.read_port() >> 2) & 0x03) == 0x00, "loopback_0x0");

    full.configure_polarity(0x0C);
    CHK((full.read_register(0x02) & 0x0F) == 0x0C, "polarity_readback");
    CHK(((full.read_port() >> 2) & 0x03) == 0x03, "polarity_inverted");
    full.configure_polarity(0x00);

    full.pollChanges();
    full.write_port(0, 0x01);
    CHK(full.pollChanges() == 0x04, "poll_changes_changed");
    full.write_port(0, 0x00);

    full.reset();
    CHK((full.read_register(0x03) & 0x0F) == 0x0F, "reset_config");
    CHK((full.read_register(0x01) & 0x0F) == 0x0F, "reset_output");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
