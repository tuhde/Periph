#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "VL53L0X.h"

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

static void onSample(uint8_t status) {
    pendingStatus = status;
    pending = true;
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, VL53L0XMinimal::I2C_ADDRESS);
    VL53L0XFull sensor(connection);                         // Create VL53L0X Full driver, (connection)
                                                            // runs init: ID check, tuning, SPADs, VHV + phase calibration

    printf("model 0x%02X\r\n", sensor.modelId());             // Read model ID, () → uint8_t
                                                            // IDENTIFICATION_MODEL_ID, always 0xEE
    printf("revision 0x%02X\r\n", sensor.revisionId());       // Read revision ID, () → uint8_t
                                                            // IDENTIFICATION_REVISION_ID, 0x10 on current silicon

    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
                                                            // single shot; blocks for about one timing budget
    printf("distance %u mm, valid %d\r\n", (unsigned)d, sensor.rangeValid());  // Check last measurement, () → bool
                                                            // device range status == 11 (range complete)
    printf("range status %u\r\n", sensor.rangeStatus());      // Read last range status, () → uint8_t 0–15
                                                            // 11 = valid, 4 = no target
    VL53L0XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
                                                            // distance, status, signal/ambient MCPS, SPAD count
    printf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs\r\n",
           (double)m.signalRateMcps, (double)m.ambientRateMcps, (double)m.effectiveSpadCount);

    sensor.startContinuous();                               // Start continuous ranging, (periodMs=0 ms) → void
                                                            // 0 = back-to-back measurements
    for (int i = 0; i < 5; i++) {
        printf("continuous %u mm\r\n", (unsigned)sensor.readContinuous());  // Read next continuous result, () → uint16_t mm
                                                            // waits for a fresh data-ready, then clears it
    }
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // does not wait for a running measurement

    sensor.startContinuous(100);                            // Start continuous ranging, (periodMs=0 ms) → void
                                                            // timed mode: one measurement every 100 ms
    while (!sensor.dataReady()) {                           // Check for a result, () → bool
        HAL_Delay(10);                                       // RESULT_INTERRUPT_STATUS bits 2:0 non-zero
    }
    printf("timed %u mm\r\n", (unsigned)sensor.readMeasurement().distanceMm);  // Read result block, () → Measurement
                                                            // non-blocking; clears the interrupt
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // back to software standby

    printf("budget %lu us\r\n", (unsigned long)sensor.timingBudget());  // Read timing budget, () → uint32_t µs
                                                            // computed from the sequence-step timeouts
    sensor.setTimingBudget(50000);                          // Set timing budget, (budgetUs µs) → bool
                                                            // longer budget = lower noise, ≥ 20000 µs
    printf("signal limit %.3f MCPS\r\n", (double)sensor.signalRateLimit());  // Read signal-rate limit, () → float MCPS
                                                            // 9.7 fixed point
    sensor.setSignalRateLimit(0.1f);                        // Set signal-rate limit, (limitMcps MCPS) → bool
                                                            // lower = longer range, more noise
    sensor.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange, 18);    // Set VCSEL period, (type, pclks) → bool
                                                            // pre-range 12/14/16/18; redoes phase calibration
    sensor.setVcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange, 14);  // Set VCSEL period, (type, pclks) → bool
                                                            // final-range 8/10/12/14
    printf("vcsel %u/%u PCLKs\r\n",
           sensor.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::PreRange),     // Read VCSEL period, (type) → uint8_t PCLKs
           sensor.vcselPulsePeriod(VL53L0XFull::VcselPeriodType::FinalRange));  // (reg + 1) × 2
    sensor.setProfile(VL53L0XFull::Profile::Default);       // Apply ranging profile, (profile) → bool
                                                            // 0.25 MCPS, 14/10 PCLKs, 33 ms

    float original = sensor.offset();                       // Read range offset, () → float mm
                                                            // NVM factory value, 0.25 mm steps
    sensor.setOffset(original - 5.0f);                      // Set range offset, (offsetMm mm) → bool
                                                            // volatile override, −512.0 to 511.75 mm
    printf("offset %.2f mm\r\n", (double)sensor.offset());    // Read range offset, () → float mm
                                                            // 12-bit two's complement × 0.25
    sensor.setOffset(original);                             // Set range offset, (offsetMm mm) → bool
                                                            // restore the factory value
    sensor.setCrosstalkCompensation(0.0f);                  // Set crosstalk compensation, (rateMcps MCPS) → bool
                                                            // 0 = compensation off

    sensor.recalibrate();                                   // Rerun reference calibration, () → bool
                                                            // VHV + phase; needed after a > 8 °C change

    sensor.setInterruptThresholds(100, 800);                // Set distance thresholds, (lowMm mm, highMm mm) → bool
                                                            // 2 mm resolution
    uint16_t lo = 0, hi = 0;
    sensor.interruptThresholds(lo, hi);                     // Read distance thresholds, (lowMm&, highMm&) → void
                                                            // (low, high) in mm
    printf("thresholds %u..%u mm\r\n", (unsigned)lo, (unsigned)hi);
    sensor.enableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);   // Select interrupt source, (source) → bool
                                                            // replaces the active source (mutually exclusive)
    sensor.disableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);  // Disable interrupt source, (source) → void
                                                            // only if it is the active one
    sensor.enableInterrupt(VL53L0XFull::SOURCE_NEW_SAMPLE_READY);  // Select interrupt source, (source) → bool
                                                            // back to the default data-ready source

    sensor.onInterrupt(onSample);                           // Subscribe to GPIO1, (callback, intPin=nullptr) → void
                                                            // status is read and cleared before the callback
    sensor.startContinuous(200);                            // Start continuous ranging, (periodMs=0 ms) → void
                                                            // timed mode feeds the subscription
    HAL_Delay(1000);
    if (pending) printf("interrupt, source %u\r\n", pendingStatus);
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // no more samples
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler
    printf("pending %u\r\n", sensor.pollInterrupt());         // Read and clear status, () → uint8_t
                                                            // SOURCE_* value that fired, 0 = nothing pending

    sensor.setAddress(0x30);                                // Change I²C address, (address) → bool
                                                            // volatile; this driver instance is now unusable
    I2CConnectionSTM32Cube movedConnection(&hi2c1, 0x30);
    VL53L0XFull moved(movedConnection);                     // Create VL53L0X Full driver, (connection)
                                                            // re-init at the new address is safe
    printf("at 0x30 %u mm\r\n", (unsigned)moved.distance());  // Measure distance, () → uint16_t mm
                                                            // same sensor, new address
    moved.setAddress(VL53L0XMinimal::I2C_ADDRESS);          // Change I²C address, (address) → bool
                                                            // back to the power-on 0x29
    while (true) HAL_Delay(1000);
}
