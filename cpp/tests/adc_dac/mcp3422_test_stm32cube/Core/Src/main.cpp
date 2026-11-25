#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MCP3422.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Minimal adc(connection);                         // Create MCP3422 driver, (connection)
    int32_t raw = adc.readRaw(0);                           // Read a signed code, (channel 0–1) → int32_t
    check_true(adc.lastReadOk() && raw >= -32768 && raw <= 32767, "raw_in_16_bit_range");
    float v = adc.readVoltage(1);                           // Read the differential voltage, (channel 0–1) → float V
    check_true(adc.lastReadOk() && v >= -2.048f && v <= 2.048f, "voltage_in_range");
    float both[2];
    check_true(adc.readAll(both), "read_all_two_values");   // Read both channels, (float out[2]) → bool

    MCP3422Full full(connection);                           // Create MCP3422 Full driver, (connection)
    check_true(full.setResolution(12) && full.setGain(2), "selection_accepted"); // Select resolution / gain, (bits|gain) → bool
    check_true(full.getResolution() == 12 && full.getGain() == 2, "selection_roundtrip");
    check_true(fabsf(full.fullScale() - 1.024f) < 1e-6f, "full_scale"); // Full-scale range, () → float V
    int32_t r12 = full.readRaw(0);
    check_true(full.lastReadOk() && r12 >= -2048 && r12 <= 2047, "one_shot_12_bit_range");
    full.setGain(1);
    full.startContinuous(0);                                // Start continuous conversion, (channel=-1) → void
    check_true(full.isContinuous(), "continuous_flag");     // Report the mode, () → bool
    int32_t rc = full.readContinuousRaw();                  // Next fresh code, () → int32_t
    check_true(full.lastReadOk() && rc >= -2048 && rc <= 2047, "continuous_sample_in_range");
    check_true((full.readConfig() & 0x10) != 0, "read_config_continuous_bit"); // Read the config byte, () → uint8_t
    full.stopContinuous();                                  // Back to one-shot / standby, () → void
    check_true(!full.isContinuous(), "stopped");
    const uint8_t ch[2] = {0, 1};
    float scanned[2];
    check_true(full.readScan(ch, 2, scanned), "read_scan_two_values"); // Scan channels, (channels, count, float out[]) → bool

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
