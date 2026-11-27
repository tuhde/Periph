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
    reader.init();                                                 // Wake, check firmware, SAM normal mode, finite retries, () → bool

    // --- Identity and health ---
    // IC 0x32 is the PN532; the support byte lists ISO 14443 A/B and ISO 18092.
    // The communication-line self test echoes a pattern through the firmware.
    PN532FirmwareVersion fw;
    reader.firmware_version(fw);                                   // GetFirmwareVersion, (fw&) → bool
    printf("PN532 IC 0x%02X firmware %u.%u support 0x%02X\r\n", fw.ic, fw.version, fw.revision, fw.support);
    PN532GeneralStatus gs;
    reader.general_status(gs);                                     // GetGeneralStatus, (status&) → bool
    printf("field %u targets %u\r\n", (unsigned)gs.field, gs.targets);
    printf("self test %u\r\n", (unsigned)reader.self_test());       // Diagnose, (test=0x00) → bool

    // --- Card polling: 15 s at 1 Hz ---
    // Present a card or tag. The finite retry count makes every poll return
    // after a few milliseconds when the field is empty.
    PN532Target t[1];
    for (int i = 0; i < 15; i++) {
        int n = reader.list_passive_targets(t);                    // InListPassiveTarget, (targets*, baud=BAUD_106_A, maxTargets=1, ...) → int count
        if (n > 0) {
            printf("%2d s UID ", i);
            print_hex(t[0].uid, t[0].uid_len);
            printf(" SENS_RES 0x%04X SEL_RES 0x%02X\r\n", t[0].sens_res, t[0].sel_res);
            reader.release(t[0].tg);                               // InRelease, (target=0) → bool
        } else {
            printf("%2d s no card\r\n", i);
        }
        HAL_Delay(1000);
    }

    // --- Tag access ---
    // SEL_RES 0x00 is an NTAG / MIFARE Ultralight: read page 4 and write it back.
    // SEL_RES 0x08 is a MIFARE Classic 1K: authenticate sector 1 with the
    // transport key FF FF FF FF FF FF, read block 4 and write it back.
    // Writing sector trailers can lock a sector forever.
    if (reader.list_passive_targets(t) > 0) {                      // InListPassiveTarget, (targets*, ...) → int count
        uint8_t data[16];
        reader.deselect(t[0].tg);                                  // InDeselect, (target=0) → bool
        reader.select(t[0].tg);                                    // InSelect, (target=0) → bool
        if (t[0].sel_res == 0x00) {
            if (reader.ntag_read_page(t[0].tg, 4, data)) {         // NTAG READ, (target, page, out[16]) → bool
                printf("pages 4..7 ");
                print_hex(data, 16);
                printf("\r\n");
                reader.ntag_write_page(t[0].tg, 4, data);          // NTAG WRITE, (target, page, data[4]) → bool
            }
            const uint8_t rd[2] = {0x30, 0x00};
            int m = reader.data_exchange(t[0].tg, rd, 2, data, sizeof(data)); // InDataExchange, (target, data*, len, out*, outMax) → int length
            printf("raw read %d bytes\r\n", m);
        } else if (t[0].sel_res & 0x08) {
            const uint8_t key[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
            if (reader.mifare_authenticate(t[0].tg, 4, key, t[0].uid, t[0].uid_len) && // MIFARE auth, (target, block, key[6], uid*, uidLen, keyB=false) → bool
                reader.mifare_read_block(t[0].tg, 4, data)) {      // MIFARE READ, (target, block, out[16]) → bool
                printf("block 4 ");
                print_hex(data, 16);
                printf("\r\n");
                reader.mifare_write_block(t[0].tg, 4, data);       // MIFARE WRITE, (target, block, data[16]) → bool
            } else {
                printf("authentication failed (error %d)\r\n", reader.last_error());
            }
        }
        reader.release(t[0].tg);                                   // InRelease, (target=0) → bool
    } else {
        printf("no card for the tag access part\r\n");
    }

    // --- Raw RF and raw commands ---
    // InCommunicateThru sends bytes without PN532 protocol handling; with no
    // target selected it reports an error. send_command reaches every command.
    uint8_t resp[16];
    const uint8_t rd0[2] = {0x30, 0x00};
    int n = reader.communicate_thru(rd0, 2, resp, sizeof(resp));   // InCommunicateThru, (data*, len, out*, outMax) → int length
    printf("communicate_thru %d (error %d)\r\n", n, reader.last_error());
    n = reader.send_command(0x02, nullptr, 0, resp, sizeof(resp)); // Raw command, (command, data*, len, out*, outMax, timeoutMs=1000) → int length
    printf("raw GetFirmwareVersion ");
    if (n > 0) print_hex(resp, (size_t)n);
    printf("\r\n");
    uint8_t txmode = 0;
    reader.read_register(0x6302, txmode);                          // ReadRegister, (address, value&) → bool
    printf("CIU_TxMode 0x%02X\r\n", txmode);
    reader.write_register(0x6302, txmode);                         // WriteRegister, (address, value) → bool

    // --- Wake on card with on_interrupt ---
    // Infinite activation retries make InListPassiveTarget wait for a card; the
    // IRQ pin (when wired to the connection) or the status byte signals the response.
    reader.set_max_retries(0xFF);                                  // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → bool
    g_frame_ready = false;
    reader.on_interrupt(on_frame);                                 // Subscribe to IRQ, (callback, intPin=nullptr) → void
    const uint8_t list[2] = {0x01, 0x00};
    reader.start_command(0x4A, list, 2);                           // Command without waiting, (command, data*, len) → bool
    printf("waiting 10 s for a card ...\r\n");
    for (int i = 0; i < 100 && !g_frame_ready; i++) {
        if (reader.poll_interrupt()) break;                        // Frame ready?, () → bool
        HAL_Delay(100);
    }
    reader.off_interrupt();                                        // Unsubscribe, () → void
    if (reader.wait_ready(10)) {                                   // Wait for a frame, (timeoutMs) → bool
        n = reader.read_response(0x4A, resp, sizeof(resp));        // Read the pending response, (command, out*, outMax, timeoutMs=1000) → int length
        if (n >= 6) {
            printf("card arrived, UID ");
            print_hex(resp + 6, resp[5]);
            printf("\r\n");
        }
        reader.release();                                          // InRelease, (target=0) → bool
    } else {
        reader.read_response(0x4A, resp, sizeof(resp), 0);         // Read the pending response, (command, out*, outMax, timeoutMs) → int length
        printf("no card arrived\r\n");                               // the timeout aborted the command with an ACK frame
    }
    reader.set_max_retries();                                      // RFConfiguration item 5, (passiveActivation=2, atr=0xFF, psl=1) → bool
    uint8_t uid[10];
    uint8_t len = reader.read_uid(uid);                            // Detect a type A card, (uid*, timeoutMs=1000) → uint8_t length
    printf("read_uid %u bytes\r\n", len);

    // --- RF field off and on, then power down ---
    // With the field off a card loses power; the reader draws less current.
    reader.set_rf_field(false);                                    // RFConfiguration item 1, (on, autoRfca=false) → bool
    HAL_Delay(1000);
    reader.set_rf_field(true);                                     // RFConfiguration item 1, (on, autoRfca=false) → bool
    reader.power_down();                                           // PowerDown, (wakeupSources=0x20) → bool
    printf("powered down; the next I2C access wakes the chip\r\n");
    while (1) {}
}
