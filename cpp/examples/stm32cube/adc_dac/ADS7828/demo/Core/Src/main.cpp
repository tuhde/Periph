#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ADS7828.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x48);
    ADS7828Full adc(connection);                            // Create ADS7828 driver, (connection, vref=0 → internal 2.5 V)

    while (true) {
        const float DIVIDER = 2.0f;   // inputs sit behind 2:1 dividers (rails up to 5 V)
        const uint8_t chans[8] = {0, 1, 2, 3, 4, 5, 6, 7};

        // --- Eight-channel supply monitor ---
        // Scan CH0–CH7 with the internal 2.5 V reference, print raw codes and rail
        // voltages (after the divider ratio) and keep a running minimum/maximum per channel.
        float vmin[8], vmax[8];
        for (int ch = 0; ch < 8; ch++) { vmin[ch] = 99.0f; vmax[ch] = 0.0f; }
        for (int n = 0; n < 5; n++) {
            uint16_t raws[8];
            adc.readScan(chans, 8, raws);                           // Scan channels, (channels, count, out) → void
            for (int ch = 0; ch < 8; ch++) {
                float volts = raws[ch] * adc.lsb() * DIVIDER;
                if (volts < vmin[ch]) vmin[ch] = volts;
                if (volts > vmax[ch]) vmax[ch] = volts;
                printf("ch%d=%u (%.3f V)  ", ch, raws[ch], (double)volts);
            }
            printf("\r\n");
            HAL_Delay(1000);
        }
        for (int ch = 0; ch < 8; ch++) printf("ch%d min=%.3f max=%.3f\r\n", ch, (double)vmin[ch], (double)vmax[ch]);

        // --- Read a bridge sensor on CH0/CH1 ---
        // The signed differential read does two conversions (normal and inverted
        // polarity) so the sign of the bridge output is preserved.
        float mv = adc.readSignedDifferential(0) * 1000.0f;         // Read pair 0 signed, (pair=0–3) → float V
        printf("bridge %+.2f mV\r\n", (double)mv);

        // --- Second scan against an external 5.0 V reference ---
        // Switching to a 5 V external reference gives full-scale range up to VDD.
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
        uint16_t ext[8];
        adc.readScan(chans, 8, ext);                                // Scan channels, (channels, count, out) → void
        printf("ext 5 V scan ch0=%u ch7=%u\r\n", ext[0], ext[7]);

        // --- Finish in full power-down ---
        adc.powerDown();                                            // Power down completely, () → void
        adc.setReference(0.0f);
        HAL_Delay(1000);
    }
}
