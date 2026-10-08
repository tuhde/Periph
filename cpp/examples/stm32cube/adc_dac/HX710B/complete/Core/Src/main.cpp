#include <stdio.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "HX711ConnectionSTM32Cube.h"
#include "HX710B.h"

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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    // HX710B bit-bang pins: DOUT on PB10 (D6), PD_SCK on PB4 (D5). The connection configures them,
    // so it must be constructed after gpio_clocks_init().
    HX711ConnectionSTM32Cube connection(GPIOB, GPIO_PIN_10, GPIOB, GPIO_PIN_4);
    HX710BFull<HX711ConnectionSTM32Cube> chip(connection);
    while (true) {
        bool ready = chip.is_ready();                          // Check if conversion is ready (non-blocking), () → bool
                                                                // returns true when DOUT is LOW
        int32_t raw = chip.read_raw();                         // Read signed 24-bit differential-input value, () → int32_t
                                                                // blocks until DOUT goes LOW, then clocks out 24 bits

        chip.set_rate(40);                                     // Select differential-input output rate, (rate: 10|40) → void
                                                                // takes effect after next read; issues dummy read to apply
        chip.set_rate(10);                                     // (restores default 10 SPS)

        int32_t avg = chip.read_average(10);                   // Average multiple raw readings, (times=10) → int32_t
                                                                // blocks for `times` complete conversions

        chip.tare(10);                                         // Capture zero offset from 10-reading average, (times=10) → void
                                                                // stores result in internal _offset; call with nothing on the scale
        int32_t offset = chip.get_offset();                    // Return stored tare offset, () → int32_t

        chip.set_scale(420.0f);                                // Set calibration scale factor, (factor: float) → void
                                                                // factor = (read_average() - offset) / known_weight_in_target_unit
        float scale = chip.get_scale();                        // Return current scale factor, () → float

        float weight = chip.read_weight(5);                    // Return calibrated weight, (times=1) → float
                                                                // computes (read_average(times) - offset) / scale
        printf("%.1f\r\n", (double)weight);

        int32_t supp_raw = chip.read_supply_diff_raw();        // Read raw DVDD−AVDD supply-difference code, () → int32_t
                                                                // uncalibrated ADC code, no absolute LSB-to-volts scale
        printf("%d\r\n", supp_raw);

        chip.power_down();                                     // Enter power-down mode, () → void
                                                                // holds PD_SCK HIGH for >60 µs
        chip.power_up();                                       // Exit power-down, reset chip, discard settling conversion, () → void
                                                                // resets to differential input, gain 128, 10 SPS

        HAL_Delay(500);
    }
}
