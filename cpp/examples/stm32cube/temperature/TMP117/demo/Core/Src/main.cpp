#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "TMP117.h"

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

// PT100-replacement cold-chain container thermometer: maximum averaging gives
// the lowest-noise reading, and the ALERT output fires when the cargo leaves
// the -25 C to 8 C safe transport range. Each event is reported with the
// boundary that tripped. Set REFERENCE_C to a reference thermometer reading to
// calibrate once and persist the offset to EEPROM.

static const int MAX_ALERTS = 10;
static const bool CALIBRATE = false;
static const float REFERENCE_C = 4.0f;

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
    I2CConnectionSTM32Cube connection(&hi2c1, TMP117Minimal::I2C_ADDRESS);
    TMP117Full sensor(connection);                          // Create TMP117 Full driver, (connection)

    // --- Lowest-noise continuous conversion ---
    // 64-conversion averaging with a 1 s cycle gives the quietest result the
    // chip can deliver — a cold-chain log needs stability, not speed.
    sensor.configure(TMP117Full::Mode::Continuous, 64, 1.0f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool

    // --- One-time calibration against a reference thermometer ---
    // The observed error is written to TEMP_OFFSET with the EEPROM unlocked, so
    // the correction survives power cycles. EEPROM endurance is limited — this
    // is a once-per-deployment step, not a loop.
    if (CALIBRATE) {
        HAL_Delay(1100);
        float offset = REFERENCE_C - sensor.readTemperature()  // Read temperature, () → °C
                       + sensor.getTemperatureOffset();     // Read calibration offset, () → °C
        sensor.unlockEeprom();                              // Unlock EEPROM, () → void
        sensor.setTemperatureOffset(offset);                // Set calibration offset, (celsius °C) → void
        while (sensor.isEepromBusy()) {                     // Check EEPROM busy, () → bool
            HAL_Delay(1);
        }
        sensor.lockEeprom();                                // Lock EEPROM, () → void
        printf("calibrated, offset %.4f C\r\n", (double)offset);
    }

    // --- Program the safe transport range ---
    // Alert mode flags either side of the window independently; ALERT is
    // active-low open-drain, pulled up on the board.
    sensor.setHighLimit(8.0f);                              // Set THIGH_LIMIT, (celsius °C) → void
    sensor.setLowLimit(-25.0f);                             // Set TLOW_LIMIT, (celsius °C) → void
    sensor.configureAlert(TMP117Full::AlertMode::Alert,
                          TMP117Full::AlertPolarity::ActiveLow);  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
    printf("monitoring, %.2f C now\r\n", (double)sensor.readTemperature());  // Read temperature, () → °C

    // --- Report which boundary tripped ---
    // Without an ALERT pin on the connection, poll the alert flags instead.
    // Reading them clears them in Alert mode, re-arming for the next excursion.
    int alerts = 0;
    while (alerts < MAX_ALERTS) {
        if (!connection.intPin()) {
            uint8_t status = sensor.pollInterrupt();        // Read alert flags, () → uint8_t mask
            if (status) {
                pendingStatus = status;
                pending = true;
            }
        }
        if (pending) {
            pending = false;
            uint8_t status = pendingStatus;
            float t = sensor.readTemperature();             // Read temperature, () → °C
            if (status & TMP117Full::SOURCE_HIGH)     printf("%.2f C  too warm - cargo above 8 C\r\n", (double)t);
            else if (status & TMP117Full::SOURCE_LOW) printf("%.2f C  too cold - cargo below -25 C\r\n", (double)t);
            alerts++;
        }
        HAL_Delay(1000);
    }

    // --- Stop monitoring after the demo run ---
    sensor.offInterrupt();                                  // Unsubscribe, () → void
    while (true) HAL_Delay(1000);
}
