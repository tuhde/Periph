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

    printf("config 0x%04X\r\n", sensor.readConfig());         // Read CONFIGURATION, () → uint16_t
    printf("limits low %.4f high %.4f\r\n",
           (double)sensor.getTLow(), (double)sensor.getTHigh());  // Read T_LOW, () → °C; Read T_HIGH, () → °C

    // --- Continuous reading at 4 Hz for 5 s ---
    for (int i = 0; i < 20; i++) {
        printf("%.4f C (raw %d)\r\n", (double)sensor.readTemperature(), (int)sensor.readRaw());  // Read temperature, () → °C; Read raw count, () → int16_t
        HAL_Delay(250);
    }

    // --- 8 Hz and the 13-bit extended mode ---
    sensor.setConversionRate(8.0f);                         // Set conversion rate, (hz) → bool
    sensor.setExtendedMode(true);                           // Set extended mode, (on) → void
    HAL_Delay(200);
    printf("extended %.4f C\r\n", (double)sensor.readTemperature());  // Read temperature, () → °C
    sensor.setExtendedMode(false);                          // Set extended mode, (on) → void
    sensor.setConversionRate(4.0f);                         // Set conversion rate, (hz) → bool

    // --- Shutdown and one-shot readings (about 10 ms each) ---
    sensor.shutdown();                                      // Shut down, () → void
    for (int i = 0; i < 5; i++) {
        float oneShot = 0.0f;
        sensor.oneShot(oneShot);                            // One-shot reading, (celsius&) → bool
        printf("one-shot %.4f C\r\n", (double)oneShot);
    }
    sensor.wake();                                          // Wake, () → void

    // --- Thermostat around the current temperature ---
    float t = sensor.readTemperature();                     // Read temperature, () → °C
    sensor.setTHigh(t + 1.0f);                              // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(t - 1.0f);                               // Set T_LOW, (celsius °C) → void
    sensor.setFaultQueue(2);                                // Set fault queue, (count) → bool
    sensor.setPolarity(false);                              // Set ALERT polarity, (activeHigh) → void
    sensor.setThermostatMode(true);                         // Set thermostat mode, (interrupt) → void
    printf("warm the sensor with a finger ...\r\n");
    for (int i = 0; i < 40; i++) {
        if (sensor.pollInterrupt()) {                       // Poll the alert state, () → bool
            printf("ALERT: %.2f C, alert_state=%d\r\n", (double)sensor.readTemperature(), sensor.alertState());  // Read alert state, () → bool
        }
        HAL_Delay(250);
    }
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
    sensor.offInterrupt();                                  // Unsubscribe, () → void

    // --- Restore the power-on defaults ---
    sensor.setThermostatMode(false);                        // Set thermostat mode, (interrupt) → void
    sensor.setFaultQueue(1);                                // Set fault queue, (count) → bool
    sensor.setTHigh(80.0f);                                 // Set T_HIGH, (celsius °C) → void
    sensor.setTLow(75.0f);                                  // Set T_LOW, (celsius °C) → void
    sensor.writeConfig(0x60A0);                             // Write CONFIGURATION, (value) → void
    sensor.reset();                                         // General-call reset, () → void
    while (true) HAL_Delay(1000);
}
