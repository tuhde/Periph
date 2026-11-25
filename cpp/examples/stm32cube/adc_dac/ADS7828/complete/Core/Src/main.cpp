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
        uint16_t raw = adc.readRaw(3);                              // Read CH3 raw code, (channel=0–7) → uint16_t
                                                            // single-ended, interleaved channel encoding; returns 0–4095
        float v3 = adc.readVoltage(3);                              // Read CH3 voltage, (channel=0–7) → float V
                                                            // raw / 4096 × vref
        float all[8];
        adc.readAll(all);                                           // Read all eight channels, (out[8]) → void
                                                            // one conversion per channel, CH0…CH7
        uint16_t draw = adc.readDifferentialRaw(0);                 // Read pair 0 raw code, (pair=0–3, inverted=false) → uint16_t
                                                            // CH0 (+) / CH1 (−); a negative difference reads 0
        float dv = adc.readDifferential(1, true);                   // Read pair 1 inverted, (pair=0–3, inverted=false) → float V
                                                            // CH3 (+) / CH2 (−)
        float sv = adc.readSignedDifferential(0);                   // Read pair 0 signed, (pair=0–3) → float V
                                                            // two conversions (normal and inverted), range −vref … +vref
        float lsb = adc.lsb();                                      // Get code step, () → float V
                                                            // vref / 4096 (610 µV at 2.5 V)
        const uint8_t chans[4] = {0, 2, 4, 6};
        uint16_t scan[4];
        adc.readScan(chans, 4, scan);                               // Scan channels, (channels, count, out) → void
                                                            // one raw conversion per channel in the given order
        adc.setReference(5.0f);                                     // Use external reference, (vref V; 0 = internal) → void
                                                            // PD1 = 0 from now on; the reference must already be stable
        float ref = adc.getReference();                             // Get reference voltage, () → float V
        bool internal = adc.usesInternalReference();                // Internal reference selected?, () → bool
        adc.setReference(0.0f);                                     // Use internal reference, (vref V; 0 = internal) → void
                                                            // re-runs the reference turn-on and 1 ms settle wait
        bool ok = adc.setPowerMode(ADS7828Full::REF_ON_ADC_ON);     // Set PD bits, (pd=0–3) → bool
                                                            // internal reference requires PD1 = 1 (values 2 or 3)
        adc.powerDown();                                            // Power down completely, () → void
                                                            // lone command with PD=00; next read re-enables the reference
        printf("raw=%u v3=%.3f V draw=%u dv=%.3f V sv=%.3f V lsb=%.6f V ref=%.2f V internal=%d ok=%d scan0=%u all7=%.3f V\r\n", raw, (double)v3, draw, (double)dv, (double)sv, (double)lsb, (double)ref, (int)internal, (int)ok, scan[0], (double)all[7]);
        HAL_Delay(1000);
    }
}
