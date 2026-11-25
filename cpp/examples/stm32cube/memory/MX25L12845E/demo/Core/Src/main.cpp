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
    // --- Bring up the chip ---
    // Plain single-I/O SPI: the driver releases deep power-down, checks the JEDEC ID and
    // waits until the chip is idle. The ESN is a factory-programmed unique serial number.
    MX25L12845EFull flash(connection, opt);                                    // Create MX25L12845E driver and check the ID, (connection, options) → MX25L12845EFull
    if (flash.status() != BlockError::Ok) {
        printf("MX25L12845E not found (error %d)\r\n", static_cast<int>(flash.status()));
        while (true) HAL_Delay(1000);
    }
    uint8_t esn[16];
    flash.serial_number(esn);                                                  // Read the factory serial number, (out[16]) → BlockError
    printf("MX25L12845E, %lu MiB, serial ", static_cast<unsigned long>(flash.size() / (1024 * 1024)));
    print_hex(esn, 16);

    // --- Append records to a ring of four sectors ---
    // 32-byte records (sequence, tick, payload) fill a sector, 128 per sector. A blank slot
    // (all 0xFF) needs no erase, so appending is just a page program; only when the writer
    // enters the next sector is that sector erased first — one erase per 128 records.
    const uint32_t RING = 0xFFC000, SECTORS = 4, SLOT = 32, SLOTS = SECTORS * 4096 / SLOT;
    for (uint32_t s = 0; s < SECTORS; s++) flash.erase_sector(RING + s * 4096);   // Erase one 4 KiB sector, (address) → BlockError
    uint32_t t0 = HAL_GetTick(), erases = 0, slot = 0;
    uint32_t eraseMs = 0;
    for (uint32_t seq = 0; seq < 300; seq++) {
        if (slot % 128 == 0 && seq >= SLOTS) {                                 // wrapped into the next sector: make it blank
            uint32_t e0 = HAL_GetTick();
            flash.erase_sector(RING + (slot / 128) * 4096);
            eraseMs += HAL_GetTick() - e0;
            erases++;
        }
        uint8_t rec[SLOT];
        memset(rec, 0xA5, sizeof(rec));
        memcpy(rec, &seq, 4);
        uint32_t tick = HAL_GetTick();
        memcpy(rec + 4, &tick, 4);
        flash.page_program(RING + slot * SLOT, rec, SLOT);                     // Program one record (inside a page), (address, data, len) → BlockError
        slot = (slot + 1) % SLOTS;
    }
    printf("appended 300 records in %lu ms, %lu sector erases (%lu ms)\r\n", static_cast<unsigned long>(HAL_GetTick() - t0),
           static_cast<unsigned long>(erases), static_cast<unsigned long>(eraseMs));

    // --- Verify by scanning the ring ---
    // The newest 128 * 4 = 512 slots hold the last records; every slot must carry a valid
    // sequence number and the 0xA5 payload.
    static uint8_t ringData[4 * 4096];
    flash.read_bytes(RING, ringData, sizeof(ringData));                        // Read bytes, (address, buf, len) → BlockError
    uint32_t good = 0, newest = 0;
    for (uint32_t s = 0; s < SLOTS; s++) {
        uint32_t seq;
        memcpy(&seq, ringData + s * SLOT, 4);
        if (seq != 0xFFFFFFFFu && ringData[s * SLOT + 20] == 0xA5) { good++; if (seq > newest) newest = seq; }
    }
    printf("ring holds %lu valid records, newest sequence number %lu\r\n", static_cast<unsigned long>(good), static_cast<unsigned long>(newest));

    // --- Leave the chip clean ---
    for (uint32_t s = 0; s < SECTORS; s++) flash.erase_sector(RING + s * 4096);
    printf("ring erased\r\n");
    while (true) HAL_Delay(1000);
}
