#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "TMP102.h"

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

static volatile float alertTemperature = 0.0f;
static volatile bool pending = false;

static void onAlert(float temperatureC) {
    alertTemperature = temperatureC;
    pending = true;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, TMP102Minimal::I2C_ADDRESS);
    TMP102Full sensor(connection);                          // Create TMP102 Full driver, (connection)

    float t = sensor.readTemperature();                     // Read temperature, () → °C
                                                            // decodes TEMPERATURE, 0.0625 °C two's complement
    printf("temperature %.4f C\r\n", (double)t);
    printf("raw %d\r\n", (int)sensor.readRaw());              // Read raw count, () → int16_t
                                                            // signed 12/13-bit count, 0.0625 °C per LSB

    printf("config 0x%04X\r\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
                                                            // reset value 0x60A0
    sensor.writeConfig(sensor.readConfig());                // Write CONFIGURATION, (value) → void
                                                            // read-only bits are masked out

    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
                                                            // 0.25, 1, 4 or 8 Hz
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
                                                            // EM bit, 13-bit readings up to +255.9375 °C
    HAL_Delay(200);
    printf("extended %.4f C\r\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
                                                            // back to 12 bit

    sensor.setTHigh(30.0f);                                 // Set T_HIGH, (celsius °C) → void
                                                            // rounded to 0.0625 °C
    sensor.setTLow(10.0f);                                  // Set T_LOW, (celsius °C) → void
                                                            // rounded to 0.0625 °C
    printf("high %.4f C\r\n", (double)sensor.getTHigh());     // Read T_HIGH, () → °C
                                                            // same format as TEMPERATURE
    printf("low %.4f C\r\n", (double)sensor.getTLow());       // Read T_LOW, () → °C
                                                            // same format as TEMPERATURE

    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
                                                            // 1, 2, 4 or 6 consecutive faults
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
                                                            // POL bit, false = active low
    sensor.setThermostatMode(false);                        // Set thermostat mode, (interrupt) → void
                                                            // TM bit, false = comparator
    printf("alert %d\r\n", sensor.alertState());              // Read alert state, () → bool
                                                            // AL bit corrected for POL

    sensor.shutdown();                                      // Shut down, () → void
                                                            // SD bit, stops after the current conversion
    float oneShot = 0.0f;
    sensor.oneShot(oneShot);                                // One-shot reading, (celsius&) → bool
                                                            // OS=1, polls until done, restores the mode
    printf("one-shot %.4f C\r\n", (double)oneShot);
    sensor.wake();                                          // Wake, () → void
                                                            // resume continuous conversion

    printf("polling %d\r\n", sensor.pollInterrupt());         // Poll the alert state, () → bool
                                                            // reads CONFIGURATION and TEMPERATURE
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
                                                            // callback receives the temperature in °C
    HAL_Delay(3000);
    if (pending) printf("alert at %.2f C\r\n", (double)alertTemperature);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler

    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool
                                                            // back to the power-on default
    sensor.setTHigh(80.0f);                                 // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(75.0f);                                  // Set T_LOW, (celsius °C) → void
    sensor.reset();                                         // General-call reset, () → void
                                                            // resets every general-call device on the bus
    while (true) HAL_Delay(1000);
}
