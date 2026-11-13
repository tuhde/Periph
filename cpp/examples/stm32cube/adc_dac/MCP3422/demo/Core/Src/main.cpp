#include <stdio.h>
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, MCP3422Minimal::I2C_ADDRESS);
    MCP3422Full adc(connection);                        // Create MCP3422 Full driver, (connection)
    // --- CH1: thermistor divider at 16 bit / x1 ---
    for (int i = 0; i < 3; i++) {
        int32_t raw = adc.readRaw(0);                   // One-shot code, (channel 0–1) → int32_t
        float v = adc.readVoltage(0);                   // One-shot voltage, (channel 0–1) → float V
        printf("CH1 raw %d = %.5f V\n", (int)raw, (double)v);
        HAL_Delay(1000);
    }

    // --- CH2: bridge sensor at 18 bit / x8 ---
    adc.setResolution(18);                              // Select 18 bit / 3.75 SPS, (bits) → bool
    adc.setGain(8);                                     // Select PGA x8, (gain) → bool
    const uint8_t ch2[1] = {1};
    float bridge[1];
    adc.readScan(ch2, 1, bridge);                       // Scan channels, (channels, count, float out[]) → bool
    printf("CH2 %.1f uV, LSB %.3f uV\n", (double)bridge[0] * 1e6f, (double)adc.lsb() / adc.getGain() * 1e6f);

    // --- Continuous conversion on CH1 at 12 bit / 240 SPS ---
    adc.setResolution(12);                              // Back to 12 bit / 240 SPS, (bits) → bool
    adc.setGain(1);                                     // Back to x1, (gain) → bool
    adc.startContinuous(0);                             // Start continuous conversion, (channel=-1) → void
    float lo = 1e9f, hi = -1e9f, sum = 0;
    for (int i = 0; i < 20; i++) {
        float v = adc.readContinuous();                 // Next fresh voltage, () → float V
        if (v < lo) lo = v;
        if (v > hi) hi = v;
        sum += v;
    }
    adc.stopContinuous();                               // Back to one-shot / standby, () → void
    printf("min %.5f max %.5f mean %.5f V\n", (double)lo, (double)hi, (double)sum / 20.0f);
    adc.generalCallReset();                             // Reset to power-on defaults, () → void
    while (1) {}
}
