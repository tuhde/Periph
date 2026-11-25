#include <stdio.h>
#include <math.h>
#include <stdint.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "FXOS8700.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

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

static void sleepMs(uint32_t ms) { HAL_Delay(ms); }
static void printVec(const char* label, float x, float y, float z, const char* unit) {
    printf("%s x=%.3f y=%.3f z=%.3f %s\r\n", label, x, y, z, unit);
}
static void printNum(const char* label, float v, const char* unit) { printf("%s %.2f %s\r\n", label, v, unit); }
static void printInt(const char* label, long v) { printf("%s %ld\r\n", label, v); }
static void printText(const char* s) { printf("%s\r\n", s); }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    HAL_Delay(100);

    I2CConnectionSTM32Cube connection(&hi2c1, 0x1E);
    FXOS8700Full compass(connection);                       // Create FXOS8700 Full driver, (connection)

    // --- Configure hybrid mode ---
    // ±4 g keeps tilt maths precise while tolerating handling; magnetometer
    // oversampling ratio 7 gives the lowest noise at this data rate.
    compass.setRange(4);                                    // Set accelerometer range, (rangeG 2|4|8) → bool
    compass.setMagOversampling(7);                          // Set magnetometer oversampling, (ratio 0..7, sleepRatio=-1) → bool

    // --- Check the accelerometer ---
    // The electrostatic self-test must move every axis by the datasheet minimum.
    int16_t dx, dy, dz;
    bool ok = compass.selfTest(dx, dy, dz);                 // Run accelerometer self-test, (dx, dy, dz) → bool
    printText(ok ? "self-test OK" : "self-test FAILED");

    // --- Calibrate the hard-iron offsets ---
    // Auto-calibration writes the midpoint of the tracked min/max to the
    // offset registers every cycle; rotate the board through all
    // orientations meanwhile.
    compass.setMagAutoCalibration(true);                    // Hardware hard-iron calibration, (enabled) → void
    printText("Rotate the board in all directions for 15 s ...");
    for (int s = 0; s < 15; s++) {
        sleepMs(1000);
        float mn[3], mxv[3];
        compass.readMagMinMax(mn, mxv);                     // Read tracked min / max, (minUt[3], maxUt[3]) → µT
        printVec("span", mxv[0] - mn[0], mxv[1] - mn[1], mxv[2] - mn[2], "uT");
    }
    compass.setMagAutoCalibration(false);                   // Hardware hard-iron calibration, (enabled) → void
    float ox, oy, oz;
    compass.getMagOffset(ox, oy, oz);                       // Get hard-iron offsets, (xUt, yUt, zUt) → µT, µT, µT
    printVec("offsets", ox, oy, oz, "uT");

    // --- Arm orientation and motion detection ---
    // Orientation changes and motion are routed to INT1; Auto-SLEEP drops the
    // chip to a low rate after 10 s without motion, and motion wakes it again.
    compass.setOrientation(true, 100.0f);                   // Configure orientation detection, (enabled=true, debounceMs=0, thresholdDeg=45, hysteresis=4, zLock=4, backFront=2) → bool
    compass.setFreefallMotion(true, 7, 0.3f, 60.0f, true, false); // Configure freefall / motion, (motion, axes=7, thresholdG=0.3, debounceMs=0, latch=false, debounceClear=false) → void
    compass.setAutoSleep(true, 12.5f, 10.0f, FXOS8700Full::SOURCE_FF_MT); // Configure Auto-SLEEP, (enabled, sleepRateHz=50, timeoutS=10, wakeSources=0) → bool
    compass.enableInterrupt(FXOS8700Full::SOURCE_ORIENTATION | FXOS8700Full::SOURCE_FF_MT, 1); // Enable and route interrupt, (source mask, pin 1|2) → bool

    // --- Print the heading ---
    // Every 250 ms the tilt-compensated heading is shown with the board's
    // pitch and roll; the temperature is printed once per second.
    static const char* const ORIENTATIONS[4] = { "PORTRAIT UP", "PORTRAIT DOWN", "LANDSCAPE RIGHT", "LANDSCAPE LEFT" };
    static const char* const DIRECTIONS[16] = { "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                                "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW" };
    for (int tick = 0; tick < 240; tick++) {
        float heading = compass.heading(3.0f);              // Compute tilt-compensated heading, (declinationDeg=0) → float degrees
        float ax, ay, az, mx, my, mz;
        compass.readHybrid(ax, ay, az, mx, my, mz);         // Read both sensors, (ax..az, mx..mz) → bool
        float roll = atan2f(ay, az) * 180.0f / 3.14159265f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.14159265f;
        printText(DIRECTIONS[(int)((heading + 11.25f) / 22.5f) % 16]);
        printNum("heading", heading, "deg");
        printNum("pitch", pitch, "deg");
        printNum("roll", roll, "deg");
        uint8_t status = compass.pollInterrupt();           // Poll and clear interrupt sources, () → uint8_t
        if (status & FXOS8700Full::SOURCE_ORIENTATION) {
            bool changed, lockout, back;
            uint8_t pl;
            compass.readOrientation(changed, lockout, pl, back); // Read orientation, (changed, lockout, portraitLandscape, back) → void
            printText(ORIENTATIONS[pl]);
        }
        if (status & FXOS8700Full::SOURCE_FF_MT) {
            printInt("MOTION, system mode", compass.readSystemMode()); // Read SYSMOD, () → uint8_t 0 standby | 1 wake | 2 sleep
        }
        if (tick % 4 == 0) {
            float temperature;
            compass.readTemperature(temperature);           // Read die temperature, (celsius) → bool
            printNum("temperature", temperature, "C");
        }
        sleepMs(250);
    }
    return 0;
}
