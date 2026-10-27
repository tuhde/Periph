#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

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

static const char* const COMPASS16[16] = {
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
};

// 16-point compass name for a heading in degrees (0–360).
static const char* compassName(float deg) {
    int idx = static_cast<int>((deg + 11.25f) / 22.5f) % 16;
    if (idx < 0) idx += 16;
    return COMPASS16[idx];
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube accelConn(&hi2c1, 0x19);        // Accelerometer connection at 0x19
    I2CConnectionSTM32Cube magConn(&hi2c1, 0x1E);          // Magnetometer connection at 0x1E

    LSM303Full chip(accelConn, magConn);                    // Create LSM303 full driver, (accel_conn, mag_conn) → sets ±2 g, 100 Hz, ±130 µT, 15 Hz

    // --- Configure for a tilt-compensated compass with tap and free-fall detection ---
    // ±2 g and 50 Hz keep the rate low enough for a steady average; high-resolution mode
    // gives 1 mg/LSB. The magnetometer runs at 15 Hz within ±1.3 gauss.
    chip.setRange(2);                                       // Set accel full scale, (range_g=2) → bool
    chip.setMode(2);                                        // Set accel mode, (mode=2) → bool
    chip.setDataRate(50);                                   // Set accel output rate, (rate_hz=50) → bool
    chip.setMagRange(130);                                  // Set mag full scale, (range_ut=130) → bool
    chip.setMagDataRate(15.0f);                             // Set mag output rate, (rate_hz=15) → bool

    // --- Arm the double-tap and free-fall detectors, both routed to INT1 ---
    // A double tap on Z (0x04) at 0.8 g with a 20 ms limit, 40 ms latency and 200 ms window.
    chip.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);     // Configure click, (single=0, double=0x04, threshold_g=0.8, ...) → void
    // Free-fall: all three axes below 0.35 g for 60 ms, latched until the source is read.
    chip.setFreeFall(1, 0.35f, 60.0f);                      // Free-fall on generator 1, (generator=1, threshold_g=0.35, duration_ms=60) → bool
    chip.setLatch(1, true);                                 // Latch generator 1, (generator=1, enabled=true) → bool
    chip.enableInterrupt(LSM303Source::CLICK, 1);           // Route click to INT1, (source, pin=1) → bool
    chip.enableInterrupt(LSM303Source::IA1, 1);             // Route IA1 to INT1, (source, pin=1) → bool

    // --- Stream into the FIFO, 16-sample watermark ---
    chip.setFifo(2, 16, false);                             // Configure FIFO, (mode=2, watermark=16, trigger_int2=false) → bool

    const int SECONDS = 60;
    for (int s = 0; s < SECONDS; s++) {
        // --- Once a second: drain the FIFO, average, and read the magnetometer ---
        // Averaging the FIFO removes most per-sample noise before the tilt is computed.
        HAL_Delay(1000);
        float xs[16], ys[16], zs[16];
        uint8_t n = chip.readFifo(xs, ys, zs, 16);          // Drain FIFO, (x, y, z, count=16) → uint8
        float ax = 0.0f, ay = 0.0f, az = 0.0f;
        if (n > 0) {
            for (uint8_t i = 0; i < n; i++) { ax += xs[i]; ay += ys[i]; az += zs[i]; }
            ax /= n; ay /= n; az /= n;
        } else {
            chip.readAcceleration(ax, ay, az);              // Read acceleration, (x, y, z) → g, g, g
        }

        float mx, my, mz;
        chip.readMagnetic(mx, my, mz);                      // Read magnetic field, (x, y, z) → µT, µT, µT
        float hdg = chip.heading(true);                     // Tilt-compensated heading, (tilt_compensate=true) → deg
        float dT = chip.readTemperatureDelta();             // Read temperature, () → float °C

        // Pitch and roll from the averaged gravity vector (degrees, display only).
        float roll  = atan2f(ay, az) * 180.0f / 3.14159265f;
        float pitch = atan2f(-ax, sqrtf(ay * ay + az * az)) * 180.0f / 3.14159265f;
        float fieldUt = sqrtf(mx * mx + my * my + mz * mz);

        printf("heading=%.1f deg %s  pitch=%.1f roll=%.1f  |B|=%.1f uT  temp_delta=%.2f C  samples=%u\r\n",
               (double)hdg, compassName(hdg), (double)pitch, (double)roll,
               (double)fieldUt, (double)dT, (unsigned)n);

        // --- Poll for events and report them ---
        // Reading the source registers clears the latched flags, so each event prints once.
        uint8_t status = chip.pollInterrupt();              // Poll interrupt sources, () → uint8
        if (status & LSM303Source::CLICK) printf("DOUBLE TAP\r\n");
        if (status & LSM303Source::IA1)   printf("FREE FALL\r\n");
    }

    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
