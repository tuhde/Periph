#include <stdio.h>
#include <stdarg.h>
#include <stdint.h>
#include <string.h>
#include <stm32f4xx_hal.h>
#include "system_clock_config.h"
#include "I2CConnectionSTM32Cube.h"
#include "St25dv.h"

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


// Converts "\n" to "\r\n" for the ST-LINK virtual COM port.
static void out(const char* fmt, ...) {
    char text[160];
    char line[320];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(text, sizeof(text), fmt, ap);
    va_end(ap);
    size_t j = 0;
    for (size_t i = 0; text[i] && j < sizeof(line) - 2; i++) {
        if (text[i] == '\n') line[j++] = '\r';
        line[j++] = text[i];
    }
    HAL_UART_Transmit(&huart2, reinterpret_cast<uint8_t*>(line), static_cast<uint16_t>(j), HAL_MAX_DELAY);
}
static void pause_ms(unsigned ms) { HAL_Delay(ms); }

static void print_hex(const char* label, const uint8_t* d, size_t n) {
    out("%s", label);
    for (size_t i = 0; i < n; i++) out("%02X", d[i]);
    out("\n");
}

static uint8_t buf[256];

static void on_event(uint8_t status) {
    out("GPO event 0x%02X\n", status);
}

static void run(ST25DVFull& tag) {
    tag.init();                                                         // Read MEM_SIZE, BLK_SIZE, IC_REF, () → bool
                                                                        // derives the user memory size from the system area
    out("size %lu bytes\n", (unsigned long)tag.memory_size());          // Get user memory size, () → bytes
                                                                        // (MEM_SIZE + 1) × (BLK_SIZE + 1)
    out("IC_REF 0x%02X\n", tag.ic_reference());                         // Get IC reference, () → uint8_t
                                                                        // 0x24 = ST25DV04K, 0x26 = ST25DV16K/64K
    uint8_t uid[8];
    tag.uid(uid);                                                       // Read unique ID, (out[8]) → bool
                                                                        // most significant byte first, starts with E0 02
    print_hex("UID ", uid, 8);
    out("IC_REV %u\n", tag.ic_revision());                              // Read IC revision, () → uint8_t
                                                                        // IC_REV register 0x0020

    for (int i = 0; i < 40; i++) buf[i] = (uint8_t)i;
    tag.write(0x0100, buf, 40);                                         // Write user memory, (address, data, length) → bool
                                                                        // split into 16-byte-aligned writes, ACK-polled each
    tag.read(0x0100, buf, 40);                                          // Read user memory, (address, buf, length) → bool
                                                                        // 16-byte-aligned reads, retried while an RF reader holds the bus
    print_hex("data ", buf, 40);
    tag.wait_write_complete();                                          // Wait for the write cycle, () → bool
                                                                        // ACK polling on 0x53

    uint8_t it_time = 0;
    tag.read_system(0x0001, &it_time, 1);                               // Read system area, (address, buf, length) → bool
                                                                        // raw access to the static registers at 0x57
    out("IT_TIME %u\n", it_time);
    out("session %u\n", tag.session_open());                            // Query I²C security session, () → bool
                                                                        // I2C_SSO_Dyn bit 0
    static const uint8_t pwd[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    out("password ok %u\n", tag.present_password(pwd));                 // Present I²C password, (password[8]) → bool
                                                                        // opens the session needed for system-area writes
    tag.write_password(pwd);                                            // Change I²C password, (password[8]) → bool
                                                                        // here rewritten with the factory value
    tag.write_system(0x0001, (uint8_t)0x03);                            // Write system area, (address, value) → bool
                                                                        // IT_TIME back to its reset value, ACK-polled

    tag.set_area_end(1, (uint8_t)(tag.memory_size() / 32 - 1));         // Set end of area 1-3, (area 1–3, end_block 0–255) → bool
                                                                        // area n ends at byte end_block × 32 + 31
    tag.set_area_protection(1, 0x00, 0x00);                             // Set area protection, (area 1–4, rf 0–15, i2c 0–3) → bool
                                                                        // RFAnSS value and the area's I2CSS bits
    uint8_t rf = 0, i2c = 0;
    tag.get_area_protection(1, rf, i2c);                                // Get area protection, (area 1–4, rf&, i2c&) → bool
                                                                        // RFAnSS & 0x0F and the I2CSS level
    out("area 1 rf %u i2c %u\n", rf, i2c);

    tag.set_gpo(ST25DVFull::GPO_FIELD_CHANGE);                          // Write static GPO register, (sources, enable=true) → bool
                                                                        // the chip copies it into GPO_CTRL_Dyn
    out("GPO 0x%02X\n", tag.get_gpo());                                 // Read static GPO register, () → uint8_t
                                                                        // GPO_* bit mask
    tag.set_gpo_runtime(true);                                          // Switch GPO output at runtime, (enable) → bool
                                                                        // GPO_CTRL_Dyn.GPO_EN, no session needed
    tag.set_interrupt_pulse(3);                                         // Set GPO pulse duration, (value 0–7) → bool
                                                                        // 301 µs − value × 37.65 µs

    out("field %u\n", tag.field_present());                             // Query RF field, () → bool
                                                                        // EH_CTRL_Dyn.FIELD_ON
    tag.set_energy_harvesting(false);                                   // Control energy harvesting, (enabled, forced=false) → bool
                                                                        // EH_EN now, EH_MODE written only when it changes
    tag.set_rf_state();                                                 // Set RF interface state, (disable=false, sleep=false) → bool
                                                                        // runtime RF_MNGT_Dyn

    tag.enable_mailbox();                                               // Enable fast transfer mode, () → bool
                                                                        // sets MB_MODE if needed, then MB_CTRL_Dyn.MB_EN
    tag.set_mailbox_watchdog(0);                                        // Set mailbox watchdog, (value 0–7) → bool
                                                                        // 2^(value−1) × 30 ms, 0 disables it
    static const uint8_t ping[] = {'p', 'i', 'n', 'g'};
    tag.mailbox_write(ping, sizeof(ping));                              // Put mailbox message, (data, length 1–256) → bool
                                                                        // single write starting at 0x2008
    ST25DVMailboxStatus st = tag.mailbox_status();                      // Decode mailbox status, () → ST25DVMailboxStatus
                                                                        // enabled, host_put, rf_put, host_miss, rf_miss, …
    out("mailbox enabled %u host_put %u rf_put %u\n", st.enabled, st.host_put, st.rf_put);
    size_t n = tag.mailbox_read(buf, sizeof(buf));                      // Read mailbox message, (buf, capacity) → size_t bytes
                                                                        // MB_LEN_Dyn + 1 bytes, 0 if no message
    print_hex("message ", buf, n);
    tag.disable_mailbox();                                              // Disable fast transfer mode, () → bool
                                                                        // clears MB_EN, which also drops the message

    static const uint8_t record[] = {0xD1, 0x01, 0x05, 0x54, 0x02, 0x65, 0x6E, 0x48, 0x69, 0x21};
    tag.write_ndef(record, sizeof(record));                             // Write NDEF message, (message, length) → bool
                                                                        // capability container + TLV 03 <len> … FE
    n = tag.read_ndef(buf, sizeof(buf));                                // Read NDEF message, (buf, capacity) → size_t bytes
                                                                        // parses the CC and the first NDEF TLV
    print_hex("NDEF ", buf, n);
    tag.lock_ccfile(false);                                             // Lock CC file blocks, (lock=true) → bool
                                                                        // false keeps blocks 0/1 RF-writable

    tag.enable_interrupt(ST25DVSource::FIELD_RISING | ST25DVSource::RF_WRITE); // Enable interrupt sources, (source) → bool
                                                                        // static GPO register bits plus GPO_EN
    tag.on_interrupt(on_event);                                         // Subscribe to GPO events, (callback, intPin=nullptr) → void
                                                                        // needs a GPO pin on the connection; otherwise poll
    pause_ms(100);
    out("pending 0x%02X\n", tag.poll_interrupt());                      // Read and clear IT_STS_Dyn, () → uint8_t
                                                                        // ST25DVSource mask, 0 = none
    tag.off_interrupt();                                                // Unsubscribe, () → void
                                                                        // removes the edge handler
    tag.disable_interrupt(ST25DVSource::RF_WRITE);                      // Disable interrupt sources, (source) → bool
                                                                        // clears the matching GPO enable bits
}

int main(void) {
    HAL_Init();
    SystemClock_Config();
    gpio_clocks_init();
    uart2_init();
    i2c1_init();
    I2CConnectionSTM32Cube user_conn(&hi2c1, 0x53, nullptr, nullptr, 2);           // Create user-area connection, (hi2c, addr=0x53, int=null, en=null, reg_bytes=2)
    I2CConnectionSTM32Cube system_conn(&hi2c1, 0x57, nullptr, nullptr, 2);         // Create system-area connection, (hi2c, addr=0x57, int=null, en=null, reg_bytes=2)
    ST25DVFull tag(user_conn, system_conn);                                       // Create ST25DV driver, (user, system, gpo_active_high=false)
    run(tag);
    while (true) pause_ms(1000);
}
