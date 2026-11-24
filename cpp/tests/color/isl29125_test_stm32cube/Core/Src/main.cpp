#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Isl29125.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, Isl29125Minimal::I2C_ADDRESS);
    Isl29125Minimal sensor(connection);                    // Create Isl29125 driver, (connection)
    Isl29125Full full(connection);                          // Create Isl29125 Full driver, (connection)

    Isl29125Counts c = {0, 0, 0};
    check_true(sensor.valid(), "valid");
    check_true(sensor.read(c), "read");                    // Read the three channels, (counts&) → bool
    check_true(c.red <= 0xFFFF && c.green <= 0xFFFF && c.blue <= 0xFFFF, "read_range");

    check_true(full.deviceId() == 0x7D, "device_id");       // Read DEVICE_ID, () → uint8_t
    check_true(full.setRange(375) && full.setRange(10000), "set_range");
    check_true(full.setResolution(12) && full.setResolution(16), "set_resolution");
    check_true(!full.setRange(100) && !full.setResolution(8), "invalid_config");
    check_true(full.setIrCompensation(true, 0x3F), "ir_compensation");
    full.setSync(false);
    full.readRgb(c);
    check_true(c.green <= 0xFFFF, "read_rgb_range");
    check_true(full.readGreen() <= 0xFFFF && full.readRed() <= 0xFFFF && full.readBlue() <= 0xFFFF, "read_single");
    check_true(full.setMode(Isl29125Full::MODE_GREEN), "mode_green");
    check_true(full.readGreen() <= 0xFFFF, "green_mode_reads");
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.setThresholds(100, 60000), "set_thresholds");
    uint16_t lo = 0, hi = 0;
    full.getThresholds(lo, hi);
    check_true(lo == 100 && hi == 60000, "thresholds_roundtrip");
    check_true(full.setPersistence(2) && full.setInterruptChannel(Isl29125Full::CHANNEL_GREEN), "interrupt_config");
    full.enableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.disableInterrupt(Isl29125Full::SOURCE_THRESHOLD);
    full.status();
    check_true((full.pollInterrupt() & Isl29125Full::STATUS_THRESHOLD) == 0, "status_clean");
    full.setMode(Isl29125Full::MODE_POWER_DOWN);
    full.softReset();
    full.setMode(Isl29125Full::MODE_RGB);
    check_true(full.deviceId() == 0x7D, "soft_reset_id");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
