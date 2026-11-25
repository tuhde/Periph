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
                                                            // checks DEVICE_ID bits 11:0 == 0x117

    float t = sensor.readTemperature();                     // Read temperature, () → °C
                                                            // decodes TEMP_RESULT, 0.0078125 °C two's complement
    printf("temperature %.4f C\r\n", (double)t);

    sensor.configure(TMP117Full::Mode::Continuous, 32, 0.5f);  // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
                                                            // writes MOD/AVG/CONV; cycle snaps to the nearest CONV step
    TMP117Full::Config cfg = sensor.getConfig();            // Read conversion config, () → Config {mode, averaging, cycleSeconds s}
                                                            // decodes MOD, AVG and CONV from CONFIGURATION
    printf("mode %d averaging %d cycle %.4f s\r\n", (int)cfg.mode, cfg.averaging, (double)cfg.cycleSeconds);

    sensor.configure(TMP117Full::Mode::Shutdown);           // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
                                                            // MOD=01 stops conversions; TEMP_RESULT keeps its last value
    printf("shutdown %d\r\n", sensor.isShutdown());           // Check Shutdown mode, () → bool
                                                            // reads MOD[1:0] == 01
    sensor.triggerOneShot();                                // Start one conversion, () → void
                                                            // MOD=11; returns to Shutdown when done
    while (!sensor.isDataReady()) {                         // Check for a fresh result, () → bool
        HAL_Delay(10);                              // reading Data_Ready clears it
    }
    printf("one-shot %.4f C\r\n", (double)sensor.readTemperature());  // Read temperature, () → °C
                                                            // the one-shot result
    sensor.configure();                                     // Configure conversion, (mode=Continuous, averaging=8, cycleSeconds=1.0 s) → bool
                                                            // back to the POR default

    sensor.setHighLimit(30.0f);                             // Set THIGH_LIMIT, (celsius °C) → void
                                                            // rounded to the nearest 0.0078125 °C step
    sensor.setLowLimit(10.0f);                              // Set TLOW_LIMIT, (celsius °C) → void
                                                            // rounded to the nearest 0.0078125 °C step
    printf("high %.4f C\r\n", (double)sensor.getHighLimit()); // Read THIGH_LIMIT, () → °C
                                                            // same format as TEMP_RESULT
    printf("low %.4f C\r\n", (double)sensor.getLowLimit());   // Read TLOW_LIMIT, () → °C
                                                            // same format as TEMP_RESULT

    sensor.setTemperatureOffset(0.25f);                     // Set calibration offset, (celsius °C) → void
                                                            // added to every result after linearization
    printf("offset %.4f C\r\n", (double)sensor.getTemperatureOffset());  // Read calibration offset, () → °C
                                                            // decodes TEMP_OFFSET
    sensor.setTemperatureOffset(0.0f);                      // Set calibration offset, (celsius °C) → void
                                                            // remove the offset again

    sensor.unlockEeprom();                                  // Unlock EEPROM, () → void
                                                            // EUN=1: EEPROM-backed writes now persist
    printf("eeprom busy %d\r\n", sensor.isEepromBusy());      // Check EEPROM busy, () → bool
                                                            // reads EEPROM_UL.EEPROM_Busy
    sensor.lockEeprom();                                    // Lock EEPROM, () → void
                                                            // EUN=0: writes are volatile again
    uint16_t scratch = 0;
    sensor.readEepromScratch(1, scratch);                   // Read EEPROM scratch, (slot 1|2|3, value&) → bool
                                                            // slot 1 holds part of the factory unique ID
    printf("eeprom1 0x%04X\r\n", scratch);
    sensor.writeEepromScratch(2, 0x1234);                   // Write EEPROM scratch, (slot 2, value 16-bit) → bool
                                                            // only EEPROM2 is writable; volatile while locked
    sensor.readEepromScratch(2, scratch);                   // Read EEPROM scratch, (slot 1|2|3, value&) → bool
                                                            // reads back EEPROM2
    printf("eeprom2 0x%04X\r\n", scratch);

    sensor.configureAlert(TMP117Full::AlertMode::Alert,
                          TMP117Full::AlertPolarity::ActiveLow,
                          TMP117Full::AlertPinFunction::Alert);  // Configure ALERT, (mode=Alert, polarity=ActiveLow, pinFunction=Alert) → void
                                                            // sets T/nA, POL and DR/Alert together

    uint8_t status = sensor.pollInterrupt();                // Read alert flags, () → uint8_t mask
                                                            // HIGH_Alert/LOW_Alert; the read clears them in Alert mode
    printf("above high %d below low %d\r\n",
           (status & TMP117Full::SOURCE_HIGH) != 0, (status & TMP117Full::SOURCE_LOW) != 0);

    sensor.onInterrupt(onAlert);                            // Subscribe to ALERT, (callback, intPin=nullptr) → void
                                                            // callback receives the pollInterrupt() mask
    HAL_Delay(5000);
    if (pending) printf("alert, status mask %u\r\n", pendingStatus);
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler

    sensor.reset();                                         // Software reset, () → void
                                                            // reloads CONFIGURATION/limits/offset from EEPROM, 2 ms
    while (true) HAL_Delay(1000);
}
