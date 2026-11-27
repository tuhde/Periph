#include <stdio.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "Pn532.h"

static UART_HandleTypeDef huart2;
static I2C_HandleTypeDef hi2c1;

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

static void i2c1_init(void) {
    __HAL_RCC_I2C1_CLK_ENABLE();

    GPIO_InitTypeDef gpioInit = {};
    gpioInit.Pin       = GPIO_PIN_8 | GPIO_PIN_9;  // PB8=SCL, PB9=SDA
    gpioInit.Mode      = GPIO_MODE_AF_OD;
    gpioInit.Pull      = GPIO_PULLUP;               // internal pull-up, in case the breakout has none
    gpioInit.Speed     = GPIO_SPEED_FREQ_HIGH;
    gpioInit.Alternate = GPIO_AF4_I2C1;
    HAL_GPIO_Init(GPIOB, &gpioInit);

    hi2c1.Instance             = I2C1;
    hi2c1.Init.ClockSpeed      = 100000;
    hi2c1.Init.DutyCycle       = I2C_DUTYCYCLE_2;
    hi2c1.Init.OwnAddress1     = 0;
    hi2c1.Init.AddressingMode  = I2C_ADDRESSINGMODE_7BIT;
    hi2c1.Init.DualAddressMode = I2C_DUALADDRESS_DISABLE;
    hi2c1.Init.OwnAddress2     = 0;
    hi2c1.Init.GeneralCallMode = I2C_GENERALCALL_DISABLE;
    hi2c1.Init.NoStretchMode   = I2C_NOSTRETCH_DISABLE;
    HAL_I2C_Init(&hi2c1);
}

static void print_hex(const uint8_t* data, size_t len) {
    for (size_t i = 0; i < len; i++) printf("%02X", data[i]);
}

static volatile bool g_frame_ready = false;
static void on_frame() { g_frame_ready = true; }

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube connection(&hi2c1, 0x24);
    PN532Full reader(connection);                                  // Create PN532 driver, (connection) → void
    // The PN532 address is fixed at 0x24; I0 low and I1 high select the I²C interface.
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool
    // GetFirmwareVersion (IC 0x32), SAMConfiguration normal mode, MxRtyPassiveActivation = 2.

    PN532FirmwareVersion fw;
    reader.firmware_version(fw);                                   // GetFirmwareVersion, (fw&) → bool
    // IC 0x32, firmware 1.6 on current parts; the support bits list the protocols.
    printf("IC 0x%02X firmware %u.%u support 0x%02X\r\n", fw.ic, fw.version, fw.revision, fw.support);
    PN532GeneralStatus gs;
    reader.general_status(gs);                                     // GetGeneralStatus, (status&) → bool
    // Last error code, external field detected, active targets, SAM status.
    printf("error 0x%02X field %u targets %u SAM 0x%02X\r\n", gs.error, (unsigned)gs.field, gs.targets, gs.sam_status);
    printf("self test %u\r\n", (unsigned)reader.self_test());       // Diagnose, (test=0x00) → bool
    // Communication line test: the chip echoes an 8-byte pattern.
    printf("ROM test %u\r\n", (unsigned)reader.self_test(0x01));    // Diagnose, (test=0x01) → bool
    // ROM checksum test.

    uint8_t uid[10];
    uint8_t len = reader.read_uid(uid);                            // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
    // InListPassiveTarget at 106 kbps type A, then InRelease.
    printf("UID ");
    print_hex(uid, len);
    printf("\r\n");

    PN532Target t[1];
    int n = reader.list_passive_targets(t, PN532Full::BAUD_106_A, 1); // InListPassiveTarget, (targets*, baud=BAUD_106_A, maxTargets=1, initiatorData=nullptr, initLen=0, timeoutMs=1000) → int count
    // Keeps the target active; t[0].tg is the logical number for the next commands.
    if (n > 0) {
        printf("tg %u SENS_RES 0x%04X SEL_RES 0x%02X\r\n", t[0].tg, t[0].sens_res, t[0].sel_res);
        reader.deselect(t[0].tg);                                  // InDeselect, (target=0) → bool
        // Puts the card to sleep but keeps its information in the PN532.
        reader.select(t[0].tg);                                    // InSelect, (target=0) → bool
        // Wakes and reselects the deselected card.
        uint8_t block[16];
        if (t[0].uid_len == 7) {
            reader.ntag_read_page(t[0].tg, 4, block);              // NTAG READ, (target, page, out[16]) → bool
            // Returns pages 4..7 (16 bytes).
            const uint8_t page[4] = {0x01, 0x02, 0x03, 0x04};
            reader.ntag_write_page(t[0].tg, 4, page);              // NTAG WRITE, (target, page, data[4]) → bool
            // Writes one 4-byte user page.
        } else {
            const uint8_t key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
            reader.mifare_authenticate(t[0].tg, 4, key, t[0].uid, t[0].uid_len); // MIFARE auth, (target, block, key[6], uid*, uidLen, keyB=false) → bool
            // Key A authentication of sector 1 with the transport key.
            reader.mifare_read_block(t[0].tg, 4, block);           // MIFARE READ, (target, block, out[16]) → bool
            // Reads block 4.
            reader.mifare_write_block(t[0].tg, 4, block);          // MIFARE WRITE, (target, block, data[16]) → bool
            // Writes the same 16 bytes back.
        }
        const uint8_t rd[2] = {0x30, 0x00};
        int m = reader.data_exchange(t[0].tg, rd, 2, block, sizeof(block)); // InDataExchange, (target, data*, len, out*, outMax) → int length
        // Raw READ of block/page 0 with PN532 framing and CRC.
        printf("data_exchange %d\r\n", m);
        reader.release(t[0].tg);                                   // InRelease, (target=0) → bool
        // Ends the session with the card.
    }

    reader.set_rf_field(false);                                    // RFConfiguration item 1, (on, autoRfca=false) → bool
    // RF field off; a card in the field loses power.
    reader.set_rf_field(true);                                     // RFConfiguration item 1, (on, autoRfca=false) → bool
    // RF field back on.
    reader.set_max_retries(2);                                     // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → bool
    // 0xFF would retry forever.
    uint8_t txmode = 0;
    reader.read_register(0x6302, txmode);                          // ReadRegister, (address, value&) → bool
    // 0x6302 is CIU_TxMode in the PN532's SFR space.
    reader.write_register(0x6302, txmode);                         // WriteRegister, (address, value) → bool
    // Writes the value back unchanged.
    uint8_t resp[8];
    n = reader.send_command(0x02, nullptr, 0, resp, sizeof(resp)); // Raw command, (command, data*, len, out*, outMax, timeoutMs=1000) → int length
    // GetFirmwareVersion through the raw frame interface.
    printf("raw response %d bytes\r\n", n);
    const uint8_t reqa[1] = {0x26};
    n = reader.communicate_thru(reqa, 1, resp, sizeof(resp));      // InCommunicateThru, (data*, len, out*, outMax) → int length
    // Raw RF exchange with the current target; fails (-1, last_error()) without one.
    printf("communicate_thru %d (error %d)\r\n", n, reader.last_error());

    reader.start_command(0x02);                                    // Command without waiting, (command, data=nullptr, len=0) → bool
    // Writes the frame and reads the ACK only.
    printf("ready %u\r\n", (unsigned)reader.wait_ready(1000));      // Wait for a frame, (timeoutMs) → bool
    // Polls the status byte every 1 ms.
    printf("poll %u\r\n", (unsigned)reader.poll_interrupt());       // Frame ready?, () → bool
    // A single status-byte read.
    n = reader.read_response(0x02, resp, sizeof(resp));            // Read the pending response, (command, out*, outMax, timeoutMs=1000) → int length
    // Header, NACK, full frame, checksums.

    reader.on_interrupt(on_frame);                                 // Subscribe to IRQ, (callback, intPin=nullptr) → void
    // Falling edge on the connection's IRQ pin; without a pin nothing is delivered.
    reader.send_command(0x02, nullptr, 0, resp, sizeof(resp));     // Raw command, (command, data*, len, out*, outMax, timeoutMs=1000) → int length
    printf("irq seen %u\r\n", (unsigned)g_frame_ready);
    reader.off_interrupt();                                        // Unsubscribe, () → void
    // Removes the edge handler.

    reader.power_down();                                           // PowerDown, (wakeupSources=0x20) → bool
    // Sleeps until the next I²C access; the driver retries the wake-up write.
    while (1) {}
}
