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
                                                                             // writes MODE=0x40 (Sequential) and verifies it with RDMR

    printf("size: %lu bytes\r\n", (unsigned long)sram.size());              // Array size, () → uint32_t bytes
                                                                             // always 131072

    sram.write_byte(0x00010, 0xA5);                                          // Write one byte, (address 0–131071, value 0–255)
                                                                             // WRITE 0x02 + 24-bit address + data
    printf("byte @0x10: 0x%02X\r\n", sram.read_byte(0x00010));               // Read one byte, (address 0–131071) → byte
                                                                             // READ 0x03 + 24-bit address

    uint8_t pattern[300];
    for (int i = 0; i < 300; i++) pattern[i] = (uint8_t)i;
    sram.write(0x00100, pattern, sizeof(pattern));                           // Block write, (address, data, length)
                                                                             // split into transactions of at most max_chunk() bytes
    uint8_t readback[300];
    sram.read(0x00100, readback, sizeof(readback));                          // Block read, (address, buf, length)
                                                                             // sequential read across the chunk boundary
    printf("block of 300: %s\r\n", memcmp(pattern, readback, sizeof(pattern)) == 0 ? "match" : "MISMATCH");

    sram.fill(0x00200, 64, 0xFF);                                            // Fill a range, (address, length, value=0)
                                                                             // writes value over the range, chunked
    printf("fill @0x200: 0x%02X\r\n", sram.read_byte(0x00200));

    sram.set_max_chunk(64);                                                  // Limit bytes per transaction, (n 1–256)
                                                                             // smaller frames for bus layers with small buffers
    printf("max_chunk: %u\r\n", (unsigned)sram.max_chunk());                // Current chunk limit, () → size_t bytes
    sram.set_max_chunk(256);                                                 // Limit bytes per transaction, (n 1–256)

    sram.set_mode(SRAM23X1024Full::MODE_PAGE);                               // Select operating mode, (mode=MODE_PAGE=0x80)
                                                                             // WRMR then RDMR check; accesses now wrap within 32-byte pages
    printf("mode: 0x%02X\r\n", sram.get_mode());                            // Read operating mode, () → byte
                                                                             // RDMR bits 7:6
    sram.write(0x00110, pattern, 64);                                        // Block write, (address, data, length)
                                                                             // the driver splits at page boundaries, so the data stays linear
    sram.read(0x00110, readback, 64);                                        // Block read, (address, buf, length)
    printf("page mode 64 bytes: %s\r\n", memcmp(pattern, readback, 64) == 0 ? "match" : "MISMATCH");

    sram.set_mode(SRAM23X1024Full::MODE_SEQUENTIAL);                         // Select operating mode, (mode=MODE_SEQUENTIAL=0x40)
    sram.reset_io();                                                         // Return to SPI mode, ()
                                                                             // sends RSTIO (0xFF); harmless while already in SPI mode

    while (true) HAL_Delay(1000);
}
