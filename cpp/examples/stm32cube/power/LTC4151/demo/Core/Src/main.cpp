#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "LTC4151.h"

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
    I2CConnectionSTM32Cube connection(&hi2c1, 0x6A);
    LTC4151Full ltc(connection, 0.02f);                                 // Create LTC4151 driver, (connection, r_sense=0.02 Ω) → void

    // --- High-side supply monitor ---
    // Continuous scan: print every channel once per second for 20 s.
    for (int n = 0; n < 20; n++) {
        LTC4151Full::Readings r;
        ltc.read_all(r);                                                // Consistent read of all channels, (out Readings) → void
        printf("%.2f V  %.2f mV  %.4f A  %.3f W  adin %.3f V\r\n", r.vin, r.sense_v * 1000.0f, r.current, r.power, r.adin);
        HAL_Delay(1000);
    }

    // --- Compare snapshot conversion times ---
    // SENSE needs about 67 ms, VIN and ADIN about 33 ms.
    static const char* const names[3] = { "SENSE", "VIN", "ADIN" };
    for (uint8_t ch = 0; ch < 3; ch++) {
        float f = 0;
        uint32_t t0 = HAL_GetTick();
        bool ok = ltc.snapshot(ch, f);                                  // One snapshot conversion, (channel 0–2, out V) → bool
        uint32_t dt = HAL_GetTick() - t0;
        printf("%s snapshot %u, %.4f V in %u ms\r\n", names[ch], (unsigned)ok, f, (unsigned)dt);
    }
    printf("snapshot mode afterwards %u\r\n", (unsigned)ltc.is_snapshot_mode());  // Snapshot mode active, () → bool

    // --- Toggle the stuck-bus timer and page read/write ---
    // The CONTROL register shows both bits cleared, then restored.
    ltc.set_stuck_bus_timer(false);                                     // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(false);                                             // I²C page read/write, (enabled) → void
    printf("CONTROL (timer off, page off) 0x%02X\r\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t
    ltc.set_stuck_bus_timer(true);                                      // Stuck-bus reset timer, (enabled) → void
    ltc.set_page_rw(true);                                              // I²C page read/write, (enabled) → void
    printf("CONTROL (restored) 0x%02X\r\n", (unsigned)ltc.read_control());  // Read CONTROL, () → uint8_t

    // --- NTC temperature from ADIN ---
    // A made-up ADIN / VIN code pair run through the datasheet example formula.
    printf("temperature %.1f C\r\n", LTC4151Full::temperature_from_adin(900, 1200));  // NTC example circuit, (adin_code, vin_code) → float °C

    // --- Power tally ---
    // Sample the power 25 times at 200 ms (5 s) and print min, max and average.
    float pmin = 1e9f, pmax = 0, psum = 0;
    for (int n = 0; n < 25; n++) {
        float p = 0;
        p = ltc.power();
        if (p < pmin) pmin = p;
        if (p > pmax) pmax = p;
        psum += p;
        HAL_Delay(200);
    }
    printf("power min %.3f W, max %.3f W, avg %.3f W\r\n", pmin, pmax, psum / 25.0f);

    while (true) HAL_Delay(1000);
}
