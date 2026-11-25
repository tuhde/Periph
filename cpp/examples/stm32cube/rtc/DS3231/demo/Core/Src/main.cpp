#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "DS3231.h"

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

// Backup-clock module for a data logger: reseeds the clock after a power
// loss, then logs a "reading" on every once-per-minute and once-per-hour
// alarm match, using the on-chip temperature sensor as the payload.

static volatile bool alarm1Fired = false, alarm2Fired = false;

static void onAlarm(uint8_t status) {
    if (status & DS3231Full::SOURCE_ALARM1) alarm1Fired = true;
    if (status & DS3231Full::SOURCE_ALARM2) alarm2Fired = true;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, DS3231Minimal::I2C_ADDRESS);
    DS3231Full rtc(connection);

    // --- Detect a lost power reference and reseed if needed ---
    if (rtc.oscillatorStopped()) {
        DS3231Minimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);  // also clears the Oscillator Stop Flag
        printf("oscillator was stopped - reseeded from reference timestamp\r\n");
    }

    // --- Arm a once-per-minute log tick and a once-per-hour summary tick ---
    DS3231Full::Alarm1 perMinute{0, 0, 0, 0, false, DS3231Full::ALARM1_MATCH_SECONDS};
    rtc.setAlarm1(perMinute);
    DS3231Full::Alarm2 hourly{0, 0, 0, false, DS3231Full::ALARM2_MATCH_MINUTES};
    rtc.setAlarm2(hourly);

    rtc.onInterrupt(onAlarm);
    rtc.enableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);

    int logged = 0;
    while (logged < 5) {
        if (alarm1Fired || alarm2Fired) {
            DS3231Minimal::DateTime dt;
            rtc.getDatetime(dt);
            float tempC = rtc.readTemperature();
            if (alarm1Fired) {
                printf("[minute] %02u:%02u:%02u  %.2f C\r\n", dt.hour, dt.minute, dt.second, (double)tempC);
                logged++;
            }
            if (alarm2Fired) {
                printf("[hourly] %02u:%02u  %.2f C\r\n", dt.hour, dt.minute, (double)tempC);
            }
            alarm1Fired = alarm2Fired = false;
        }
        HAL_Delay(200);
    }

    rtc.disableInterrupt(DS3231Full::SOURCE_ALARM1 | DS3231Full::SOURCE_ALARM2);
    rtc.offInterrupt();
    while (true) HAL_Delay(1000);
}
