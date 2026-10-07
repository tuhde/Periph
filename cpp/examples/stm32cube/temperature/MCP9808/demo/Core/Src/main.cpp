#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "MCP9808.h"

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

// Industrial freezer monitor: the healthy range is -25 C to -15 C, and
// -5 C means the door has been left open too long. The Alert output fires
// in interrupt mode each time the temperature leaves or re-enters the
// window; each event is reported with the boundary that tripped.

static const int MAX_ALERTS = 10;

static volatile uint8_t pendingStatus = 0;
static volatile bool pending = false;

static void onAlert(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, MCP9808Minimal::I2C_ADDRESS);
    MCP9808Full sensor(connection);                         // Create MCP9808 Full driver, (connection)

    // --- Trade resolution for faster sampling ---
    // 0.25 C is plenty for a freezer and converts in ~65 ms instead of 250 ms,
    // so a door opening shows up in the next reading almost immediately.
    sensor.setResolution(0.25f);                            // Set resolution, (celsius 0.5|0.25|0.125|0.0625) → bool

    // --- Program the healthy window and the door-open threshold ---
    // TLOWER/TUPPER bracket normal operation; TCRIT flags a door left open.
    // 3 C of hysteresis stops the Alert chattering while the compressor cycles.
    sensor.setLowerLimit(-25.0f);                           // Set TLOWER, (celsius °C) → void
    sensor.setUpperLimit(-15.0f);                           // Set TUPPER, (celsius °C) → void
    sensor.setCriticalLimit(-5.0f);                         // Set TCRIT, (celsius °C) → void
    sensor.setHysteresis(3.0f);                             // Set hysteresis, (celsius 0|1.5|3.0|6.0) → bool

    // --- Route every boundary to the Alert pin as a latched interrupt ---
    // Interrupt mode latches each crossing until clearInterrupt(), so a short
    // excursion is never missed between two reads.
    sensor.configureAlert(MCP9808Full::AlertMode::All,
                          MCP9808Full::AlertOutput::Interrupt,
                          MCP9808Full::AlertPolarity::ActiveLow);  // Configure Alert, (mode=All, output=Comparator, polarity=ActiveLow) → bool
    sensor.enableAlert();                                   // Enable Alert output, () → void
    sensor.onInterrupt(onAlert);                            // Subscribe to Alert, (callback, intPin=nullptr) → void
    printf("monitoring, %.2f C now\r\n", (double)sensor.readTemperature());  // Read ambient temperature, () → °C

    // --- Report which boundary tripped, then re-arm ---
    // Without an Alert pin on the connection, poll ALERT_STAT instead. An empty
    // status mask means the temperature has come back inside the healthy window.
    int alerts = 0;
    while (alerts < MAX_ALERTS) {
        if (!connection.intPin() && sensor.isAlertAsserted()) {  // Check Alert output, () → bool
            pendingStatus = sensor.pollInterrupt();         // Read boundary status, () → uint8_t mask
            pending = true;
        }
        if (pending) {
            pending = false;
            uint8_t status = pendingStatus;
            float t = sensor.readTemperature();             // Read ambient temperature, () → °C
            if (status & MCP9808Full::SOURCE_CRITICAL)   printf("%.2f C  CRITICAL - door open?\r\n", (double)t);
            else if (status & MCP9808Full::SOURCE_UPPER) printf("%.2f C  too warm\r\n", (double)t);
            else if (status & MCP9808Full::SOURCE_LOWER) printf("%.2f C  too cold\r\n", (double)t);
            else                                         printf("%.2f C  back in range\r\n", (double)t);
            sensor.clearInterrupt();                        // Clear interrupt-mode Alert, () → void
            alerts++;
        }
        HAL_Delay(1000);
    }

    // --- Shut down cleanly after the demo run ---
    sensor.disableAlert();                                  // Disable Alert output, () → void
    sensor.offInterrupt();                                  // Unsubscribe, () → void
    while (true) HAL_Delay(1000);
}
