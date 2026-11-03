#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "HY3131.h"

static UART_HandleTypeDef huart2;
static SPI_HandleTypeDef hspi1;

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
static void spi1_init(uint32_t prescaler, uint32_t polarity, uint32_t phase) {
    __HAL_RCC_SPI1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_5 | GPIO_PIN_6 | GPIO_PIN_7;
    gpioInit.Mode      = GPIO_MODE_AF_PP;
    gpioInit.Pull      = GPIO_NOPULL;
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF5_SPI1;
    HAL_GPIO_Init(GPIOA, &gpioInit);

    hspi1.Instance               = SPI1;
    hspi1.Init.Mode              = SPI_MODE_MASTER;
    hspi1.Init.Direction         = SPI_DIRECTION_2LINES;
    hspi1.Init.DataSize          = SPI_DATASIZE_8BIT;
    hspi1.Init.CLKPolarity       = polarity;
    hspi1.Init.CLKPhase          = phase;
    hspi1.Init.NSS               = SPI_NSS_SOFT;
    hspi1.Init.BaudRatePrescaler = prescaler;
    hspi1.Init.FirstBit          = SPI_FIRSTBIT_MSB;
    hspi1.Init.TIMode            = SPI_TIMODE_DISABLE;
    hspi1.Init.CRCCalculation    = SPI_CRCCALCULATION_DISABLE;
    HAL_SPI_Init(&hspi1);
}

static int passed = 0;
static int failed = 0;

static void check_true(const char* label, bool condition) {
    if (condition) { printf("PASS %s\r\n", label); passed++; }
    else           { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_32, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);
    HY3131Full adc(connection, 1.2f);

    int32_t raw = adc.read_raw();                                    // Read AD1 code, () → int32_t 24-bit
    check_true("read_raw in 24-bit range", raw != HY3131Minimal::TIMEOUT && raw >= -8388608 && raw <= 8388607);
    float v = adc.read_voltage();                                    // Read AD1 voltage, () → float V
    check_true("read_voltage finite", v == v);
    adc.data_ready();                                                // Report AD1F, () → bool
    check_true("data_ready accepted", true);

    adc.configure_ad1(4, 2, 0, 0, 0, 0);                             // Configure AD1, (osr, chop, input_gain, ref_gain, offset, buffers) → void
    check_true("read_raw after configure_ad1", adc.read_raw() != HY3131Minimal::TIMEOUT);
    adc.set_ad1_input(10, 3, 2);                                     // Select AD1 inputs, (pos, neg, filter_r) → void
    adc.set_ad1_reference(1, 1);                                     // Select AD1 reference, (high, low) → void
    check_true("set_ad1_input/reference accepted", true);

    int32_t tcode = adc.read_temperature_code();                     // Read temperature code, () → int32_t
    float t = adc.read_temperature(tcode);                           // Read die temperature, (tcode_25) → float °C
    check_true("read_temperature finite", t == t);

    adc.enable_ad2(5, 1, 0, false, false, 0, false);                 // Enable AD2, (osr, input_gain, ref_gain, chop, clk_div4, source, use_ad3) → void
    int32_t ad2 = adc.read_ad2_raw();                                // Read AD2 code, () → int32_t 19-bit
    check_true("read_ad2_raw in 19-bit range", ad2 >= -262144 && ad2 <= 262143);
    adc.enable_lpf(0);                                               // Enable low-pass filter, (bw) → void
    check_true("read_lpf_raw accepted", adc.read_lpf_raw() != HY3131Minimal::TIMEOUT);
    adc.enable_rms();                                                // Enable RMS converter, () → void
    float rms = adc.read_rms();                                      // Read RMS, () → float LSB
    check_true("read_rms >= 0", rms >= 0.0f);
    adc.enable_peak_hold(0);                                         // Enable peak hold, (source) → void
    int32_t pk_min, pk_max;
    adc.read_peak(pk_min, pk_max);                                   // Read peak hold, (min&, max&) → void
    check_true("read_peak min <= max", pk_min <= pk_max);
    adc.enable_interrupts(0x04);                                     // Enable interrupts, (mask) → void
    adc.read_flags();                                                // Read and clear INTF, () → uint8_t
    check_true("read_flags accepted", true);
    adc.reset();                                                     // Software reset, () → void
    check_true("reset accepted", true);

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (true) HAL_Delay(1000);
}
