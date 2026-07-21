#pragma once
#include <stdint.h>
#include <stddef.h>
#include "../../transport/Transport.h"

/** @brief MFRC522 13.56 MHz contactless reader/writer — minimal interface.
 *
 *  Provides a 13.56 MHz RFID/NFC reader/writer frontend that detects an
 *  ISO/IEC 14443 Type A card in the field and reads its UID. No
 *  configuration beyond the transport is required.
 *
 *  Supports three host transports — I²C, SPI, and UART — all of which
 *  expose the same 64-register internal bank; the address-byte framing
 *  differs per transport. The driver selects the correct framing from a
 *  bus-type parameter.
 *
 *  Default configuration (baked in at construction):
 *      - 25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)
 *      - Force100ASK modulation
 *      - ISO/IEC 14443-3 CRC_A preset (0x6363)
 *      - Antenna enabled
 *      - 106 kBd, 33 dB RX gain (reset default)
 *
 *  @param transport Configured I²C, SPI, or UART transport bound to the device.
 *  @param bus_type  Bus type, one of BUS_SPI (default), BUS_I2C, BUS_UART.
 *                   SPI is the most common wiring.
 */
class MFRC522Minimal {
public:
    static constexpr uint8_t BUS_I2C  = 0;
    static constexpr uint8_t BUS_SPI  = 1;
    static constexpr uint8_t BUS_UART = 2;

    explicit MFRC522Minimal(Transport& transport, uint8_t bus_type = BUS_SPI);

    /** @brief Detect a card in the RF field.
     *
     *  Sends a REQA short frame. Returns true if a card answered with
     *  a valid 2-byte ATQA.
     *
     *  @return true if a card is in the field.
     */
    bool is_card_present();

    /** @brief Detect a card, run anticollision/Select (all cascade levels), and HLTA.
     *
     *  Returns the reassembled UID (4, 7, or 10 bytes). A card read this
     *  way is immediately halted, so the next call re-detects it from scratch.
     *
     *  @param out  Output buffer; must be at least 10 bytes.
     *  @param len  Output: number of UID bytes written (0 on failure).
     *  @return true on success.
     */
    bool read_uid(uint8_t* out, size_t& len);

protected:
    static constexpr uint8_t REG_COMMAND         = 0x01;
    static constexpr uint8_t REG_COM_I_EN        = 0x02;
    static constexpr uint8_t REG_DIV_I_EN        = 0x03;
    static constexpr uint8_t REG_COM_IRQ         = 0x04;
    static constexpr uint8_t REG_DIV_IRQ         = 0x05;
    static constexpr uint8_t REG_ERROR           = 0x06;
    static constexpr uint8_t REG_STATUS_1        = 0x07;
    static constexpr uint8_t REG_STATUS_2        = 0x08;
    static constexpr uint8_t REG_FIFO_DATA       = 0x09;
    static constexpr uint8_t REG_FIFO_LEVEL      = 0x0A;
    static constexpr uint8_t REG_WATER_LEVEL     = 0x0B;
    static constexpr uint8_t REG_CONTROL         = 0x0C;
    static constexpr uint8_t REG_BIT_FRAMING     = 0x0D;
    static constexpr uint8_t REG_COLL            = 0x0E;
    static constexpr uint8_t REG_MODE            = 0x11;
    static constexpr uint8_t REG_TX_MODE         = 0x12;
    static constexpr uint8_t REG_RX_MODE         = 0x13;
    static constexpr uint8_t REG_TX_CONTROL      = 0x14;
    static constexpr uint8_t REG_TX_ASK          = 0x15;
    static constexpr uint8_t REG_TX_SEL          = 0x16;
    static constexpr uint8_t REG_RX_SEL          = 0x17;
    static constexpr uint8_t REG_RX_THRESHOLD    = 0x18;
    static constexpr uint8_t REG_DEMOD           = 0x19;
    static constexpr uint8_t REG_MF_TX           = 0x1C;
    static constexpr uint8_t REG_MF_RX           = 0x1D;
    static constexpr uint8_t REG_SERIAL_SPEED    = 0x1F;
    static constexpr uint8_t REG_CRC_RESULT_H    = 0x21;
    static constexpr uint8_t REG_CRC_RESULT_L    = 0x22;
    static constexpr uint8_t REG_MOD_WIDTH       = 0x24;
    static constexpr uint8_t REG_RF_CFG          = 0x26;
    static constexpr uint8_t REG_GS_N            = 0x27;
    static constexpr uint8_t REG_CW_GS_P         = 0x28;
    static constexpr uint8_t REG_MOD_GS_P        = 0x29;
    static constexpr uint8_t REG_T_MODE          = 0x2A;
    static constexpr uint8_t REG_T_PRESCALER     = 0x2B;
    static constexpr uint8_t REG_T_RELOAD_H      = 0x2C;
    static constexpr uint8_t REG_T_RELOAD_L      = 0x2D;
    static constexpr uint8_t REG_TEST_SEL_1      = 0x31;
    static constexpr uint8_t REG_TEST_SEL_2      = 0x32;
    static constexpr uint8_t REG_TEST_PIN_EN     = 0x33;
    static constexpr uint8_t REG_TEST_PIN_VALUE  = 0x34;
    static constexpr uint8_t REG_TEST_BUS        = 0x35;
    static constexpr uint8_t REG_AUTO_TEST       = 0x36;
    static constexpr uint8_t REG_VERSION         = 0x37;
    static constexpr uint8_t REG_ANALOG_TEST     = 0x38;
    static constexpr uint8_t REG_TEST_DAC_1      = 0x39;
    static constexpr uint8_t REG_TEST_DAC_2      = 0x3A;
    static constexpr uint8_t REG_TEST_ADC        = 0x3B;

    static constexpr uint8_t CMD_IDLE            = 0x00;
    static constexpr uint8_t CMD_MEM             = 0x01;
    static constexpr uint8_t CMD_RANDOM_ID       = 0x02;
    static constexpr uint8_t CMD_CALC_CRC        = 0x03;
    static constexpr uint8_t CMD_TRANSMIT        = 0x04;
    static constexpr uint8_t CMD_NO_CMD_CHANGE   = 0x07;
    static constexpr uint8_t CMD_RECEIVE         = 0x08;
    static constexpr uint8_t CMD_TRANSCEIVE      = 0x0C;
    static constexpr uint8_t CMD_MFAUTHENT       = 0x0E;
    static constexpr uint8_t CMD_SOFT_RESET      = 0x0F;

    static constexpr uint8_t IRQ_RX              = 0x30;
    static constexpr uint8_t IRQ_IDLE            = 0x10;
    static constexpr uint8_t IRQ_TIMER           = 0x01;
    static constexpr uint8_t IRQ_ALL             = 0x7F;

    static constexpr uint8_t STATUS_2_CRYPTO1ON  = 0x08;

    static constexpr uint8_t FIFO_FLUSH          = 0x80;

    static constexpr uint8_t PICC_REQA           = 0x26;
    static constexpr uint8_t PICC_WUPA           = 0x52;
    static constexpr uint8_t PICC_HLTA           = 0x50;
    static constexpr uint8_t PICC_CT             = 0x88;
    static constexpr uint8_t PICC_ANTICOLL_CL1   = 0x93;
    static constexpr uint8_t PICC_ANTICOLL_CL2   = 0x95;
    static constexpr uint8_t PICC_ANTICOLL_CL3   = 0x97;
    static constexpr uint8_t PICC_SEL_BIT        = 0x70;
    static constexpr uint8_t PICC_SAK_NOT_COMPLETE = 0x04;

    Transport& _transport;
    uint8_t    _bus_type;

    uint8_t _addr_for(uint8_t reg, bool read) const;
    void    _write_reg(uint8_t reg, uint8_t value);
    uint8_t _read_reg(uint8_t reg);
    void    _set_bits(uint8_t reg, uint8_t mask);
    void    _clear_bits(uint8_t reg, uint8_t mask);
    void    _init_chip();

    void    _read_fifo(uint8_t* out, size_t n);
    void    _write_fifo(const uint8_t* data, size_t n);
    void    _flush_fifo();
    bool    _card_command(uint8_t command, uint8_t wait_irq, const uint8_t* send_data, size_t send_len);
    bool    _transceive(const uint8_t* send, size_t send_len,
                        uint8_t* back_data, size_t& back_len, uint8_t valid_bits = 0);
    bool    _select_card(uint8_t* out, size_t& len);
    bool    _anticollision(uint8_t cmd, uint8_t* ser_num);
    bool    _select(uint8_t cmd, const uint8_t* uid_part, uint8_t* sak);
    void    _halt_card();
    bool    _calc_crc(const uint8_t* data, size_t len, uint8_t out[2]);

    static void _delay_ms(unsigned long ms);
};

/** @brief MFRC522 full interface — extends minimal with configuration, antenna control,
 *         self test, MIFARE Classic authenticated operations, and MIFARE Ultralight
 *         / NTAG page read/write.
 */
class MFRC522Full : public MFRC522Minimal {
public:
    static constexpr uint8_t KEY_A = 0x60;
    static constexpr uint8_t KEY_B = 0x61;

    static constexpr uint8_t RX_GAIN_18_DB = 0x00;
    static constexpr uint8_t RX_GAIN_23_DB = 0x10;
    static constexpr uint8_t RX_GAIN_33_DB = 0x40;
    static constexpr uint8_t RX_GAIN_38_DB = 0x50;
    static constexpr uint8_t RX_GAIN_43_DB = 0x60;
    static constexpr uint8_t RX_GAIN_48_DB = 0x70;

    MFRC522Full(Transport& transport, uint8_t bus_type = BUS_SPI);

    /** @brief Re-run SoftReset and the full initialization sequence. */
    void reset();

    /** @brief Enable the antenna driver (TX1 + TX2). */
    void antenna_on();

    /** @brief Disable the antenna driver (TX1 + TX2). */
    void antenna_off();

    /** @brief Set the receiver gain.
     *  @param dB One of 18, 23, 33, 38, 43, or 48 dB.
     */
    void set_antenna_gain(uint8_t dB);

    /** @brief Read the currently configured receiver gain.
     *  @return Gain in dB (one of 18, 23, 33, 38, 43, 48).
     */
    uint8_t antenna_gain();

    /** @brief Read the version register and decode it.
     *  @param chip_type Output: chip type (0x09 for MFRC522).
     *  @param version   Output: software version (1 or 2).
     */
    void version(uint8_t& chip_type, uint8_t& version);

    /** @brief Run the datasheet-defined digital self test.
     *  @return true if all 64 FIFO bytes match the version-specific reference.
     */
    bool self_test();

    /** @brief WUPA — wake a HALTed card. Same as is_card_present but with WUPA. */
    bool wakeup_card();

    /** @brief Run anticollision / Select only — leaves the card active for further ops.
     *  @param out Output buffer; must be at least 10 bytes.
     *  @param len Output: number of UID bytes written (0 on failure).
     *  @return true on success.
     */
    bool select_card(uint8_t* out, size_t& len);

    /** @brief Send HLTA — put the currently selected card into HALT state. */
    void halt_card();

    /** @brief Run MIFARE Classic Crypto1 authentication.
     *  @param block_address Block number to authenticate against.
     *  @param key_type      KEY_A (0x60) or KEY_B (0x61).
     *  @param key           6-byte key.
     *  @param uid           4-byte UID of the card.
     *  @return true on success.
     */
    bool authenticate(uint8_t block_address, uint8_t key_type,
                      const uint8_t* key, const uint8_t* uid);

    /** @brief Clear Status2Reg.MFCrypto1On. */
    void stop_crypto();

    /** @brief Read a 16-byte MIFARE Classic block.
     *  @param block_address Block number.
     *  @param out           Output buffer; must be 16 bytes.
     *  @return true on success.
     */
    bool read_block(uint8_t block_address, uint8_t* out);

    /** @brief Write a 16-byte MIFARE Classic block.
     *  @param block_address Block number.
     *  @param data          16 bytes to write.
     *  @return true on success.
     */
    bool write_block(uint8_t block_address, const uint8_t* data);

    /** @brief Increment the value block at block_address by delta and transfer it back.
     *  @param block_address Source value block.
     *  @param delta         Unsigned 32-bit increment.
     *  @return true on success.
     */
    bool increment_value(uint8_t block_address, uint32_t delta);

    /** @brief Decrement the value block at block_address by delta and transfer it back.
     *  @param block_address Source value block.
     *  @param delta         Unsigned 32-bit decrement.
     *  @return true on success.
     */
    bool decrement_value(uint8_t block_address, uint32_t delta);

    /** @brief Restore (re-read) the value block at block_address into the internal data register.
     *  @param block_address Value block to restore.
     *  @return true on success.
     */
    bool restore_value(uint8_t block_address);

    /** @brief Commit the internal data register to destination_block.
     *  @param destination_block Block to write the data register to.
     *  @return true on success.
     */
    bool transfer_value(uint8_t destination_block);

    /** @brief Read 4 consecutive pages (16 bytes) starting at page_address.
     *  @param page_address Page number (0-based).
     *  @param out           Output buffer; must be 16 bytes.
     *  @return true on success.
     */
    bool read_ultralight_page(uint8_t page_address, uint8_t* out);

    /** @brief Write a 4-byte page (MIFARE Ultralight / NTAG).
     *  @param page_address Page number.
     *  @param data          4 bytes to write.
     *  @return true on success.
     */
    bool write_ultralight_page(uint8_t page_address, const uint8_t* data);

    /** @brief Run the Generate RandomID command and return the 10-byte ID.
     *  @param out Output buffer; must be 10 bytes.
     */
    void generate_random_id(uint8_t* out);

private:
    bool _value_op(uint8_t cmd, uint8_t block_address, uint32_t delta, bool dummy);
    bool _transfer(uint8_t block_address);
};
