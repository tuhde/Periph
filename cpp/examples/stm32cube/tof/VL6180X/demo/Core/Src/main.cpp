#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "VL6180X.h"

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, VL6180XMinimal::I2C_ADDRESS, nullptr, nullptr, 2);
    VL6180XFull sensor(connection);                         // Create VL6180X Full driver, (connection)
    sensor.init();                                          // Initialize, () → bool

    // --- Identification ---
    uint8_t major = 0, minor = 0;
    sensor.revision(major, minor);                          // Model revision, (uint8_t& major, uint8_t& minor) → void
    printf("model 0x%02X revision %u.%u\r\n", (unsigned)sensor.model_id(), (unsigned)major, (unsigned)minor); // Model ID, () → uint8_t

    // --- Single-shot ranging at 5 Hz for 10 s ---
    uint8_t mm = 0, err = 0;
    for (int i = 0; i < 50; i++) {
        if (sensor.read_range(mm)) printf("%u mm\r\n", (unsigned)mm); // Single-shot range, (uint8_t& mm) → bool
        HAL_Delay(200);
    }

    // --- Continuous ranging at 50 ms: move a hand towards the sensor ---
    sensor.start_continuous_range(50);                      // Start continuous ranging, (uint16_t period_ms) → bool
    for (int i = 0; i < 40; i++) {
        bool ok = sensor.read_range_continuous(mm);         // Wait for next sample, (uint8_t& mm) → bool
        printf("%u mm (%s)\r\n", (unsigned)mm, ok ? "valid" : "error");
    }
    sensor.stop_continuous_range();                         // Stop continuous ranging, () → void
    HAL_Delay(200);

    // --- Out-of-window interrupt for 30-80 mm ---
    sensor.set_range_thresholds(30, 80);                    // Range thresholds, (uint8_t low, uint8_t high) → bool
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_OUT_OF_WINDOW); // Enable an interrupt, (uint8_t source, uint8_t mode) → bool
    sensor.start_continuous_range(50);
    for (int i = 0; i < 50; i++) {
        if (sensor.poll_interrupt() & VL6180XFull::SOURCE_RANGE) printf("range out of window\r\n"); // Read and clear, () → uint8_t
        HAL_Delay(100);
    }
    sensor.stop_continuous_range();
    HAL_Delay(200);
    sensor.enable_interrupt(VL6180XFull::SOURCE_RANGE, VL6180XFull::MODE_NEW_SAMPLE_READY);

    // --- Light meter at gain 1 and 20 ---
    static const uint8_t gains[2] = { 6, 0 };
    for (int i = 0; i < 2; i++) {
        sensor.set_als_gain(gains[i]);                      // ALS gain code, (uint8_t code) → bool
        float lux = sensor.read_lux();                      // Single-shot ALS in lux, () → float lux
        printf("gain code %u: %d.%02d lux\r\n", (unsigned)gains[i], (int)lux, (int)(lux * 100) % 100);
    }
    sensor.set_als_gain(6);

    // --- Interleaved ALS + range for 5 s ---
    sensor.set_interleaved(true);                           // Interleaved ALS + range, (bool on) → void
    sensor.start_continuous_als(100);                       // Start continuous ALS, (uint16_t period_ms) → bool
    HAL_Delay(5000);
    sensor.stop_continuous_als();                           // Stop continuous ALS, () → void
    sensor.set_interleaved(false);

    // --- Calibration and diagnostics ---
    sensor.set_range_offset(0);                             // Part-to-part offset, (int8_t mm) → void
    sensor.set_crosstalk_rate(0.0f);                        // Crosstalk rate, (float rate_mcps) → bool
    sensor.set_als_integration_time(100);                   // ALS integration time, (uint16_t ms) → bool
    sensor.set_als_scaler(1);                               // ALS result scaler, (uint8_t scaler) → bool
    sensor.recalibrate_vhv();                               // Recalibrate VHV, () → bool
    uint16_t raw = 0;
    sensor.read_als_raw(raw);                               // Single-shot ALS count, (uint16_t& raw) → bool
    sensor.read_range_status(mm, err);                      // Range and error code, (uint8_t& mm, uint8_t& err) → bool
    printf("als raw %u, last range %u mm (error %u), offset %d mm\r\n", (unsigned)raw, (unsigned)mm, (unsigned)err, (int)sensor.get_range_offset()); // Read the offset, () → int8_t mm
    uint16_t hist[8];
    sensor.read_history(hist);                              // History buffer, (uint16_t out[8]) → void

    // --- Change the device address and back (volatile) ---
    sensor.set_address(0x30);                               // Change address, (uint8_t address) → bool
    I2CConnectionSTM32Cube moved(&hi2c1, 0x30, nullptr, nullptr, 2);
    VL6180XFull sensorMoved(moved);
    sensorMoved.init();
    printf("on 0x30: model 0x%02X\r\n", (unsigned)sensorMoved.model_id());// Model ID at the new address, () → uint8_t
    sensorMoved.set_address(0x29);                          // Back to the power-on 0x29
    while (1) { HAL_Delay(1000); }
}
