#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF8579.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x3C);
    PCF8579Minimal lcd(connection, 0, 16);
    lcd.clear();
    lcd.set_pixel(0, 0);
    lcd.set_pixel(39, 15);
    lcd.set_pixel(45, 30);                                    // out of range, ignored
    static const uint8_t cols[3] = {0xFF, 0x81, 0xFF};
    check_true(lcd.write_columns(2, 1, cols, sizeof(cols)), "minimal_draw");

    // Full: exercise every method; read_ram verifies the bytes really landed.
    PCF8579Full full(connection, 0, 16);
    static const uint8_t ram[3] = {0xAA, 0x55, 0x0F};
    check_true(full.write_ram(16, 0, ram, sizeof(ram)), "write_ram");
    uint8_t got[3] = {0, 0, 0};
    check_true(full.read_ram(16, 0, got, sizeof(got)) && got[0] == 0xAA && got[1] == 0x55 && got[2] == 0x0F,
               "ram_roundtrip");

    full.fill(true);
    full.fill(false);
    full.draw_line(0, 0, 39, 15);
    full.draw_rect(0, 0, 40, 16);
    full.draw_rect(4, 4, 8, 8, true, true);
    static const uint8_t bmp[4] = {0x18, 0x24, 0x42, 0x81};
    bool bmp_ok = full.show_bitmap(0, 0, bmp, sizeof(bmp));
    check_true(bmp_ok && full.get_pixel(0, 0), "graphics");

    static const uint8_t zeros[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    bool cfg_ok = full.set_display(PCF8579Full::DISPLAY_INVERSE)
        && full.set_display(PCF8579Full::DISPLAY_ALL_ON)
        && full.set_display(PCF8579Full::DISPLAY_NORMAL)
        && full.set_start_bank(1);
    full.scroll(1);
    full.scroll(-2);
    cfg_ok = cfg_ok
        && full.write_ram(16, 0, zeros, sizeof(zeros), PCF8579Full::ACCESS_HALF_GRAPHIC)
        && full.write_ram(16, 0, zeros, sizeof(zeros), PCF8579Full::ACCESS_FULL_GRAPHIC)
        && full.set_mode(24, false) && full.set_mode(16, false);
    full.reset();
    check_true(cfg_ok, "configuration");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
