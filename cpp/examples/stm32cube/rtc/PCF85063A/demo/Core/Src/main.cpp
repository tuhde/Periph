#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF85063A.h"

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

// Scheduling core of a battery-powered logger: reseeds the clock after a
// power loss, counts boots in the free RAM byte, then wakes on an hourly
// alarm to print a timestamp while a 30-second pulsed countdown timer
// toggles an LED as a "still running" heartbeat.

static volatile uint8_t pendingStatus = 0;
static bool ledOn = false;

static void onEvent(uint8_t status) {
    pendingStatus |= status;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, PCF85063AMinimal::I2C_ADDRESS);

    PCF85063AFull rtc(connection);                           // Create PCF85063A Full driver, (connection)

    // --- Detect a lost time reference and reseed if needed ---
    // A fresh chip, or one whose supply dropped out, reports the OS flag set:
    // its calendar cannot be trusted until it is reseeded. The crystal needs
    // up to 2 s to start, so wait until the flag stays clear.
    if (rtc.oscillatorStopped()) {                           // Query oscillator-stop flag, () → bool
        PCF85063AMinimal::DateTime reference{2026, 1, 1, 4, 0, 0, 0};
        rtc.setDatetime(reference);                          // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → bool
        printf("oscillator was stopped - reseeded from reference timestamp\r\n");
        while (rtc.oscillatorStopped()) {                    // Query oscillator-stop flag, () → bool
            HAL_Delay(100);
        }
    }

    // --- Count boots in the free RAM byte ---
    // The RAM byte survives as long as VDD is present, so it works as a tiny
    // non-volatile counter next to the clock.
    uint8_t boots = (uint8_t)(rtc.readRam() + 1);            // Read RAM byte, () → uint8_t
    rtc.writeRam(boots);                                     // Write RAM byte, (value 0–255) → void
    PCF85063AMinimal::DateTime now;
    rtc.getDatetime(now);                                    // Read calendar clock, () → DateTime
    int8_t offset;
    PCF85063AFull::OffsetMode offsetMode;
    rtc.getOffset(offset, offsetMode);                       // Read offset calibration, () → (int8_t, OffsetMode)
    printf("boot #%u %04u-%02u-%02u %02u:%02u:%02u offset=%d\r\n",
        boots, now.year, now.month, now.day, now.hour, now.minute, now.second, offset);

    // --- Tune the board for low power ---
    // Match the crystal's load capacitance and switch the 32.768 kHz CLKOUT
    // off — it is enabled at power-on and costs current on a battery device.
    rtc.setOscillatorLoad(12.5f);                            // Select crystal load, (picofarads 7|12.5) → void
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void

    // --- Hourly wake-up plus a 30 s heartbeat ---
    // Only second and minute are enabled, so the alarm matches at hh:00:00
    // every hour. The pulsed timer reloads automatically; minute ticks share
    // the same TF flag and are reported as SOURCE_TIMER.
    PCF85063AFull::Alarm hourly{0, 0, PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED};
    rtc.setAlarm(hourly);                                    // Configure alarm, (second, minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
    rtc.configureTimer(30, PCF85063AFull::SourceClock::Hz1, true);  // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → void
    rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Minute);  // Select minute interrupt, (mode Off|Minute|HalfMinute) → void

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
    rtc.enableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_TIMER);  // Enable sources, (source) → void

    // --- Dispatch by source: log on the alarm, blink on the heartbeat ---
    // Without an INT pin on the connection, poll the flags instead.
    int alarms = 0;
    int ticks = 0;
    while (alarms < 5 && ticks < 5 * 60 * 5) {
        if (!connection.intPin()) pendingStatus |= rtc.pollInterrupt();  // Poll & clear flags, () → uint8_t
        uint8_t status = pendingStatus;
        pendingStatus = 0;
        if (status & PCF85063AFull::SOURCE_ALARM) {
            PCF85063AMinimal::DateTime dt;
            rtc.getDatetime(dt);                             // Read calendar clock, () → DateTime
            printf("[hourly] %04u-%02u-%02u %02u:%02u:%02u\r\n",
                dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second);
            alarms++;
        }
        if (status & PCF85063AFull::SOURCE_TIMER) {
            ledOn = !ledOn;
            printf("[heartbeat] LED %s\r\n", ledOn ? "on" : "off");
        }
        HAL_Delay(200);
        ticks++;
    }

    // --- Leave the chip quiet on exit ---
    rtc.disableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_TIMER);  // Disable sources, (source) → void
    rtc.disableTimer();                                      // Stop timer, () → void
    rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Off);  // Select minute interrupt, (mode Off|Minute|HalfMinute) → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void
    while (true) HAL_Delay(1000);
}
