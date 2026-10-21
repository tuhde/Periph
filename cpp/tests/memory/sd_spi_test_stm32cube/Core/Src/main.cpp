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
    spi1_init(SPI_BAUDRATEPRESCALER_256, SPI_POLARITY_LOW, SPI_PHASE_1EDGE);  // SPI Mode 0, 100 MHz / 256 = 390 kHz (SD init needs <= 400 kHz)
    HAL_Delay(2000);

    SPIConnectionSTM32Cube connection(&hspi1, GPIOB, GPIO_PIN_10);             // CS = PB10
    SDSPIFull::Options opt = SDSPIFull::defaults();
    opt.now_ms = HAL_GetTick;
    SDSPIFull sd(connection, opt);
    check_true(sd.status() == BlockError::Ok, "init");
    if (sd.status() != BlockError::Ok) {
        printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
        while (1) HAL_Delay(1000);
    }
    printf("type=%d blocks=%lu name=%s\r\n", static_cast<int>(sd.card_type()),
           static_cast<unsigned long>(sd.block_count()), sd.info().name);
    check_true(sd.card_type() != SDSPIMinimal::CardType::None, "card_type_detected");
    check_true(sd.block_count() > 0 && sd.block_size() == 512, "geometry");
    check_true(sd.erase_size() == 512 && !sd.needs_erase(), "no_erase_needed");
    check_true(sd.is_present(), "is_present");

    uint8_t cid[16];
    check_true(sd.cid(cid) == BlockError::Ok, "cid_read");
    uint32_t ocr = 0;
    check_true(sd.ocr(&ocr) == BlockError::Ok && (ocr & 0x00FF8000), "ocr_has_3v3_window");

    // Scratch area: the last 8 blocks, contents saved and restored.
    static uint8_t saved[8 * 512], pat[8 * 512], back[8 * 512];
    uint32_t scratch = sd.block_count() - 8;
    check_true(sd.read(scratch, saved, 8) == BlockError::Ok, "save_scratch");
    for (size_t i = 0; i < sizeof(pat); i++) pat[i] = static_cast<uint8_t>(i * 7 + 3);

    check_true(sd.write(scratch, pat, 1) == BlockError::Ok, "write_single");
    check_true(sd.read(scratch, back, 1) == BlockError::Ok && !memcmp(pat, back, 512), "single_roundtrip");
    memset(back, 0, sizeof(back));
    check_true(sd.write(scratch, pat, 8) == BlockError::Ok, "write_multi");
    check_true(sd.sync() == BlockError::Ok, "sync");
    check_true(sd.read(scratch, back, 8) == BlockError::Ok && !memcmp(pat, back, sizeof(pat)), "multi_roundtrip");
    check_true(sd.read(sd.block_count(), back, 1) == BlockError::OutOfRange, "out_of_range");

    check_true(sd.set_crc(false) == BlockError::Ok, "crc_off");
    check_true(sd.read(scratch, back, 1) == BlockError::Ok && !memcmp(pat, back, 512), "read_with_crc_off");
    check_true(sd.set_crc(true) == BlockError::Ok, "crc_on");
    check_true(sd.set_speed(25000000), "set_speed_25mhz");
    check_true(sd.read(scratch, back, 8) == BlockError::Ok && !memcmp(pat, back, sizeof(pat)), "read_at_higher_speed");

    check_true(sd.write(scratch, saved, 8) == BlockError::Ok, "restore_scratch");
    check_true(sd.sync() == BlockError::Ok, "final_sync");

    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (1) HAL_Delay(1000);
}
