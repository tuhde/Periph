#include <stdio.h>
#include <string.h>
#include <math.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11); CS = PB4 (D5).
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

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);   // SPI Mode 0, 100 MHz / 16 = 6.25 MHz
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_4);                      // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_4) → SPIConnectionSTM32Cube
    SRAM23X1024Full sram(connection);                                        // Create 23X1024 Full driver, (connection) → SRAM23X1024Full

    // --- Store a 256-entry lookup table in external SRAM ---
    // A sine table is the kind of data a microcontroller with little RAM
    // keeps outside: written once, then read back entry by entry.
    uint8_t table[256];
    for (int i = 0; i < 256; i++) table[i] = (uint8_t)(127.5f + 127.5f * sinf(2.0f * 3.14159265f * i / 256.0f));
    sram.write(0x00000, table, sizeof(table));                               // Block write, (address, data, length)
    uint8_t check[256];
    sram.read(0x00000, check, sizeof(check));                                // Block read, (address, buf, length)
    printf("sine table: %s (sin(64/256 turn) = %u)\r\n",
           memcmp(table, check, sizeof(table)) == 0 ? "ok" : "MISMATCH", (unsigned)sram.read_byte(64));   // Read one byte, (address) → byte

    // --- Wipe the whole array and spot-check it ---
    // Zero write time means the 128 KiB fill runs at SPI speed with no
    // waiting; the first, middle and last byte prove the full range.
    sram.fill(0x00000, sram.size(), 0xA5);                                   // Fill a range, (address, length, value=0)
    printf("fill spot check: 0x%02X 0x%02X 0x%02X\r\n",
           sram.read_byte(0x00000), sram.read_byte(0x10000), sram.read_byte(0x1FFFF));                      // Read one byte, (address) → byte

    // --- Page mode: a write that crosses a 32-byte page boundary ---
    // The hardware would wrap inside the page; the driver splits the
    // transfer so the 64 bytes starting at address 16 still read back in order.
    sram.set_mode(SRAM23X1024Full::MODE_PAGE);                               // Select operating mode, (mode=MODE_PAGE=0x80)
    uint8_t ramp[64];
    for (int i = 0; i < 64; i++) ramp[i] = (uint8_t)(0x40 + i);
    sram.write(16, ramp, sizeof(ramp));                                      // Block write, (address, data, length)
    uint8_t ramp_back[64];
    sram.read(16, ramp_back, sizeof(ramp_back));                             // Block read, (address, buf, length)
    printf("page-crossing write: %s\r\n", memcmp(ramp, ramp_back, sizeof(ramp)) == 0 ? "ok" : "MISMATCH");
    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);                         // Select operating mode, (mode=MODE_SEQUENTIAL=0x40)

    // --- Measure throughput for a 4 KiB transfer ---
    // Sequential mode streams 4096 bytes in 16 transactions; HAL_GetTick()
    // times them in milliseconds.
    static uint8_t block[4096];
    for (int i = 0; i < 4096; i++) block[i] = (uint8_t)i;
    uint32_t t0 = HAL_GetTick();
    sram.write(0x01000, block, sizeof(block));                               // Block write, (address, data, length)
    uint32_t t1 = HAL_GetTick();
    sram.read(0x01000, block, sizeof(block));                                // Block read, (address, buf, length)
    uint32_t t2 = HAL_GetTick();
    printf("4 KiB write: %lu ms, read: %lu ms\r\n", (unsigned long)(t1 - t0), (unsigned long)(t2 - t1));

    while (true) HAL_Delay(1000);
}
