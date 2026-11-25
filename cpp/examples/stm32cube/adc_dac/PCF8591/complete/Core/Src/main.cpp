#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF8591.h"

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
    PCF8591Full adc(connection);

    while (true) {

    uint8_t ch0_raw = adc.read_channel(0);                              // Read single channel, (channel=0–3) → uint8_t
                                                                          // discards the stale first conversion byte; returns 0–255
    uint8_t ch1_raw = adc.read_channel(1);                              // Read single channel, (channel=0–3) → uint8_t
                                                                          // selects channel 1 via the control byte, returns 0–255
    uint8_t all_raw[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all(all_raw);                                               // Read all four channels, (out[4]) → None
                                                                          // sets AI=1 and reads 5 bytes; discards stale byte 0

    float v0 = adc.read_channel_voltage(0, 3.3f, 0.0f);                  // Read channel as voltage, (channel, vref=3.3 V, vagnd=0.0 V) → float V
                                                                          // converts raw to voltage using V_AGND + raw × (V_REF−V_AGND) / 256
    float v_all[PCF8591Minimal::NUM_CHANNELS];
    adc.read_all_voltage(v_all, 3.3f, 0.0f);                             // Read all channels as voltages, (out[4], vref=3.3 V, vagnd=0.0 V) → None
                                                                          // returns four voltages using the same conversion

    adc.configure(PCF8591Full::MODE_3_DIFFERENTIAL, false, false);      // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                          // sets AIP=01 (3 differential channels vs AIN3) and clears AOE/AI
    int8_t diff = adc.read_differential(0);                              // Read differential channel, (channel=0–2) → int8_t
                                                                          // returns signed 8-bit two's complement (-128 to 127)
    adc.configure(PCF8591Full::MODE_4_SINGLE_ENDED, false, true);       // Configure input mode, (input_mode=0–3, auto_increment=bool, dac_enabled=bool) → None
                                                                          // restores 4 single-ended mode and enables the DAC output
    adc.set_dac(128);                                                    // Enable DAC and set raw value, (value=0–255) → None
                                                                          // sets AOE=1 and writes 128 to the DAC register; V_AOUT ≈ V_REF/2
    adc.set_dac_voltage(0.25f);                                          // Set DAC as fraction of (VREF−VAGND), (fraction=0.0–1.0) → None
                                                                          // maps fraction to 0–255 and writes the DAC; AOUT follows
    adc.disable_dac();                                                   // Disable DAC output, () → None
                                                                          // clears AOE; AOUT returns to high-impedance
    HAL_Delay(1000);
        HAL_Delay(10);
    }

}
