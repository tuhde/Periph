#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "ADE7953.h"

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

    I2CConnectionSTM32Cube connection(&hi2c1, 0x38);
    ADE7953Full ade(connection, 251.0f, 30.0f);                     // Create ADE7953 driver, (connection, voltage_gain=V/V, current_gain=A/V, bus_type='i2c')

    HAL_Delay(2000);

    printf("version: 0x%02X\r\n", ade.version());                     // Read silicon version, () → 0x..
                                                                      // returns the silicon revision
    printf("V=%.2f\r\n",   ade.voltage());                            // Read bus voltage, () → V
                                                                      // converts raw VRMS to volts using voltage_gain
    printf("I_a=%.3f\r\n", ade.current());                            // Read load current, () → A
                                                                      // converts raw IRMSA to amperes using current_gain
    printf("P_a=%.2f\r\n", ade.activePower());                        // Read active power, () → W
                                                                      // converts raw AWATT (instantaneous, 6.99 kHz) to watts
    printf("E_a=%.4f\r\n", ade.activeEnergy());                       // Read active energy, () → Wh
                                                                      // converts raw AENERGYA accumulated LSBs to watt-hours
    printf("PF=%.3f\r\n",  ade.powerFactor());                        // Read power factor, () → ratio
                                                                      // converts raw PFA (1 LSB = 2^-15) to a −1.0..+1.0 ratio
    printf("f=%.2f\r\n",    ade.lineFrequency());                      // Read line frequency, () → Hz

    ade.configureChannelB(30.0f);                                    // Set Channel B calibration, (current_gain_b) → none
    printf("I_b=%.3f\r\n", ade.currentB());                            // Read Current Channel B, () → A

    ade.setActiveEnergyMode('a', 0);                                 // Set active-energy mode A, (channel, mode) → none
                                                                      // mode = 0 (normal) | 1 (positive-only) | 2 (absolute)
    ade.setPga('a', 1);                                              // Write PGA Channel A, (channel, gain) → none
                                                                      // gain 1, 2, 4, 8, 16 (+22 valid only for Channel A)
    ade.setPhaseCalibration('a', 0.0f);                              // Write phase calibration A, (channel, delay_s) → none
    ade.setGainCalibration(0x282, 0x400000);    // Write active-power gain A, (reg, value) → none
                                                                      // 0x400000 = unity; valid range 0x200000..0x600000
    ade.setOffsetCalibration(0x289, 0);       // Write active-power offset A, (reg, value) → none
                                                                      // signed 24-bit offset
    printf("checksum: 0x%08X\r\n", (unsigned)ade.checksum());         // Read CRC/checksum, () → u32
    ade.enableChecksum(true);                                        // Enable CRC/checksum, (enabled) → none

    ade.configureOvervoltage(260.0f);                                // Configure overvoltage, (threshold) → none
                                                                      // threshold in volts (same scale as voltage())
    ade.configureOvercurrent(40.0f);                                 // Configure overcurrent, (threshold) → none
                                                                      // threshold in amperes; applies to BOTH current channels

    ade.reset();                                                     // Software reset, () → none
                                                                      // waits 110 ms then re-runs the mandatory power-up sequence
    while (true) HAL_Delay(1000);
}
