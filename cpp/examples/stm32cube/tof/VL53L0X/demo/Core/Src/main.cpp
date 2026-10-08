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

// Touchless presence gate with multi-rate ranging: a first measurement picks
// the profile (long range in a dark room, default otherwise), then timed
// continuous ranging at 100 ms feeds an out-of-window interrupt — closer than
// 10 cm is an ENTER event, the scene clearing beyond 80 cm a LEAVE event.
// After 20 events or 60 s, it prints statistics over 10 fresh samples, stops
// ranging and recalibrates.

static const int MAX_EVENTS = 20;
static const int MAX_MS = 60000;

static volatile bool pending = false;

static void onEvent(uint8_t) { pending = true; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, VL53L0XMinimal::I2C_ADDRESS);
    VL53L0XFull sensor(connection);                         // Create VL53L0X Full driver, (connection)

    // --- Pick a profile from the ambient light level ---
    // The long-range profile (0.1 MCPS limit, 18/14 PCLK VCSEL periods) reaches
    // ~2 m, but only without IR background; in daylight it mostly adds invalid
    // readings. One single-shot measurement tells us how bright the scene is.
    sensor.distance();                                      // Measure distance, () → uint16_t mm
    VL53L0XFull::Measurement first = sensor.readMeasurement();  // Read result block, () → Measurement
    if (first.ambientRateMcps < 0.5f) {
        sensor.setProfile(VL53L0XFull::Profile::LongRange);  // Apply ranging profile, (profile) → bool
        printf("dark scene (%.2f MCPS ambient): long range profile\r\n", (double)first.ambientRateMcps);
    } else {
        sensor.setProfile(VL53L0XFull::Profile::Default);   // Apply ranging profile, (profile) → bool
        printf("bright scene (%.2f MCPS ambient): default profile\r\n", (double)first.ambientRateMcps);
    }

    // --- Arm the presence gate ---
    // Timed ranging every 100 ms keeps the laser mostly idle. The firmware
    // compares each result with the 100 mm / 800 mm window itself and only
    // raises GPIO1 when a reading falls outside it.
    sensor.setInterruptThresholds(100, 800);                // Set distance thresholds, (lowMm mm, highMm mm) → bool
    sensor.enableInterrupt(VL53L0XFull::SOURCE_OUT_OF_WINDOW);  // Select interrupt source, (source) → bool
    sensor.startContinuous(100);                            // Start continuous ranging, (periodMs=0 ms) → void
    sensor.onInterrupt(onEvent);                            // Subscribe to GPIO1, (callback, intPin=nullptr) → void

    // --- Classify each event ---
    // Without a GPIO1 pin on the connection, poll the status instead. The
    // result block still holds the measurement that triggered the event.
    int events = 0;
    for (int elapsed = 0; events < MAX_EVENTS && elapsed < MAX_MS; elapsed += 50) {
        if (!connection.intPin() && sensor.pollInterrupt()) pending = true;  // Read and clear status, () → uint8_t
        if (pending) {
            pending = false;
            uint16_t d = sensor.readMeasurement().distanceMm;  // Read result block, () → Measurement
            printf("%s %u mm\r\n", d < 100 ? "ENTER" : "LEAVE", (unsigned)d);
            events++;
        }
        HAL_Delay(50);
    }

    // --- Statistics over fresh samples ---
    // Threshold sources hide ordinary samples from dataReady(), so switch back
    // to new-sample-ready before using the blocking continuous reads.
    sensor.offInterrupt();                                  // Unsubscribe, () → void
    sensor.enableInterrupt(VL53L0XFull::SOURCE_NEW_SAMPLE_READY);  // Select interrupt source, (source) → bool
    sensor.pollInterrupt();                                 // Read and clear status, () → uint8_t
    uint32_t sum = 0;
    uint16_t lo = 0xFFFF, hi = 0;
    float rate = 0.0f;
    for (int i = 0; i < 10; i++) {
        uint16_t d = sensor.readContinuous();               // Read next continuous result, () → uint16_t mm
        rate += sensor.readMeasurement().signalRateMcps;    // Read result block, () → Measurement
        sum += d;
        if (d < lo) lo = d;
        if (d > hi) hi = d;
    }
    printf("mean %lu mm, min %u mm, max %u mm, signal %.2f MCPS\r\n",
           (unsigned long)(sum / 10), (unsigned)lo, (unsigned)hi, (double)(rate / 10.0f));

    // --- Shut down and recalibrate ---
    // Reference calibration must run in software standby. Repeat it whenever
    // the sensor's temperature has drifted more than 8 °C.
    sensor.stopContinuous();                                // Stop continuous ranging, () → void
    HAL_Delay(200);
    sensor.recalibrate();                                   // Rerun reference calibration, () → bool
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
