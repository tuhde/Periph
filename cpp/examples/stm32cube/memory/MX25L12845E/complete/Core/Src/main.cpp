#include <stdio.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "SPIConnectionSTM32Cube.h"
#include "MX25L12845E.h"

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

// SPI1 on the Arduino header: PA5=SCK (D13), PA6=MISO (D12), PA7=MOSI (D11); CS = PB6 (D10).
// SPI1 is on APB2 (100 MHz), so the baud rate is 100 MHz / prescaler.
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
// All writes and erases stay inside the last 64 KiB of the chip (0xFF0000..0xFFFFFF).
static const uint32_t SCRATCH = 0xFF0000;
int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    spi1_init(SPI_BAUDRATEPRESCALER_16, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);   // SPI Mode 0, 100 MHz / 16 = 6.25 MHz
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_6);              // Create SPI connection, (&hspi1, GPIOB, GPIO_PIN_6) → SPIConnectionSTM32Cube
    MX25L12845EOptions opt;                                                    // Driver options, (now_ms=nullptr, delay_ms=nullptr, auto_erase=false)
    opt.now_ms = HAL_GetTick;
    opt.delay_ms = HAL_Delay;
    MX25L12845EFull flash(connection, opt);                                    // Create MX25L12845E driver and check the ID, (connection, options) → MX25L12845EFull
    if (flash.status() != BlockError::Ok) {
        printf("MX25L12845E not found (error %d)\r\n", static_cast<int>(flash.status()));
        while (true) HAL_Delay(1000);
    }

    uint8_t id[3], res = 0, man = 0, dev = 0;
    flash.jedec_id(id);                                                        // Read JEDEC ID, (out[3]) → BlockError
    flash.electronic_id(&res);                                                 // Read the old-style electronic ID (RES), (out) → BlockError
    flash.device_ids(&man, &dev);                                              // Read manufacturer + device ID (REMS), (&man, &dev) → BlockError
    printf("JEDEC %02X %02X %02X, RES %02X, REMS %02X %02X\r\n", id[0], id[1], id[2], res, man, dev);

    uint8_t sr = 0, bp = 0, scur = 0;
    flash.read_status(&sr);                                                    // Read the status register, (out) → BlockError
    flash.protection(&bp);                                                     // Read BP3:0, (out) → BlockError
    flash.read_security(&scur);                                                // Read the security register, (out) → BlockError
    printf("status 0x%02X, protection BP=%u, security 0x%02X\r\n", sr, bp, scur);

    uint8_t esn[16];
    flash.serial_number(esn);                                                  // Read the factory serial number, (out[16]) → BlockError
                                                                               // 128-bit ESN from the secured OTP area
    printf("ESN: "); print_hex(esn, 16);
    uint8_t sfdp[16];
    flash.read_sfdp(0, sfdp, 16);                                              // Read the SFDP table, (address, buf, len) → BlockError
                                                                               // starts with the signature "SFDP"
    printf("SFDP: "); print_hex(sfdp, 16);

    flash.erase_sector(SCRATCH);                                               // Erase one 4 KiB sector, (address) → BlockError
                                                                               // takes 60 ms typ, 300 ms max
    uint8_t page[256], back[256];
    for (int i = 0; i < 256; i++) page[i] = static_cast<uint8_t>(i);
    flash.page_program(SCRATCH, page, 256);                                    // Program one page (must not cross a page boundary), (address, data, len) → BlockError
                                                                               // 1.4 ms typ for 256 bytes; bits only go 1 -> 0
    flash.fast_read(SCRATCH, back, 256);                                       // FAST_READ, (address, buf, len) → BlockError
                                                                               // same data as read_bytes, for clocks above 50 MHz
    printf("page program + fast_read: %s\r\n", memcmp(page, back, 256) == 0 ? "ok" : "MISMATCH");

    flash.erase_block_32k(SCRATCH);                                            // Erase a 32 KiB block, (address) → BlockError
                                                                               // 0.5 s typ
    flash.erase_block_64k(SCRATCH);                                            // Erase a 64 KiB block, (address) → BlockError
                                                                               // 0.7 s typ
    uint8_t blank[16];
    flash.read_bytes(SCRATCH, blank, 16);                                      // Read bytes, (address, buf, len) → BlockError
    printf("after block erase: "); print_hex(blank, 16);
    // chip_erase() (80 s typ, wipes all 16 MiB) is deliberately not called here.

    uint8_t text[] = "erase-aware write";
    flash.write_bytes(SCRATCH + 100, text, sizeof(text));                      // Write bytes, erasing the sector only when needed, (address, data, len) → BlockError
    flash.write_bytes(SCRATCH + 100, text, sizeof(text));                      // the same bytes again: nothing is programmed or erased
    flash.read_bytes(SCRATCH + 100, back, sizeof(text));
    printf("text: %s\r\n", back);

    flash.deep_power_down();                                                   // Enter deep power-down, () → BlockError
                                                                               // only RDP / RES are accepted afterwards
    flash.release_deep_power_down();                                           // Leave deep power-down (RDP, waits tRES2), () → BlockError
    printf("busy: %d\r\n", flash.is_busy());                                  // WIP bit, () → bool
    flash.wait_ready(1000);                                                    // Wait for WIP = 0, (timeout_ms) → BlockError

    BlockInfo info = flash.info();                                             // Geometry and capabilities, () → BlockInfo
                                                                               // 512-byte blocks, 4 KiB erase unit, needs_erase
    printf("block device: %lu blocks of %lu bytes, erase unit %lu, needs_erase=%d\r\n",
           static_cast<unsigned long>(info.block_count), static_cast<unsigned long>(info.block_size),
           static_cast<unsigned long>(info.erase_size), info.needs_erase);
    // set_protection(), write_status(), program_otp() and lock_otp() change non-volatile state; see the spec before using them.

    flash.erase_sector(SCRATCH);
    printf("done\r\n");
    while (true) HAL_Delay(1000);
}
