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

static void onEvent(uint8_t status) {
    printf("interrupt status=0x%02X\r\n", status);
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, PCF85063AMinimal::I2C_ADDRESS);

    PCF85063AFull rtc(connection);                           // Create PCF85063A Full driver, (connection)
                                                             // bus check, forces 24-hour mode with STOP = 0

    PCF85063AMinimal::DateTime dt{2026, 9, 23, 3, 14, 30, 0};
    rtc.setDatetime(dt);                                     // Write calendar clock, (year 2000–2099, month, day, weekday 0=Sun, hour, minute, second) → bool
                                                             // STOP-bit precision start; forces 24-hour mode and clears OS
    rtc.getDatetime(dt);                                     // Read calendar clock, () → DateTime
                                                             // decodes the seven BCD clock/calendar registers
    bool stopped = rtc.oscillatorStopped();                  // Query oscillator-stop flag, () → bool
                                                             // true means the time may be invalid until setDatetime
    rtc.clearOscillatorStop();                               // Clear oscillator-stop flag, () → void
                                                             // still true afterwards means the crystal is not running
    rtc.stopClock();                                         // Freeze the clock, () → void
                                                             // STOP = 1, divider chain held in reset
    rtc.startClock();                                        // Release the clock, () → void

    PCF85063AFull::Alarm alarm{0, 0, 9, PCF85063AFull::ALARM_DISABLED, PCF85063AFull::ALARM_DISABLED};
    rtc.setAlarm(alarm);                                     // Configure alarm, (second, minute, hour, day, weekday; ALARM_DISABLED = ignore) → void
                                                             // fires daily at 09:00:00
    rtc.getAlarm(alarm);                                     // Read alarm, () → Alarm
                                                             // disabled fields decode as ALARM_DISABLED

    rtc.configureTimer(10, PCF85063AFull::SourceClock::Hz1, false);  // Start countdown timer, (value 1–255, sourceClock, pulsed=false) → void
                                                             // counts down 10 s, then sets TF
    uint8_t remaining = rtc.readTimer();                     // Read timer counter, () → uint8_t
                                                             // live value, not the loaded one
    rtc.disableTimer();                                      // Stop timer, () → void
                                                             // TE = 0, TIE = 0, 1/60 Hz clock for power saving

    rtc.setMinuteInterrupt(PCF85063AFull::MinuteInterrupt::Minute);  // Select minute interrupt, (mode Off|Minute|HalfMinute) → void
                                                             // sets MI; reported as SOURCE_TIMER

    rtc.setClockOutput(1);                                   // Drive CLKOUT, (frequencyHz) → void
                                                             // 1 Hz square wave on the CLKOUT pin
    rtc.disableClockOutput();                                // Disable CLKOUT, () → void
                                                             // COF = 111, pin low

    rtc.setOffset(-3, PCF85063AFull::OffsetMode::Normal);    // Write offset calibration, (offset −64–63, mode=Normal) → void
                                                             // −3 LSB × 4.34 ppm = −13.02 ppm correction
    int8_t offset;
    PCF85063AFull::OffsetMode offsetMode;
    rtc.getOffset(offset, offsetMode);                       // Read offset calibration, () → (int8_t, OffsetMode)

    rtc.setOscillatorLoad(12.5f);                            // Select crystal load, (picofarads 7|12.5) → void
                                                             // match the crystal's specified load capacitance
    rtc.writeRam(0xA5);                                      // Write RAM byte, (value 0–255) → void
    uint8_t ram = rtc.readRam();                             // Read RAM byte, () → uint8_t

    rtc.onInterrupt(onEvent);                                // Subscribe to interrupts, (callback, intPin=nullptr) → void
                                                             // needs an INT pin on the connection or the intPin argument
    rtc.enableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_TIMER);  // Enable sources, (source) → void
                                                             // sets AIE and TIE
    uint8_t status = rtc.pollInterrupt();                    // Poll & clear flags, () → uint8_t
                                                             // clears AF/TF, returns the pre-clear mask
    rtc.disableInterrupt(PCF85063AFull::SOURCE_ALARM | PCF85063AFull::SOURCE_TIMER | PCF85063AFull::SOURCE_MINUTE | PCF85063AFull::SOURCE_HALF_MINUTE | PCF85063AFull::SOURCE_CORRECTION);  // Disable sources, (source) → void
    rtc.offInterrupt();                                      // Unsubscribe, () → void

    rtc.softwareReset();                                     // Software reset, () → void
                                                             // resets ALL registers including the time

    printf("%04u-%02u-%02u %02u:%02u:%02u os_stopped=%d\r\n",
        dt.year, dt.month, dt.day, dt.hour, dt.minute, dt.second, stopped);
    printf("alarm %u:%u:%u timer=%u ram=0x%02X\r\n", alarm.hour, alarm.minute, alarm.second, remaining, ram);
    printf("offset=%d course=%d status=0x%02X\r\n",
        offset, offsetMode == PCF85063AFull::OffsetMode::Course, status);
    while (true) HAL_Delay(1000);
}
