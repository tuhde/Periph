#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "PCF8523.h"

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

static void onEvent(uint8_t status) {
    printf("interrupt status=0x%02X\r\n", status);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, PCF8523Minimal::I2C_ADDRESS);

    PCF8523Full rtc(connection);                             // Create PCF8523 Full driver, (connection)
                                                             // enables battery switch-over standard mode (PM=000)

    PCF8523Minimal::DateTime dt{2026, 9, 23, 3, 14, 30, 0};
    rtc.setDatetime(dt);                                     // Write calendar clock, (year, month, day, weekday 0=Sun, hour, minute, second) → void
                                                             // STOP-bit precision start; forces 24-hour mode and clears OS
    rtc.getDatetime(dt);                                     // Read calendar clock, () → DateTime
                                                             // decodes the seven BCD clock/calendar registers
    bool stopped = rtc.oscillatorStopped();                  // Query oscillator-stop flag, () → bool
                                                             // true means the time may be invalid until setDatetime

    PCF8523Full::Alarm alarm{0, 9, PCF8523Full::ALARM_DISABLED, PCF8523Full::ALARM_DISABLED};
    rtc.setAlarm(alarm);                                     // Configure alarm, (minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
                                                             // fires daily at 09:00
    rtc.getAlarm(alarm);                                     // Read alarm, () → Alarm
                                                             // disabled fields decode as ALARM_DISABLED

    rtc.configureTimerA(PCF8523Full::TimerAMode::Countdown, 10, PCF8523Full::SourceClock::Hz1);  // Start Timer A, (mode, value 0–255, sourceClock, pulsed=false) → void
                                                             // counts down 10 s, then sets CTAF
    uint8_t remainingA = rtc.readTimerA();                   // Read Timer A counter, () → uint8_t
                                                             // live value, not the loaded one
    rtc.disableTimerA();                                     // Stop Timer A, () → void

    rtc.configureTimerB(30, PCF8523Full::SourceClock::Hz1, 62.5f, true);  // Start Timer B, (value 0–255, sourceClock, pulseWidthMs=46.875 ms, pulsed=false) → void
                                                             // 30 s countdown, pulsed 62.5 ms low on INT1 and INT2
    uint8_t remainingB = rtc.readTimerB();                   // Read Timer B counter, () → uint8_t
    rtc.disableTimerB();                                     // Stop Timer B, () → void

    rtc.setClockOutput(1);                                   // Drive CLKOUT, (frequencyHz) → void
                                                             // 1 Hz square wave on the shared INT1/CLKOUT pin
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void
                                                             // frees INT1 for interrupts

    rtc.setOffset(-3, PCF8523Full::OffsetMode::EveryTwoHours);  // Write offset calibration, (offset −64–63, mode=EveryTwoHours) → void
                                                             // −3 LSB × 4.34 ppm = −13.02 ppm correction
    int8_t offset;
    PCF8523Full::OffsetMode offsetMode;
    rtc.getOffset(offset, offsetMode);                       // Read offset calibration, () → (int8_t, OffsetMode)

    rtc.configureBatteryBackup(PCF8523Full::BatteryMode::Standard, true);  // Select battery switch-over, (mode, lowDetection=true) → void
                                                             // switches to VBAT when VDD < VBAT and VDD < 2.5 V
    bool switched = rtc.isBatterySwitchedOver();             // Query switch-over flag, () → bool
    rtc.clearBatterySwitchover();                            // Clear switch-over flag, () → void
    bool low = rtc.isBatteryLow();                           // Query battery-low flag, () → bool
                                                             // read-only; clears itself once the cell is replaced

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
                                                             // needs an INT pin on the connection or the intPin argument
    rtc.enableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_BATTERY_LOW);  // Enable sources, (source) → void
                                                             // sets AIE, CTBIE and BLIE
    uint8_t status = rtc.pollInterrupt();                    // Poll & clear flags, () → uint8_t
                                                             // clears CTAF/CTBF/SF/AF/BSF, returns the pre-clear mask
    rtc.disableInterrupt(PCF8523Full::SOURCE_ALARM | PCF8523Full::SOURCE_TIMER_A | PCF8523Full::SOURCE_TIMER_B | PCF8523Full::SOURCE_BATTERY_LOW);  // Disable sources, (source) → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void

    rtc.softwareReset();                                     // Software reset, () → void
                                                             // control registers back to POR (PM=111); time is kept

    printf("%04u-%02u-%02u %02u:%02u:%02u os_stopped=%d\r\n",
        dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, stopped);
    printf("alarm %u:%u timerA=%u timerB=%u\r\n", alarm.hour, alarm.minute, remainingA, remainingB);
    printf("offset=%d every_minute=%d switched=%d low=%d status=0x%02X\r\n",
        offset, offsetMode == PCF8523Full::OffsetMode::EveryMinute, switched, low, status);
    while (true) HAL_Delay(1000);
}
