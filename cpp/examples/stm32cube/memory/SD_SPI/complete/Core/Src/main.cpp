#include <stdio.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "SD_SPI.h"

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11); CS = PB10 (D5).
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


static void print_hex(const uint8_t* p, int n) {
    for (int i = 0; i < n; i++) printf("%02X ", p[i]);
    printf("\r\n");
}
int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_256, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);  // SPI Mode 0, 100 MHz / 256 = 390 kHz (SD init needs <= 400 kHz)
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_10);             // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_4) → SPIConnectionSTM32Cube
    SDSPIFull::Options opt = SDSPIFull::defaults();                           // Options with CRC16 checking on, () → Options
    opt.now_ms = HAL_GetTick;
    SDSPIFull sd(connection, opt);                                            // Create SD driver and init the card, (spi, options) → SDSPIFull
    if (sd.status() != BlockError::Ok) {                                      // Result of card init, () → BlockError
        printf("no SD card (error %d)\r\n", static_cast<int>(sd.status()));
        while (true) HAL_Delay(1000);
    }

    printf("type: %d (1=v1 2=v2 3=SDHC)\r\n", static_cast<int>(sd.card_type()));  // Card generation, () → CardType
    BlockInfo info = sd.info();                                               // Geometry and capabilities, () → BlockInfo
                                                                              // block size, count, erase unit, name from the CID
    printf("name=%s blocks=%lu block_size=%lu erase_size=%lu needs_erase=%d\r\n", info.name,
           static_cast<unsigned long>(info.block_count), static_cast<unsigned long>(info.block_size),
           static_cast<unsigned long>(info.erase_size), info.needs_erase);

    uint8_t cid[16];
    sd.cid(cid);                                                              // Read CID register, (out[16]) → BlockError
                                                                              // manufacturer, name, revision, serial, date
    printf("CID: "); print_hex(cid, 16);
    printf("CSD: "); print_hex(sd.csd(), 16);                                 // CSD register read at init, () → const uint8_t*
    uint32_t ocr = 0;
    sd.ocr(&ocr);                                                             // Read OCR, (out) → BlockError
                                                                              // voltage window and CCS (block addressing) bit
    printf("OCR: %08lX\r\n", static_cast<unsigned long>(ocr));
    printf("present: %d\r\n", sd.is_present());                              // Card responds, () → bool

    // Use the last 8 blocks of the card as scratch, restoring their contents.
    static uint8_t saved[8 * 512], pattern[8 * 512], back[8 * 512];
    uint32_t scratch = sd.block_count() - 8;
    sd.read(scratch, saved, 8);                                               // Read blocks, (block, buf, count) → BlockError
                                                                              // CMD18 multi-block read for count > 1
    for (size_t i = 0; i < sizeof(pattern); i++) pattern[i] = static_cast<uint8_t>(i * 7 + 3);
    printf("write: %d\r\n", static_cast<int>(sd.write(scratch, pattern, 8)));  // Write blocks, (block, data, count) → BlockError
                                                                              // CMD25 multi-block write with stop token
    printf("sync: %d\r\n", static_cast<int>(sd.sync()));                      // Wait until programming is done, () → BlockError
    sd.read(scratch, back, 8);
    printf("roundtrip: %s\r\n", memcmp(pattern, back, sizeof(pattern)) == 0 ? "ok" : "MISMATCH");

    printf("set_crc(false): %d\r\n", static_cast<int>(sd.set_crc(false)));     // Disable CRC checking, (enabled) → BlockError
                                                                              // CMD59 off; data CRC16 is no longer verified
    sd.set_crc(true);
    printf("set_speed: %d\r\n", sd.set_speed(6000000));                        // Change SCK frequency, (hz) → bool
                                                                              // picks the largest prescaler setting <= hz
    printf("erase: %d\r\n", static_cast<int>(sd.erase(scratch, 8)));          // Erase blocks, (block, count) → BlockError
                                                                              // CMD32/33/38; contents afterwards are card dependent
    sd.write(scratch, saved, 8);                                              // restore the original contents

    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
