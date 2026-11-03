#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LSM303.h"

static I2C_HandleTypeDef hi2c1;
static UART_HandleTypeDef huart2;

extern "C" void SysTick_Handler(void) { HAL_IncTick(); }

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
    gpioInit.Pin       = GPIO_PIN_2 | GPIO_PIN_3;
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
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();

    I2CConnectionSTM32Cube accel_connection(&hi2c1, LSM303Minimal::I2C_ADDRESS_ACCEL);
    I2CConnectionSTM32Cube mag_connection(&hi2c1, LSM303Minimal::I2C_ADDRESS_MAG);
    LSM303Full accel(accel_connection, mag_connection);  // Create LSM303 driver, (accel_connection, mag_connection)

    // --- Configure the sensor for a responsive compass ---
    // 50 Hz gives a fresh sample every 20 ms without flooding the FIFO, and
    // the high-resolution mode keeps 1 mg/LSB so the tilt compensation stays
    // precise.
    accel.setDataRate(50);                      // Set output data rate, (rate_hz=50) → bool
    accel.setMode(LSM303Full::MODE_HIGH_RESOLUTION);  // Set operating mode, (mode=MODE_HIGH_RESOLUTION=2) → bool
    accel.setMagRange(130);                       // Set magnetometer range, (range_ut=130) → bool
    accel.setMagDataRate(15.0f);                  // Set magnetometer data rate, (rate_hz=15) → bool

    // --- Arm the gestures ---
    // A double tap on the Z axis and a free-fall event both end up on INT1.
    // The latch keeps the line asserted until the source register is read, so
    // a short tap is never missed between two polling rounds.
    accel.setClick(0, 0x04, 0.8f, 20.0f, 40.0f, 200.0f);  // Configure click detector, (single=0, double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200) → bool
    accel.setFreeFall(1, 0.35f, 60.0f);         // Arm free-fall, (generator=1, threshold_g=0.35, duration_ms=30) → bool
    accel.setLatch(1, true);                    // Latch the interrupt, (generator=1, enabled=true) → bool
    accel.enableInterrupt(LSM303Full::SOURCE_CLICK, 1);  // Route an interrupt, (source=SOURCE_CLICK=0x20, pin=1) → bool
    accel.enableInterrupt(LSM303Full::SOURCE_IA1, 1);  // Route an interrupt, (source=SOURCE_IA1=0x08, pin=1) → bool

    // --- Collect the samples in the FIFO ---
    // Stream mode keeps the newest 32 sample sets; the watermark interrupts as
    // soon as 16 of them have piled up.
    accel.setFifo(LSM303Full::FIFO_STREAM, 16);  // Configure FIFO, (mode=FIFO_STREAM=2, watermark=0, trigger_int2=false) → bool

    float samples[3 * 8];
    for (int round = 0; round < 60; round++) {
        // --- Average the samples the FIFO collected ---
        // Averaging over the whole burst cancels most of the vibration noise a
        // handheld compass would otherwise show.
        float ax = 0, ay = 0, az = 0;
        uint8_t count = accel.readFifo(samples, 8);  // Read FIFO, (out, max_count=8) → byte
        if (count > 0) {
            for (uint8_t i = 0; i < count; i++) {
                ax += samples[i * 3];
                ay += samples[i * 3 + 1];
                az += samples[i * 3 + 2];
            }
            ax /= count;
            ay /= count;
            az /= count;
        } else {
            accel.readAcceleration(ax, ay, az);  // Read acceleration, (x, y, z) → g, g, g
        }

        // --- Compute the heading and the tilt ---
        float heading = accel.heading();         // Read the compass heading, (tilt_compensate=true) → float °
                                                 // tilt-compensated with the accelerometer
        float roll = (float)atan2((double)ay, (double)az) * 180.0 / M_PI;
        float pitch = (float)atan2((double)-ax,
                                    sqrt((double)ay * ay + (double)az * az)) * 180.0 / M_PI;
        float mx, my, mz;
        accel.readMagnetic(mx, my, mz);            // Read magnetic field, (x, y, z) → uT, uT, uT
        float magnitude = (float)sqrt((double)mx * mx + (double)my * my + (double)mz * mz);
        float temperature = accel.readTemperatureDelta();  // Read relative temperature, () → float °C
        static const char* directions[16] = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                                              "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int index = (int)((heading + 11.25f) / 22.5f) % 16;
        if (index < 0) index += 16;

        printf("heading %6.1f deg (%3s)  pitch %6.1f deg  roll %6.1f deg  "
               "field %6.1f uT  temp %5.1f C\r\n",
               (double)heading, directions[index], (double)pitch, (double)roll,
               (double)magnitude, (double)temperature);

        // --- Report the gestures ---
        uint8_t status = accel.pollInterrupt();  // Read interrupt status, () → byte
                                                 // reading the source registers clears the latched flags
        if (status & LSM303Full::SOURCE_CLICK) {
            printf("DOUBLE TAP\r\n");
        }
        if (status & LSM303Full::SOURCE_IA1) {
            printf("FREE FALL\r\n");
        }

        HAL_Delay(1000);
    }
}
