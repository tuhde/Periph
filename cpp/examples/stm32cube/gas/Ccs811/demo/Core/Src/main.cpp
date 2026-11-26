#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Ccs811.h"

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

static const char* classify(uint16_t eco2) {
    if (eco2 < 1000) return "good";
    if (eco2 <= 1500) return "fair";
    return "poor";
}

static void on_int(uint8_t status) {
    printf("callback status 0x%02X\r\n", (unsigned)(status));
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x5A);
    CCS811Full sensor(connection);                                 // Create CCS811 driver, (connection) → void
    // --- Identity ---
    // HW_ID 0x81 identifies the CCS81x family; the firmware versions tell which
    // algorithm revision computes eCO2 and TVOC.
    sensor.init();                                                 // Check HW_ID, start application, mode 1, () → bool
    uint8_t hw_id = 0, hw_version = 0, st = 0, err = 0;
    sensor.hardware_version(hw_id, hw_version);                    // Hardware ID and version, (hw_id&, hw_version&) → bool
    CCS811Version boot = {0, 0, 0}, app = {0, 0, 0};
    sensor.firmware_version(boot, app);                            // Firmware versions, (boot&, app&) → bool
    sensor.status(st);                                             // STATUS, (status&) → bool
    sensor.error(err);                                             // ERROR_ID, (error_id&) → bool
    printf("hw 0x%02X/0x%02X boot %u.%u.%u app %u.%u.%u status 0x%02X error 0x%02X\r\n", (unsigned)(hw_id), (unsigned)(hw_version), (unsigned)(boot.major), (unsigned)(boot.minor), (unsigned)(boot.trivial), (unsigned)(app.major), (unsigned)(app.minor), (unsigned)(app.trivial), (unsigned)(st), (unsigned)(err));

    // --- Air-quality monitor: 30 s at 1 Hz ---
    // Mode 1 delivers a sample every second. Values settle after about 20 min of
    // warm-up; a brand-new sensor needs a 48 h burn-in.
    uint16_t eco2 = 0, tvoc = 0;
    for (int i = 0; i < 30; i++) {
        if (sensor.read(eco2, tvoc)) {                             // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
            printf("%u s eCO2 %u ppm TVOC %u ppb %s\r\n", (unsigned)(i), (unsigned)(eco2), (unsigned)(tvoc), classify(eco2));
        }
    }
    sensor.read_eco2(eco2);                                        // eCO2 only, (eco2_ppm&) → bool
    sensor.read_tvoc(tvoc);                                        // TVOC only, (tvoc_ppb&) → bool
    printf("eCO2 %u ppm TVOC %u ppb\r\n", (unsigned)(eco2), (unsigned)(tvoc));

    // --- Environment compensation ---
    // Feeding the real temperature and humidity (e.g. from a BME280) corrects the
    // algorithm; without it the chip assumes 25 C and 50 %RH.
    sensor.set_environment(40.0f, 23.0f);                          // Compensation data, (humidity_rh, temperature_c) → bool
    uint16_t v_ref = 0, v_ntc = 0;
    sensor.read_ntc(v_ref, v_ntc);                                 // NTC voltages, (v_ref_mv&, v_ntc_mv&) → bool
    float r_ntc = 0;
    if (sensor.ntc_resistance(10000.0f, r_ntc)) {                  // NTC resistance, (r_ref_ohm, r_ntc_ohm&) → bool
        printf("NTC %.0f ohm\r\n", (double)(r_ntc));
    } else {
        printf("no NTC circuit fitted\r\n");
    }

    // --- Threshold interrupt: only report zone changes ---
    // With INT_THRESH the chip raises nINT only when eCO2 crosses 1000 or
    // 2000 ppm by more than the hysteresis. Polling STATUS here shows each sample.
    sensor.set_thresholds(1000, 2000, 50);                         // Zone boundaries, (low_medium_ppm, medium_high_ppm, hysteresis_ppm) → bool
    sensor.enable_interrupt(CCS811Full::SOURCE_THRESHOLD);         // Threshold interrupt, (source) → bool
    for (int i = 0; i < 5; i++) {
        while (!(sensor.poll_interrupt() & CCS811Full::STATUS_DATA_READY)) { // STATUS, () → uint8_t
            HAL_Delay(100);
        }
        sensor.read(eco2, tvoc);                                   // Reading releases nINT, (eco2_ppm&, tvoc_ppb&) → bool
        printf("event eCO2 %u ppm %s\r\n", (unsigned)(eco2), classify(eco2));
    }
    sensor.disable_interrupt(CCS811Full::SOURCE_THRESHOLD);        // Threshold interrupt off, (source) → bool

    // --- Data-ready callback ---
    // The callback gets the STATUS byte; it needs an INT pin on the connection.
    sensor.enable_interrupt(CCS811Full::SOURCE_DATA_READY);        // Data-ready interrupt, (source) → bool
    sensor.on_interrupt(on_int);                                   // Subscribe, (callback, intPin = nullptr) → void
    HAL_Delay(3000);
    sensor.off_interrupt();                                        // Unsubscribe, () → void
    sensor.disable_interrupt(CCS811Full::SOURCE_DATA_READY);       // Data-ready interrupt off, (source) → bool

    // --- Raw sensor data ---
    // The MOx sensor's current and voltage give its resistance; the algorithm
    // derives eCO2 and TVOC from it.
    uint8_t current = 0;
    float voltage = 0;
    sensor.read_raw(current, voltage);                             // Raw data, (current_ua&, voltage_v&) → bool
    float r_sensor = current ? voltage / (current * 1e-6f) : 0.0f;
    printf("raw %u uA %.3f V %.0f ohm\r\n", (unsigned)(current), (double)(voltage), (double)(r_sensor));

    // --- Slower sampling: mode 2 (every 10 s) ---
    // Pulse heating saves power; pass through idle when changing modes.
    sensor.set_mode(0);                                            // Idle, (mode) → bool
    sensor.set_mode(2);                                            // Every 10 s, (mode) → bool
    for (int i = 0; i < 2; i++) {
        sensor.read(eco2, tvoc);                                   // Next sample, (eco2_ppm&, tvoc_ppb&) → bool
        bool ready = false;
        sensor.data_ready(ready);                                  // Ready flag, (ready&) → bool
        printf("mode 2 eCO2 %u ppm TVOC %u ppb ready %u\r\n", (unsigned)(eco2), (unsigned)(tvoc), (unsigned)(ready));
    }
    sensor.set_mode(0);                                            // Idle, (mode) → bool
    sensor.set_mode(1);                                            // Back to every second, (mode) → bool

    // --- Baseline ---
    // Save the baseline daily and restore it after a power cycle, but only after
    // about 20 min of warm-up; otherwise the chip relearns it from scratch.
    uint16_t baseline = 0;
    sensor.get_baseline(baseline);                                 // Baseline, (baseline&) → bool
    printf("baseline 0x%04X\r\n", (unsigned)(baseline));
    sensor.set_baseline(baseline);                                 // Restore baseline, (baseline) → bool

    // --- Wrap up ---
    // A soft reset puts the chip into boot mode; the driver starts the
    // application again in mode 1.
    sensor.soft_reset();                                           // Software reset, () → bool
    sensor.status(st);                                             // STATUS, (status&) → bool
    printf("status after reset 0x%02X\r\n", (unsigned)(st));
    while (1) {}
}
