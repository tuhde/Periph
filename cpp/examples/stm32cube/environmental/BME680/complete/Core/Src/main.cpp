#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "BME680.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x77);
    BME680Full bme(connection);

    HAL_Delay(2000);

    uint8_t cid = bme.chip_id();                       // Read chip ID, () → int
                                                        // returns 0x61 for BME680
    printf("chip_id=%d\r\n", (int)(cid));

    bme.configure(BME680Full::OSRS_X1, BME680Full::OSRS_X1, BME680Full::OSRS_X1, BME680Full::MODE_SLEEP, BME680Full::FILTER_0);  // Configure chip, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5, mode 0/1, filter 0–7) → void
                                                        // writes ctrl_hum, config, ctrl_meas in correct order
    bme.set_oversampling(BME680Full::OSRS_X4, BME680Full::OSRS_X2, BME680Full::OSRS_X1);  // Set oversampling, (osrs_t 0–5, osrs_p 0–5, osrs_h 0–5) → void
                                                        // changes conversion time vs resolution trade-off
    bme.set_filter(BME680Full::FILTER_7);               // Set IIR filter, (coeff 0–7) → void
                                                        // applies to temperature and pressure only
    bme.set_heater(320, 150);                           // Configure heater profile 0, (temp_c, duration_ms) → void
                                                        // sets target temperature and on-time, then selects profile 0
    bme.set_heater_profile(1, 200, 100);                // Configure heater profile 1, (index 0–9, temp_c, duration_ms) → void
                                                        // stores profile 1 without activating it
    bme.select_heater_profile(1);                       // Activate heater profile 1, (index 0–9) → void
                                                        // subsequent measurements use profile 1's heater settings
    bme.select_heater_profile(0);                       // Switch back to profile 0, (index 0–9) → void
                                                        // profile 0 is the default 320 °C / 150 ms configuration
    bme.set_gas_enabled(false);                         // Disable gas conversion, (enabled) → void
                                                        // skips the gas measurement phase to save power and time
    bme.set_gas_enabled(true);                          // Re-enable gas conversion, (enabled) → void
                                                        // restores gas measurement in the forced-mode cycle
    bme.set_heater_off(true);                           // Disable heater via heat_off override, (off) → void
                                                        // prevents heater activation regardless of profile settings
    bme.set_heater_off(false);                          // Re-enable heater, (off) → void
                                                        // clears the heat_off override bit
    bme.set_ambient_temperature(25.0f);                 // Override ambient for heater calc, (temp_c) → void
                                                        // recomputes heater resistance register using the new ambient value
    uint8_t st = bme.status();                          // Read status register, () → uint8_t
                                                        // bit 7 = new_data, bit 6 = gas_measuring, bit 5 = measuring

    float t, p, h, g;
    bme.read_all(t, p, h, g);                           // Read all sensors in one cycle, (t, p, h, g) → void
                                                        // returns (T, P, RH, R_gas) from single TPHG trigger
    bool gv = bme.gas_valid();                          // Check gas validity, () → bool
    bool hs = bme.heater_stable();                      // Check heater stability, () → bool
    bme.reset();                                        // Soft reset chip, () → void
                                                        // re-reads calibration and re-applies configuration

    printf("T=");
    printf("%.1f", t);
    printf(" C, P=");
    printf("%.1f", p);
    printf(" hPa, RH=");
    printf("%.1f", h);
    printf(" %, R_gas=");
    printf("%.0f", g);
    printf(" Ohm\r\n");

    while (true) {
    HAL_Delay(1000); 
        HAL_Delay(10);
    }

}
