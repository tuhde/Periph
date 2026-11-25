#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "VL53L1X.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, VL53L1XMinimal::I2C_ADDRESS);
    VL53L1XFull sensor(connection);                         // Create VL53L1X Full driver, (connection)
                                                            // runs init: boot poll, ID check, ULD default config

    printf("model 0x%02X\r\n", sensor.modelId());             // Read model ID, () → uint8_t
                                                            // IDENTIFICATION__MODEL_ID, always 0xEA
    printf("module 0x%02X\r\n", sensor.moduleType());         // Read module type, () → uint8_t
                                                            // IDENTIFICATION__MODULE_TYPE, always 0xCC
    printf("revision 0x%02X\r\n", sensor.revisionId());       // Read revision ID, () → uint8_t
                                                            // mask revision, 0x10

    uint16_t d = sensor.distance();                         // Measure distance, () → uint16_t mm
                                                            // single shot; blocks for about one timing budget
    printf("distance %u mm, valid %d\r\n", (unsigned)d, sensor.rangeValid());  // Check last measurement, () → bool
                                                            // mapped range status == 0
    printf("range status %u\r\n", sensor.rangeStatus());      // Read last range status, () → uint8_t
                                                            // 0 = valid, 2 = signal fail, 4 = out of bounds
    VL53L1XFull::Measurement m = sensor.readMeasurement();  // Read result block, () → Measurement
                                                            // distance, status, signal/ambient MCPS, SPAD count
    printf("signal %.2f MCPS, ambient %.2f MCPS, %.1f SPADs\r\n",
           (double)m.signalRateMcps, (double)m.ambientRateMcps, (double)m.effectiveSpadCount);

    bool isLong = sensor.distanceMode() == VL53L1XFull::DistanceMode::Long;  // Read distance mode, () → DistanceMode
                                                            // from PHASECAL_CONFIG__TIMEOUT_MACROP
    printf("mode %s\r\n", isLong ? "long" : "short");
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Short);  // Set distance mode, (mode) → bool
                                                            // ~1.3 m, robust in sunlight; keeps the budget
    printf("budget %lu us\r\n", (unsigned long)sensor.timingBudget());  // Read timing budget, () → uint32_t µs
                                                            // decoded from the range timeout A register
    sensor.setTimingBudget(33000);                          // Set timing budget, (budgetUs µs) → bool
                                                            // ULD table values 15000 (short only) … 500000
    printf("short mode %u mm\r\n", (unsigned)sensor.distance());  // Measure distance, () → uint16_t mm
                                                            // 33 ms single shot
    sensor.setDistanceMode(VL53L1XFull::DistanceMode::Long);  // Set distance mode, (mode) → bool
                                                            // back to up to 4 m in the dark
    sensor.setTimingBudget(100000);                         // Set timing budget, (budgetUs µs) → bool
                                                            // default 100 ms

    sensor.setInterMeasurement(200);                        // Set inter-measurement period, (periodMs ms) → bool
                                                            // must be ≥ the timing budget
    printf("period %lu ms\r\n", (unsigned long)sensor.interMeasurement());  // Read inter-measurement period, () → uint32_t ms
                                                            // oscillator ticks scaled by the PLL calibration
    sensor.startContinuous(200);                            // Start continuous ranging, (periodMs=0 ms) → bool
                                                            // timed mode; 0 = as fast as the budget allows
    for (int i = 0; i < 5; i++) {
        printf("continuous %u mm\r\n", (unsigned)sensor.readContinuous());  // Read next continuous result, () → uint16_t mm
                                                            // waits for data ready, then clears it
    }
    while (!sensor.dataReady()) {                           // Check for a result, () → bool
        HAL_Delay(10);                                       // GPIO1 line asserted
    }
    printf("record %u mm\r\n", (unsigned)sensor.readMeasurement().distanceMm);  // Read result block, () → Measurement
                                                            // non-blocking; clears the interrupt
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // does not wait for a running measurement
    HAL_Delay(250);

    printf("signal limit %.3f MCPS\r\n", (double)sensor.signalRateLimit());  // Read signal-rate limit, () → float MCPS
                                                            // 9.7 fixed point, default 1.0
    sensor.setSignalRateLimit(0.5f);                        // Set signal-rate limit, (limitMcps MCPS) → bool
                                                            // lower = longer range, more noise
    sensor.setSignalRateLimit(1.0f);                        // Set signal-rate limit, (limitMcps MCPS) → bool
                                                            // restore the default
    printf("sigma %u mm\r\n", (unsigned)sensor.sigmaThreshold());  // Read sigma threshold, () → uint16_t mm
                                                            // 14.2 fixed point, default 90
    sensor.setSigmaThreshold(60);                           // Set sigma threshold, (sigmaMm mm) → bool
                                                            // stricter repeatability filter
    sensor.setSigmaThreshold(90);                           // Set sigma threshold, (sigmaMm mm) → bool
                                                            // restore the default

    uint8_t centre = sensor.opticalCenter();                // Read optical-centre SPAD, () → uint8_t
                                                            // factory NVM value for this part's lens
    printf("optical centre %u\r\n", (unsigned)centre);
    sensor.setRoi(8, 8);                                    // Set ROI size, (width SPADs, height SPADs) → bool
                                                            // 4–16 each; narrows the field of view
    sensor.setRoiCenter(centre);                            // Set ROI centre, (spad) → void
                                                            // align the narrow ROI with the lens
    uint8_t w = 0, h = 0;
    sensor.roi(w, h);                                       // Read ROI size, (width&, height&) → void
                                                            // in SPADs
    printf("roi %ux%u centre %u\r\n", (unsigned)w, (unsigned)h, (unsigned)sensor.roiCenter());  // Read ROI centre, () → uint8_t
                                                            // SPAD number
    sensor.setRoi(16, 16);                                  // Set ROI size, (width SPADs, height SPADs) → bool
                                                            // full array; re-centres on SPAD 199

    float original = sensor.offset();                       // Read range offset, () → float mm
                                                            // NVM factory value, 0.25 mm steps
    sensor.setOffset(original - 5.0f);                      // Set range offset, (offsetMm mm) → bool
                                                            // volatile override, −1024.0 to 1023.75 mm
    printf("offset %.2f mm\r\n", (double)sensor.offset());    // Read range offset, () → float mm
                                                            // 13-bit two's complement × 0.25
    sensor.setCrosstalkCompensation(0.01f);                 // Set crosstalk compensation, (rateMcps MCPS) → bool
                                                            // per-SPAD rate, 7.9 kcps register
    printf("crosstalk %.4f MCPS\r\n", (double)sensor.crosstalkCompensation());  // Read crosstalk compensation, () → float MCPS
                                                            // 0 = off
    float calOffset = 0.0f, calXtalk = 0.0f;
    sensor.calibrateOffset(140, calOffset);                 // Calibrate offset, (targetMm mm, offsetMm&) → bool
                                                            // 50 samples against a target at 140 mm; applies it
    sensor.calibrateCrosstalk(600, calXtalk);               // Calibrate crosstalk, (targetMm mm, rateMcps&) → bool
                                                            // 50 samples against a target at 600 mm; applies it
    printf("calibrated offset %.2f mm, crosstalk %.4f MCPS\r\n", (double)calOffset, (double)calXtalk);
    sensor.setOffset(original);                             // Set range offset, (offsetMm mm) → bool
                                                            // restore the factory value
    sensor.setCrosstalkCompensation(0.0f);                  // Set crosstalk compensation, (rateMcps MCPS) → bool
                                                            // compensation off

    sensor.recalibrate();                                   // Run temperature update, () → bool
                                                            // full VHV; after a > 8 °C change, not while ranging

    sensor.setInterruptThresholds(100, 800);                // Set distance thresholds, (lowMm mm, highMm mm) → bool
                                                            // 1 mm resolution
    uint16_t lo = 0, hi = 0;
    sensor.interruptThresholds(lo, hi);                     // Read distance thresholds, (lowMm&, highMm&) → void
                                                            // (low, high) in mm
    printf("thresholds %u..%u mm\r\n", (unsigned)lo, (unsigned)hi);
    sensor.enableInterrupt(VL53L1XFull::SOURCE_IN_WINDOW);  // Select interrupt source, (source) → bool
                                                            // fires while 100 mm ≤ range ≤ 800 mm
    sensor.disableInterrupt(VL53L1XFull::SOURCE_IN_WINDOW);  // Disable interrupt source, (source) → void
                                                            // reverts to new-sample-ready (no disabled state)
    sensor.enableInterrupt(VL53L1XFull::SOURCE_NEW_SAMPLE_READY);  // Select interrupt source, (source) → bool
                                                            // the default data-ready source

    sensor.onInterrupt(onSample);                           // Subscribe to GPIO1, (callback, intPin=nullptr) → void
                                                            // interrupt is cleared before the callback
    sensor.startContinuous(200);                            // Start continuous ranging, (periodMs=0 ms) → bool
                                                            // timed mode feeds the subscription
    HAL_Delay(1000);
    if (pending) printf("interrupt, source %u\r\n", pendingStatus);
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
                                                            // no more samples
    sensor.offInterrupt();                                  // Unsubscribe, () → void
                                                            // detaches the pin handler
    printf("pending %u\r\n", sensor.pollInterrupt());         // Read and clear interrupt, () → uint8_t
                                                            // active SOURCE_* value, 0 = nothing pending

    sensor.setAddress(0x30);                                // Change I²C address, (address) → bool
                                                            // volatile; this driver instance is now unusable
    I2CConnectionSTM32Cube movedConnection(&hi2c1, 0x30);
    VL53L1XFull moved(movedConnection);                     // Create VL53L1X Full driver, (connection)
                                                            // re-init at the new address is safe
    printf("at 0x30 %u mm\r\n", (unsigned)moved.distance());  // Measure distance, () → uint16_t mm
                                                            // same sensor, new address
    moved.setAddress(VL53L1XMinimal::I2C_ADDRESS);          // Change I²C address, (address) → bool
                                                            // back to the power-on 0x29
    while (true) HAL_Delay(1000);
}
