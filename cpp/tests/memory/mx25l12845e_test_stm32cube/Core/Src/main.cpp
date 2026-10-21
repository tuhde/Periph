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

static int passed = 0, failed = 0;
static void check_true(bool cond, const char *label) {
    if (cond) { printf("PASS %s\r\n", label); passed++; }
    else       { printf("FAIL %s\r\n", label); failed++; }
}
static void finish() {
    printf("===DONE: %d passed, %d failed===\r\n", passed, failed);
    while (1) HAL_Delay(1000);
}

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
    MX25L12845EFull flash(connection, opt);
    check_true(flash.status() == BlockError::Ok, "init (JEDEC C2 20 18)");
    if (flash.status() != BlockError::Ok) finish();

    uint8_t id[3], res = 0, man = 0, dev = 0;
    flash.jedec_id(id);
    check_true(id[0] == 0xC2 && id[1] == 0x20 && id[2] == 0x18, "jedec_id");
    check_true(flash.electronic_id(&res) == BlockError::Ok && res == 0x17, "electronic_id (RES) = 17h");
    check_true(flash.device_ids(&man, &dev) == BlockError::Ok && man == 0xC2 && dev == 0x17, "device_ids (REMS) = C2 17");

    uint8_t sr = 0xFF, bp = 0xFF, scur = 0xFF;
    check_true(flash.read_status(&sr) == BlockError::Ok && !(sr & 0x03), "status: not busy, WEL clear");
    check_true(flash.protection(&bp) == BlockError::Ok, "protection readable");
    printf("INFO status 0x%02X, BP=%u\r\n", sr, bp);
    check_true(flash.read_security(&scur) == BlockError::Ok, "security register readable");
    printf("INFO security 0x%02X (bit0 = OTP factory locked)\r\n", scur);

    uint8_t esn[16], esn2[16];
    check_true(flash.serial_number(esn) == BlockError::Ok, "serial number");
    printf("INFO ESN: "); print_hex(esn, 16);
    check_true(flash.serial_number(esn2) == BlockError::Ok && memcmp(esn, esn2, 16) == 0, "ESN stable");
    uint8_t sfdp[8];
    check_true(flash.read_sfdp(0, sfdp, 8) == BlockError::Ok && memcmp(sfdp, "SFDP", 4) == 0, "SFDP signature");

    if (bp != 0) { printf("INFO protection was BP=%u; clearing for the test\r\n", bp); flash.set_protection(0); }

    // erase / blank check
    static uint8_t buf[4096], back[4096];
    uint32_t t = HAL_GetTick();
    check_true(flash.erase_sector(SCRATCH) == BlockError::Ok, "erase_sector");
    printf("INFO sector erase %lu ms\r\n", static_cast<unsigned long>(HAL_GetTick() - t));
    flash.read_bytes(SCRATCH, back, 4096);
    bool blank = true;
    for (int i = 0; i < 4096; i++) if (back[i] != 0xFF) { blank = false; break; }
    check_true(blank, "sector blank (all FF) after erase");

    // page program + readback + the AND property of programming
    for (int i = 0; i < 256; i++) buf[i] = static_cast<uint8_t>(i * 3 + 1);
    t = HAL_GetTick();
    check_true(flash.page_program(SCRATCH, buf, 256) == BlockError::Ok, "page_program 256 bytes");
    printf("INFO page program %lu ms\r\n", static_cast<unsigned long>(HAL_GetTick() - t));
    flash.read_bytes(SCRATCH, back, 256);
    check_true(memcmp(buf, back, 256) == 0, "read_bytes matches");
    flash.fast_read(SCRATCH, back, 256);
    check_true(memcmp(buf, back, 256) == 0, "fast_read matches");
    uint8_t zeros[4] = {0x0F, 0xF0, 0x00, 0xFF};
    flash.page_program(SCRATCH, zeros, 4);
    flash.read_bytes(SCRATCH, back, 4);
    check_true(back[0] == (buf[0] & 0x0F) && back[1] == (buf[1] & 0xF0) && back[2] == 0 && back[3] == buf[3], "programming only clears bits (AND)");
    check_true(flash.page_program(SCRATCH + 250, buf, 10) == BlockError::OutOfRange, "page-crossing program refused");

    // erase-aware write
    for (int i = 0; i < 4096; i++) buf[i] = static_cast<uint8_t>(i ^ (i >> 7));
    t = HAL_GetTick();
    check_true(flash.write_bytes(SCRATCH + 4096 - 100, buf, 3000) == BlockError::Ok, "write_bytes across two sectors");
    printf("INFO erase-aware write of 3000 bytes %lu ms\r\n", static_cast<unsigned long>(HAL_GetTick() - t));
    flash.read_bytes(SCRATCH + 4096 - 100, back, 3000);
    check_true(memcmp(buf, back, 3000) == 0, "read back across sectors");
    t = HAL_GetTick();
    check_true(flash.write_bytes(SCRATCH + 4096 - 100, buf, 3000) == BlockError::Ok, "identical rewrite");
    printf("INFO identical rewrite %lu ms (no program, no erase)\r\n", static_cast<unsigned long>(HAL_GetTick() - t));
    check_true(flash.write_bytes(MX25L12845EMinimal::SIZE - 2, buf, 5) == BlockError::OutOfRange, "write past the end refused");

    // block erases
    t = HAL_GetTick();
    check_true(flash.erase_block_32k(SCRATCH) == BlockError::Ok, "erase_block_32k");
    printf("INFO 32 KiB block erase %lu ms\r\n", static_cast<unsigned long>(HAL_GetTick() - t));
    t = HAL_GetTick();
    check_true(flash.erase_block_64k(SCRATCH) == BlockError::Ok, "erase_block_64k");
    printf("INFO 64 KiB block erase %lu ms\r\n", static_cast<unsigned long>(HAL_GetTick() - t));
    flash.read_bytes(SCRATCH + 0xF000, back, 16);
    check_true(back[0] == 0xFF && back[15] == 0xFF, "64 KiB block blank");

    // BlockDevice
    check_true(flash.block_size() == 512 && flash.block_count() == 32768 && flash.erase_size() == 4096 && flash.needs_erase(), "BlockDevice geometry");
    uint32_t blk = SCRATCH / 512;
    for (int i = 0; i < 1024; i++) buf[i] = static_cast<uint8_t>(i * 5 + 2);
    check_true(flash.write(blk, buf, 2) == BlockError::Ok, "BlockDevice write 2 blocks");
    check_true(flash.read(blk, back, 2) == BlockError::Ok && memcmp(buf, back, 1024) == 0, "BlockDevice read back");
    uint8_t ones[512]; memset(ones, 0xFF, sizeof(ones));
    check_true(flash.write(blk, ones, 1) == BlockError::NotErased, "0->1 without erase -> NotErased");
    check_true(flash.erase(blk, 8) == BlockError::Ok, "BlockDevice erase (4 KiB)");
    check_true(flash.erase(blk + 1, 8) == BlockError::OutOfRange, "unaligned erase refused");
    check_true(flash.sync() == BlockError::Ok, "sync");

    // protection: top 2 x 64 KiB blocks are protected with BP = 1
    check_true(flash.set_protection(1) == BlockError::Ok, "set_protection(1)");
    uint8_t d = 0x00;
    check_true(flash.page_program(SCRATCH + 0x8000, &d, 1) == BlockError::WriteProtected, "program into protected area -> WriteProtected");
    check_true(flash.erase_sector(SCRATCH + 0x8000) == BlockError::WriteProtected, "erase of protected sector -> WriteProtected");
    check_true(flash.set_protection(0) == BlockError::Ok, "set_protection(0)");
    check_true(flash.page_program(SCRATCH + 0x8000, &d, 1) == BlockError::Ok, "program after unprotect");

    // power
    check_true(flash.deep_power_down() == BlockError::Ok, "deep_power_down");
    HAL_Delay(1);
    check_true(flash.release_deep_power_down() == BlockError::Ok && flash.electronic_id(&res) == BlockError::Ok && res == 0x17, "release_deep_power_down, RES again");

    // leave the scratch area blank
    flash.erase_block_64k(SCRATCH);
    flash.wait_ready(1000);
    finish();
}
