#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "INA3221.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x40);
    INA3221Full ina(connection, /*r_shunt=*/0.1f);

    printf("0x%X\r\n", (unsigned)ina.manufacturer_id());       // Read Manufacturer ID, () → int 0x5449
                                                       // Texas Instruments ID
    printf("0x%X\r\n", (unsigned)ina.die_id());                // Read Die ID, () → int 0x3220
                                                       // INA3221 die revision

    for (uint8_t ch = 1; ch <= 3; ch++) {
        printf("%d\r\n", ina.voltage(ch));             // Read bus voltage, (channel) → float V
                                                       // left-aligned 12-bit bus register, 8 mV LSB
        printf("%d\r\n", ina.shunt_voltage(ch));        // Read shunt voltage, (channel) → float V
                                                       // left-aligned 13-bit signed shunt, 40 µV LSB
        printf("%d\r\n", ina.current(ch));              // Read load current, (channel) → float A
                                                       // computed from shunt voltage / r_shunt
        printf("%d\r\n", ina.power(ch));                // Read power, (channel) → float W
                                                       // computed from voltage × current
    }

    printf("%d\r\n", ina.conversion_ready());          // Check conversion done, () → bool
                                                       // reads CVRF bit from Mask/Enable register

    ina.configure(4, 4, 4, 7);                         // Configure ADC, (avg 0–7, vbus_ct 0–7, vsh_ct 0–7, mode 0–7) → None
                                                       // sets averaging count, conversion time, and operating mode

    ina.enable_channel(1, true);                     // Enable channel, (channel, enabled) → None
                                                       // modifies CH1en bit in Configuration register
    bool ena = ina.channel_enabled(1);               // Read channel enabled, (channel) → bool
                                                       // reads CH1en bit

    ina.set_critical_alert(1, 0.1f);                 // Set critical alert, (channel, limit_v, latch=False) → None
                                                       // per-conversion threshold on shunt voltage
    ina.set_warning_alert(2, 0.05f);                 // Set warning alert, (channel, limit_v, latch=False) → None
                                                       // per-average threshold on shunt voltage

    uint16_t flags = ina.alert_flags();               // Read alert flags, () → int
                                                       // reads Mask/Enable register, clears latched flags

    uint8_t channels[] = {1, 2};
    ina.set_summation_channels(channels, 2, 0.2f);   // Set summation channels, (channels, n, limit_v) → None
                                                       // enables SCC bits and sets sum limit register
    float sv_sum = ina.summation_value();             // Read summation value, () → float V
                                                       // reads Shunt-Voltage Sum register

    ina.set_power_valid_limits(5.5f, 4.5f);           // Set PV limits, (upper_v, lower_v) → None
                                                       // sets PV Upper/Lower Limit registers
    bool pv = ina.power_valid();                      // Read power valid, () → bool
                                                       // reads PVF bit from Mask/Enable

    ina.shutdown();                                   // Put chip into power-down mode, () → None
                                                       // saves current mode for wake()
    HAL_Delay(1);
    ina.wake();                                       // Restore operating mode, () → None
                                                       // restores the mode saved by shutdown()

    ina.reset();                                     // Reset all registers, () → None
                                                       // sets RST bit, chip re-initializes to defaults
    while (true) HAL_Delay(1000);
}
