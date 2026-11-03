#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include <string.h>
#include "23X1024.h"

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

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);   // SPI Mode 0, 6.25 MHz
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_4);             // CS = PB4
    SRAM23X1024Full sram(connection);                                          // aborts if the MODE register round-trip fails
    check_true(true, "init_mode_roundtrip");
    check_true(sram.size() == 131072, "size");
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "default_mode_sequential");

    sram.write_byte(0x00000, 0xA5);
    check_true(sram.read_byte(0x00000) == 0xA5, "byte_roundtrip_first");
    sram.write_byte(0x1FFFF, 0x5A);
    check_true(sram.read_byte(0x1FFFF) == 0x5A, "byte_roundtrip_last");
    sram.write_byte(0x10000, 0x3C);
    check_true(sram.read_byte(0x10000) == 0x3C && sram.read_byte(0x00000) == 0xA5, "address_bit16_distinct");

    static uint8_t pattern[1000], back[1000];
    for (int i = 0; i < 1000; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    sram.write(0x00400, pattern, sizeof(pattern));
    sram.read(0x00400, back, sizeof(back));
    check_true(memcmp(pattern, back, sizeof(pattern)) == 0, "block_roundtrip_chunked");

    sram.fill(0x02000, 100, 0xC3);
    bool fill_ok = true;
    for (int i = 0; i < 100; i++) fill_ok = fill_ok && sram.read_byte(0x02000 + i) == 0xC3;
    check_true(fill_ok && sram.read_byte(0x02000 + 100) != 0xC3, "fill_range");

    sram.write(0x1FFF0, pattern, 16);
    sram.read(0x1FFF0, back, 16);
    check_true(memcmp(pattern, back, 16) == 0, "block_at_array_end");

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_PAGE, "set_mode_page");
    sram.write(0x04010, pattern, 64);          // crosses the pages at 0x4020 and 0x4040
    sram.read(0x04010, back, 64);
    check_true(memcmp(pattern, back, 64) == 0, "page_mode_crossing_linear");

    sram.set_mode(SRAM23X1024Full::MODE_BYTE);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_BYTE, "set_mode_byte");
    sram.write(0x05000, pattern, 8);
    sram.read(0x05000, back, 8);
    check_true(memcmp(pattern, back, 8) == 0, "byte_mode_block");

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "set_mode_sequential");

    sram.reset_io();
    check_true(sram.get_mode() == SRAM23X1024Full::MODE_SEQUENTIAL, "reset_io_stays_spi");
    check_true(sram.read_byte(0x00000) == 0xA5, "data_survives_reset_io");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (1) HAL_Delay(1000);
}
