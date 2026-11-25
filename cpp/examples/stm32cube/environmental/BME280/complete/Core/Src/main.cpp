#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BME280.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x76);
    BME280Full bme(connection);

    HAL_Delay(2000);

    uint8_t cid = bme.chip_id();                        // Read chip ID, () → uint8_t
                                                         // returns 0x60 for BME280
    bme.configure(1, 1, 1, 0, 0, 0);                    // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1/3, filter 0–4, t_sb 0–7) → void
                                                         // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(BME280Full::OSRS_X4, BME280Full::OSRS_X2, BME280Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → void
                                                         // humidity update requires ctrl_meas write to latch
    bme.set_mode(BME280Full::MODE_FORCED);              // Set power mode, (mode 0/1/3) → void
    bme.set_filter(BME280Full::FILTER_4);               // Set IIR filter, (coeff 0–4) → void
                                                         // suppresses short-term pressure disturbances
    bme.set_standby(BME280Full::T_SB_125_MS);           // Set standby time, (t_sb 0–7) → void
                                                         // only relevant in normal mode; codes 6/7 mean 10/20 ms on BME280
    uint8_t st = bme.status();                          // Read status register, () → uint8_t
    float t = bme.temperature();                        // Read temperature, () → float °C
    float p = bme.pressure();                           // Read pressure, () → float hPa
    float h = bme.humidity();                           // Read humidity, () → float %RH
    float alt = bme.altitude();                         // Compute altitude, (sea_level_hpa=1013.25) → float m
                                                         // uses barometric formula to convert pressure to metres
    float slp = bme.sea_level_pressure(alt);            // Compute sea-level pressure, (altitude_m) → float hPa
    float dp = bme.dew_point();                         // Compute dew point, () → float °C
                                                         // Magnus-Tetens approximation from current T and RH
    bme.reset();                                        // Soft reset chip, () → void
                                                         // re-reads calibration and re-applies configuration

    printf("T="); printf("%.1f", t); printf(" C, P=");
    printf("%.1f", p); printf(" hPa, RH=");
    printf("%.1f", h); printf(" %%RH, alt=");
    printf("%.1f", alt); printf(" m, dp=");
    printf("%.1f", dp); printf(" C\r\n");
    printf("===DONE: 0 passed, 0 failed===\r\n");
    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
