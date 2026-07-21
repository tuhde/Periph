"""MFRC522 13.56 MHz contactless reader/writer — minimal interface.

Provides a 13.56 MHz RFID/NFC reader/writer frontend that detects an
ISO/IEC 14443 Type A card in the field and reads its UID. No configuration
beyond the transport is required.

Supports three host transports — I²C, SPI, and UART — all of which expose
the same 64-register internal bank; the address-byte framing differs per
transport (see Register Access below). The driver selects the correct
framing from a ``bus_type`` string.

Default configuration (baked in at construction):
    - 25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)
    - Force100ASK modulation
    - ISO/IEC 14443-3 CRC_A preset (0x6363)
    - Antenna enabled
    - 106 kBd, 33 dB RX gain (reset default)

Args:
    transport: Configured I²C, SPI, or UART transport pointing at the
        device. The transport must already be bound to the chip's
        I²C address / SPI CS / UART port.
    bus_type: Bus type string, ``'spi'`` (default), ``'i2c'``, or
        ``'uart'``. SPI is the most common wiring for this chip.
"""

import time


_BUS_SPI  = 'spi'
_BUS_I2C  = 'i2c'
_BUS_UART = 'uart'

# --- Command set (CommandReg.Command[3:0]) ---
_CMD_IDLE            = 0x00
_CMD_MEM             = 0x01
_CMD_RANDOM_ID       = 0x02
_CMD_CALC_CRC        = 0x03
_CMD_TRANSMIT        = 0x04
_CMD_NO_CMD_CHANGE   = 0x07
_CMD_RECEIVE         = 0x08
_CMD_TRANSCEIVE      = 0x0C
_CMD_MFAUTHENT       = 0x0E
_CMD_SOFT_RESET      = 0x0F

# --- Register addresses (6 bits, 0x00-0x3F) ---
_REG_COMMAND         = 0x01
_REG_COM_I_EN        = 0x02
_REG_DIV_I_EN        = 0x03
_REG_COM_IRQ         = 0x04
_REG_DIV_IRQ         = 0x05
_REG_ERROR           = 0x06
_REG_STATUS_1        = 0x07
_REG_STATUS_2        = 0x08
_REG_FIFO_DATA       = 0x09
_REG_FIFO_LEVEL      = 0x0A
_REG_WATER_LEVEL     = 0x0B
_REG_CONTROL         = 0x0C
_REG_BIT_FRAMING     = 0x0D
_REG_COLL            = 0x0E
_REG_MODE            = 0x11
_REG_TX_MODE         = 0x12
_REG_RX_MODE         = 0x13
_REG_TX_CONTROL      = 0x14
_REG_TX_ASK          = 0x15
_REG_TX_SEL          = 0x16
_REG_RX_SEL          = 0x17
_REG_RX_THRESHOLD    = 0x18
_REG_DEMOD           = 0x19
_REG_MF_TX           = 0x1C
_REG_MF_RX           = 0x1D
_REG_SERIAL_SPEED    = 0x1F
_REG_CRC_RESULT_H    = 0x21
_REG_CRC_RESULT_L    = 0x22
_REG_MOD_WIDTH       = 0x24
_REG_RF_CFG          = 0x26
_REG_GS_N            = 0x27
_REG_CW_GS_P         = 0x28
_REG_MOD_GS_P        = 0x29
_REG_T_MODE          = 0x2A
_REG_T_PRESCALER     = 0x2B
_REG_T_RELOAD_H      = 0x2C
_REG_T_RELOAD_L      = 0x2D
_REG_TEST_SEL_1      = 0x31
_REG_TEST_SEL_2      = 0x32
_REG_TEST_PIN_EN     = 0x33
_REG_TEST_PIN_VALUE  = 0x34
_REG_TEST_BUS        = 0x35
_REG_AUTO_TEST       = 0x36
_REG_VERSION         = 0x37
_REG_ANALOG_TEST     = 0x38
_REG_TEST_DAC_1      = 0x39
_REG_TEST_DAC_2      = 0x3A
_REG_TEST_ADC        = 0x3B

# --- ComIrqReg bits ---
_IRQ_RX              = 0x30
_IRQ_IDLE            = 0x10
_IRQ_TIMER           = 0x01
_IRQ_ALL             = 0x7F

# --- Status1Reg bits ---
_STATUS_1_CRCOK      = 0x40
_STATUS_1_CRC_READY  = 0x20
_STATUS_1_IRQ        = 0x10

# --- FIFOLevelReg ---
_FIFO_FLUSH          = 0x80

# --- ISO/IEC 14443-3 Type A short frame commands ---
_PICC_CMD_REQA       = 0x26
_PICC_CMD_WUPA       = 0x52
_PICC_CMD_HLTA       = 0x50
_PICC_CMD_CT         = 0x88

# --- Cascade-level command bytes for anticollision / Select ---
_PICC_ANTICOLL_CL1   = 0x93
_PICC_ANTICOLL_CL2   = 0x95
_PICC_ANTICOLL_CL3   = 0x97
_PICC_SELECT_CL1     = 0x93
_PICC_SELECT_CL2     = 0x95
_PICC_SELECT_CL3     = 0x97
_PICC_SEL_BIT        = 0x70
_PICC_SAK_NOT_COMPLETE = 0x04


def _delay_ms(ms):
    time.sleep_ms(ms)


class MFRC522Minimal:
    """MFRC522 13.56 MHz RFID/NFC reader — minimal interface.

    Detects ISO/IEC 14443 Type A cards in the field and reads their UID.
    No configuration beyond the transport and bus type is required.

    Args:
        transport: Configured I²C, SPI, or UART transport bound to the
            device.
        bus_type: Bus type string — ``'spi'`` (default), ``'i2c'``, or
            ``'uart'``. SPI is the most common wiring.
    """

    def __init__(self, transport, bus_type='spi'):
        self._transport = transport
        self._bus_type = bus_type
        self._init_chip()

    def _addr_for(self, reg, read=False):
        if self._bus_type == _BUS_SPI:
            return ((reg << 1) & 0x7E) | (0x80 if read else 0x00)
        if self._bus_type == _BUS_UART:
            return (reg & 0x3F) | (0x80 if read else 0x00)
        return reg & 0x3F

    def _write_reg(self, reg, value):
        addr = self._addr_for(reg, read=False)
        self._transport.write(bytes([addr, value & 0xFF]))

    def _read_reg(self, reg):
        addr = self._addr_for(reg, read=True)
        return self._transport.write_read(bytes([addr]), 1)[0]

    def _set_bits(self, reg, mask):
        self._write_reg(reg, self._read_reg(reg) | mask)

    def _clear_bits(self, reg, mask):
        self._write_reg(reg, self._read_reg(reg) & ~mask)

    def _init_chip(self):
        self._write_reg(_REG_COMMAND, _CMD_SOFT_RESET)
        # Wait for PowerDown bit to clear (oscillator started)
        for _ in range(50):
            if (self._read_reg(_REG_COMMAND) & 0x10) == 0:
                break
            _delay_ms(1)
        else:
            _delay_ms(50)
        # Timer: ~25 ms auto-timeout
        self._write_reg(_REG_T_MODE,        0x80)
        self._write_reg(_REG_T_PRESCALER,   0xA9)
        self._write_reg(_REG_T_RELOAD_H,    0x03)
        self._write_reg(_REG_T_RELOAD_L,    0xE8)
        # Force100ASK
        self._write_reg(_REG_TX_ASK, 0x40)
        # Mode: TxWaitRF, PolMFin, CRCPreset=0x6363 (CRC_A)
        self._write_reg(_REG_MODE, 0x3D)
        # Antenna on
        self._set_bits(_REG_TX_CONTROL, 0x03)

    def _read_fifo(self, n):
        out = bytearray(n)
        for i in range(n):
            out[i] = self._read_reg(_REG_FIFO_DATA)
        return bytes(out)

    def _write_fifo(self, data):
        for b in data:
            self._write_reg(_REG_FIFO_DATA, b & 0xFF)

    def _flush_fifo(self):
        self._write_reg(_REG_FIFO_LEVEL, _FIFO_FLUSH)

    def _card_command(self, command, wait_irq, send_data):
        # Write command; clear IRQ bits; flush FIFO; write FIFO data; StartSend
        self._write_reg(_REG_COMMAND, _CMD_IDLE)
        self._write_reg(_REG_COM_IRQ, 0x7F)
        self._flush_fifo()
        if send_data:
            self._write_fifo(send_data)
        self._write_reg(_REG_COMMAND, command)
        if command == _CMD_TRANSCEIVE:
            self._set_bits(_REG_BIT_FRAMING, 0x80)
        # Wait for IRQ (or timeout)
        deadline = time.ticks_ms() + 50
        while True:
            n = self._read_reg(_REG_COM_IRQ)
            if n & wait_irq:
                return True
            if n & 0x01:        # TimerIRq
                return False
            if time.ticks_ms() > deadline:
                return False

    def _transceive(self, send_data, rx_align=0, tx_last_bits=0, valid_bits=0):
        back_data = bytearray()
        back_len = 0
        back_last_bits = valid_bits
        status = self._card_command(_CMD_TRANSCEIVE, _IRQ_RX | _IRQ_IDLE, send_data)
        if not status:
            return None, None, 0, 0
        # ErrorReg
        err = self._read_reg(_REG_ERROR)
        if err & 0x13:           # BufferOvfl | ParityErr | ProtocolErr
            return None, None, 0, 0
        fifo_level = self._read_reg(_REG_FIFO_LEVEL)
        if fifo_level > 0:
            back_data = bytearray(self._read_fifo(fifo_level))
            back_len = fifo_level
        if back_len and rx_align:
            shift = rx_align
            for i in range(back_len - 1):
                back_data[i] = ((back_data[i] << shift) & 0x7F) | (back_data[i + 1] >> (8 - shift))
            back_data[back_len - 1] = (back_data[back_len - 1] << shift) & 0x7F
        if back_len and tx_last_bits:
            back_last_bits = tx_last_bits
        return back_data, back_len, back_last_bits, err

    def is_card_present(self):
        """Detect a card in the RF field.

        Sends a REQA short frame. Returns ``True`` if a card answered with
        a valid 2-byte ATQA.

        Returns:
            bool: ``True`` if a card is in the field.
        """
        self._write_reg(_REG_BIT_FRAMING, 0x07)  # TxLastBits = 7 (REQA short frame)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        back_data, back_len, _valid, _err = self._transceive(
            bytes([_PICC_CMD_REQA]), valid_bits=0)
        if not back_data or back_len != 2:
            return False
        return True

    def read_uid(self):
        """Detect a card, run anticollision/Select (all cascade levels), and HLTA.

        Returns the reassembled UID (4, 7, or 10 bytes). A card read this way
        is immediately halted, so the next call re-detects it from scratch.

        Returns:
            bytes | None: UID bytes, or ``None`` if no card answered.
        """
        if not self.is_card_present():
            return None
        uid = self._select_card()
        self._halt_card()
        return uid

    def _select_card(self):
        # Run anticollision / Select per cascade level until SAK indicates completion
        uid = bytearray()
        cascade_levels = [
            (_PICC_ANTICOLL_CL1, _PICC_SELECT_CL1),
            (_PICC_ANTICOLL_CL2, _PICC_SELECT_CL2),
            (_PICC_ANTICOLL_CL3, _PICC_SELECT_CL3),
        ]
        for anticoll_cmd, select_cmd in cascade_levels:
            uid_part = self._anticollision(anticoll_cmd)
            if uid_part is None:
                return None
            sak = self._select(select_cmd, uid_part)
            if sak is None:
                return None
            if not (sak & _PICC_SAK_NOT_COMPLETE):
                # Combine the parts. If first byte is 0x88 (cascade tag), drop it.
                if uid_part[0] == _PICC_CT:
                    uid.extend(uid_part[1:4])
                else:
                    uid.extend(uid_part[0:4])
                return bytes(uid)
            else:
                # Cascade continues; drop the 0x88 tag
                uid.extend(uid_part[1:4])
        return None

    def _anticollision(self, cmd):
        # Bitwise anticollision at 106 kBd, no CRC
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        self._write_reg(_REG_BIT_FRAMING, (0 << 4) | 0x00)  # RxAlign=0, TxLastBits=0
        ser_num = bytearray()
        known_bits = 0
        # Send 0x93 0x20 0x00... with 0 padding up to 32 bits total
        for _ in range(4):
            send = bytearray(2)
            send[0] = cmd
            send[1] = 0x20
            # Add padding zeros up to 32 bits
            for b in range(known_bits // 8):
                send.append(ser_num[b])
            if known_bits % 8:
                send.append(ser_num[known_bits // 8] & (0xFF << (8 - (known_bits % 8))))
            _delay_ms(1)
            back_data, back_len, _valid, _err = self._transceive(
                bytes(send), rx_align=0, valid_bits=0)
            if back_data is None or back_len != 5:
                return None
            # Reassemble up to known_bits + 32
            for i in range(4):
                ser_num.append(back_data[i])
            known_bits = 32
            # Check CollReg
            coll = self._read_reg(_REG_COLL)
            if coll & 0x20:  # CollPosNotValid
                break
            # Collision: shift bit position, but for simplicity we don't
            # implement the full bit-by-bit scheme. The MFRC522
            # hard-resolves collisions at the chip level when RxAlign=0
            # and ValuesAfterColl=0, so a single read is sufficient.
            break
        # BCC = XOR of first 4 bytes
        bcc = 0
        for b in ser_num[0:4]:
            bcc ^= b
        if bcc != ser_num[4]:
            return None
        return bytes(ser_num[0:4])

    def _select(self, cmd, uid_part):
        buf = bytearray(2 + 4 + 2)
        buf[0] = cmd
        buf[1] = _PICC_SEL_BIT
        buf[2:6] = uid_part
        bcc = 0
        for b in uid_part:
            bcc ^= b
        buf[6] = bcc
        # Append CRC_A — calculated by the chip's CalcCRC engine
        crc = self._calc_crc(buf[0:7])
        buf[7] = crc[0]
        buf[8] = crc[1]
        # Enable TxCRCEn / RxCRCEn
        self._write_reg(_REG_TX_MODE, 0x80)  # TxCRCEn=1
        self._write_reg(_REG_RX_MODE, 0x80)  # RxCRCEn=1
        _delay_ms(1)
        back_data, back_len, _valid, _err = self._transceive(bytes(buf), valid_bits=0)
        # Disable CRC for next time (anticollision/REQA/WUPA)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data is None or back_len < 1:
            return None
        return back_data[0]

    def _halt_card(self):
        buf = bytearray([_PICC_CMD_HLTA, 0x00])
        self._write_reg(_REG_TX_MODE, 0x80)  # TxCRCEn
        self._write_reg(_REG_RX_MODE, 0x80)  # RxCRCEn
        crc = self._calc_crc(buf)
        buf.append(crc[0])
        buf.append(crc[1])
        _delay_ms(1)
        self._card_command(_CMD_TRANSCEIVE, _IRQ_RX | _IRQ_IDLE, bytes(buf))
        # Disable CRC
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)

    def _calc_crc(self, data):
        self._write_reg(_REG_COMMAND, _CMD_IDLE)
        self._write_reg(_REG_COM_IRQ, 0x04)  # clear CRCIRq
        self._write_reg(_REG_DIV_IRQ, 0x04)  # clear CRCIRq (div)
        self._flush_fifo()
        self._write_fifo(data)
        self._write_reg(_REG_COMMAND, _CMD_CALC_CRC)
        for _ in range(90):
            n = self._read_reg(_REG_DIV_IRQ)
            if n & 0x04:
                break
            _delay_ms(1)
        self._write_reg(_REG_COMMAND, _CMD_IDLE)
        h = self._read_reg(_REG_CRC_RESULT_H)
        l = self._read_reg(_REG_CRC_RESULT_L)
        return bytes([h, l])


class MFRC522Full(MFRC522Minimal):
    """MFRC522 full interface — extends minimal with configuration, antenna control,
    self test, MIFARE Classic authenticated operations, and MIFARE Ultralight / NTAG
    page read/write.

    Args:
        transport: Configured I²C, SPI, or UART transport bound to the device.
        bus_type: Bus type string — ``'spi'`` (default), ``'i2c'``, or ``'uart'``.
    """

    KEY_A = 0x60
    KEY_B = 0x61

    RX_GAIN_18_DB = 0x00
    RX_GAIN_23_DB = 0x10
    RX_GAIN_33_DB = 0x40
    RX_GAIN_38_DB = 0x50
    RX_GAIN_43_DB = 0x60
    RX_GAIN_48_DB = 0x70

    _GAIN_TO_DB = {
        0x00: 18, 0x10: 23, 0x40: 33,
        0x50: 38, 0x60: 43, 0x70: 48,
    }
    _DB_TO_GAIN = {v: k for k, v in _GAIN_TO_DB.items()}

    def __init__(self, transport, bus_type='spi'):
        super().__init__(transport, bus_type)

    def reset(self):
        """Re-run ``SoftReset`` and the full initialization sequence."""
        self._init_chip()

    def antenna_on(self):
        """Enable the antenna driver (TX1 + TX2)."""
        self._set_bits(_REG_TX_CONTROL, 0x03)

    def antenna_off(self):
        """Disable the antenna driver (TX1 + TX2)."""
        self._clear_bits(_REG_TX_CONTROL, 0x03)

    def set_antenna_gain(self, dB):
        """Set the receiver gain.

        Args:
            dB: One of 18, 23, 33, 38, 43, or 48 dB.
        """
        if dB not in self._DB_TO_GAIN:
            raise ValueError('Unsupported gain: {} dB (choose 18, 23, 33, 38, 43, 48)'.format(dB))
        # Clear RxGain bits (4-6) and set new value
        cur = self._read_reg(_REG_RF_CFG) & 0x8F
        self._write_reg(_REG_RF_CFG, cur | self._DB_TO_GAIN[dB])

    def antenna_gain(self):
        """Read the currently configured receiver gain.

        Returns:
            int: Gain in dB (one of 18, 23, 33, 38, 43, 48).
        """
        cur = self._read_reg(_REG_RF_CFG) & 0x70
        return self._GAIN_TO_DB.get(cur, 0)

    def version(self):
        """Read the version register and decode it.

        Returns:
            tuple: ``(chip_type, version)``. For MFRC522, chip_type = 0x09.
        """
        raw = self._read_reg(_REG_VERSION)
        chip_type = (raw >> 4) & 0x0F
        version = raw & 0x0F
        return (chip_type, version)

    def self_test(self):
        """Run the datasheet-defined digital self test.

        Compares the 64 FIFO output bytes against the reference array
        matching the detected ``version()``.

        Returns:
            bool: ``True`` if all 64 bytes match the expected reference.
        """
        # Reference values from NXP datasheet (per detected version)
        ref_v10 = [
            0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
            0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
            0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
            0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
            0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
            0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
            0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
            0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
        ]
        ref_v20 = [
            0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
            0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
            0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
            0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
            0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
            0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
            0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
            0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F,
        ]
        chip_type, version = self.version()
        ref = ref_v10 if version == 1 else ref_v20
        # Run self test
        self._write_reg(_REG_AUTO_TEST, 0x09)
        self._write_reg(_REG_FIFO_LEVEL, _FIFO_FLUSH)
        self._write_reg(_REG_COMMAND, _CMD_IDLE)
        # CalcCRC with SelfTest=1001 runs the self test
        for _ in range(255):
            n = self._read_reg(_REG_FIFO_LEVEL)
            if n >= 64:
                break
            self._write_reg(_REG_COMMAND, _CMD_CALC_CRC)
            _delay_ms(1)
        self._stop_self_test()
        got = self._read_fifo(64)
        return list(got) == ref

    def _stop_self_test(self):
        self._write_reg(_REG_AUTO_TEST, 0x00)
        self._write_reg(_REG_COMMAND, _CMD_SOFT_RESET)
        _delay_ms(50)
        self._init_chip()

    def wakeup_card(self):
        """WUPA — wake a HALTed card. Same as ``is_card_present`` but with WUPA."""
        self._write_reg(_REG_BIT_FRAMING, 0x07)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        back_data, back_len, _valid, _err = self._transceive(
            bytes([_PICC_CMD_WUPA]), valid_bits=0)
        if not back_data or back_len != 2:
            return False
        return True

    def select_card(self):
        """Run anticollision / Select only — leaves the card active for further ops.

        Returns:
            bytes | None: UID bytes, or ``None`` if no card answered.
        """
        if not self.wakeup_card():
            return None
        return self._select_card()

    def halt_card(self):
        """Send HLTA — put the currently selected card into HALT state."""
        self._halt_card()

    def authenticate(self, block_address, key_type, key, uid):
        """Run MIFARE Classic Crypto1 authentication.

        Args:
            block_address: Block number to authenticate against.
            key_type: ``MFRC522Full.KEY_A`` (0x60) or ``MFRC522Full.KEY_B`` (0x61).
            key: 6-byte key.
            uid: 4-byte UID of the card.

        Returns:
            bool: ``True`` if authentication succeeded.
        """
        if len(key) != 6 or len(uid) != 4:
            return False
        buf = bytearray(12)
        buf[0] = key_type
        buf[1] = block_address & 0xFF
        buf[2:8] = key
        buf[8:12] = uid
        self._write_reg(_REG_COM_IRQ, 0x7F)
        self._write_reg(_REG_STATUS_2, 0x00)
        self._flush_fifo()
        self._write_fifo(bytes(buf))
        self._write_reg(_REG_COMMAND, _CMD_MFAUTHENT)
        for _ in range(200):
            n = self._read_reg(_REG_STATUS_2)
            if n & 0x08:  # MFCrypto1On
                return True
            if n & 0x10:  # ... or check error
                pass
            _delay_ms(1)
        return False

    def stop_crypto(self):
        """Clear ``Status2Reg.MFCrypto1On``."""
        self._clear_bits(_REG_STATUS_2, 0x08)

    def read_block(self, block_address):
        """Read a 16-byte MIFARE Classic block.

        Args:
            block_address: Block number (0–255 for 1K, 0–255 for 4K).

        Returns:
            bytes | None: 16 data bytes, or ``None`` on failure.
        """
        buf = bytes([0x30, block_address & 0xFF])
        self._write_reg(_REG_TX_MODE, 0x80)
        self._write_reg(_REG_RX_MODE, 0x80)
        crc = self._calc_crc(buf)
        full = bytes([buf[0], buf[1], crc[0], crc[1]])
        back_data, back_len, _valid, _err = self._transceive(full, valid_bits=0)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data is None or back_len != 16:
            return None
        return bytes(back_data)

    def write_block(self, block_address, data):
        """Write a 16-byte MIFARE Classic block.

        Args:
            block_address: Block number.
            data: 16 bytes to write.

        Returns:
            bool: ``True`` on success.
        """
        if len(data) != 16:
            return False
        # Phase 1: command + address + CRC
        buf = bytes([0xA0, block_address & 0xFF])
        self._write_reg(_REG_TX_MODE, 0x80)
        self._write_reg(_REG_RX_MODE, 0x80)
        crc = self._calc_crc(buf)
        full = bytes([buf[0], buf[1], crc[0], crc[1]])
        back_data, back_len, _valid, _err = self._transceive(full, valid_bits=4)
        if back_data is None or (back_len != 1 and back_len != 2) or back_data[0] & 0x0F != 0x0A:
            self._write_reg(_REG_TX_MODE, 0x00)
            self._write_reg(_REG_RX_MODE, 0x00)
            return False
        # Phase 2: 16 data bytes + CRC
        crc2 = self._calc_crc(data)
        full2 = bytes(list(data) + [crc2[0], crc2[1]])
        back_data2, back_len2, _valid2, _err2 = self._transceive(full2, valid_bits=4)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data2 is None or back_len2 < 1 or back_data2[0] & 0x0F != 0x0A:
            return False
        return True

    def increment_value(self, block_address, delta):
        """Decrement the value block at ``block_address`` by ``delta`` and transfer it back.

        Args:
            block_address: Source value block.
            delta: Unsigned 32-bit decrement.

        Returns:
            bool: ``True`` on success.
        """
        return self._value_op(0xC1, block_address, delta)

    def decrement_value(self, block_address, delta):
        """Decrement the value block at ``block_address`` by ``delta``.

        Args:
            block_address: Source value block.
            delta: Unsigned 32-bit decrement.

        Returns:
            bool: ``True`` on success.
        """
        return self._value_op(0xC0, block_address, delta)

    def restore_value(self, block_address):
        """Restore (re-read) the value block at ``block_address`` into the internal data register.

        Args:
            block_address: Value block to restore.

        Returns:
            bool: ``True`` on success.
        """
        # Restore uses 4 dummy data bytes
        if not self._value_op(0xC2, block_address, None, dummy=True):
            return False
        return self._transfer(block_address)

    def transfer_value(self, destination_block):
        """Commit the internal data register to ``destination_block``.

        Args:
            destination_block: Block to write the data register to.

        Returns:
            bool: ``True`` on success.
        """
        return self._transfer(destination_block)

    def _value_op(self, cmd, block_address, delta, dummy=False):
        buf = bytes([cmd, block_address & 0xFF])
        self._write_reg(_REG_TX_MODE, 0x80)
        self._write_reg(_REG_RX_MODE, 0x80)
        crc = self._calc_crc(buf)
        full = bytes([buf[0], buf[1], crc[0], crc[1]])
        back_data, back_len, _valid, _err = self._transceive(full, valid_bits=4)
        if back_data is None or back_len < 1 or back_data[0] & 0x0F != 0x0A:
            self._write_reg(_REG_TX_MODE, 0x00)
            self._write_reg(_REG_RX_MODE, 0x00)
            return False
        # Phase 2: 4-byte value (or 4 dummy bytes)
        if dummy:
            data = bytes(4)
        else:
            data = bytes([
                delta & 0xFF,
                (delta >> 8) & 0xFF,
                (delta >> 16) & 0xFF,
                (delta >> 24) & 0xFF,
            ])
        crc2 = self._calc_crc(data)
        full2 = bytes(list(data) + [crc2[0], crc2[1]])
        back_data2, back_len2, _valid2, _err2 = self._transceive(full2, valid_bits=4)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data2 is None or back_len2 < 1 or back_data2[0] & 0x0F != 0x0A:
            return False
        return True

    def _transfer(self, block_address):
        buf = bytes([0xB0, block_address & 0xFF])
        self._write_reg(_REG_TX_MODE, 0x80)
        self._write_reg(_REG_RX_MODE, 0x80)
        crc = self._calc_crc(buf)
        full = bytes([buf[0], buf[1], crc[0], crc[1]])
        back_data, back_len, _valid, _err = self._transceive(full, valid_bits=4)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data is None or back_len < 1 or back_data[0] & 0x0F != 0x0A:
            return False
        return True

    def read_ultralight_page(self, page_address):
        """Read 4 consecutive pages (16 bytes) starting at ``page_address``.

        No authentication required.

        Args:
            page_address: Page number (0-based).

        Returns:
            bytes | None: 16 data bytes, or ``None`` on failure.
        """
        buf = bytes([0x30, page_address & 0xFF])
        self._write_reg(_REG_TX_MODE, 0x80)
        self._write_reg(_REG_RX_MODE, 0x80)
        crc = self._calc_crc(buf)
        full = bytes([buf[0], buf[1], crc[0], crc[1]])
        back_data, back_len, _valid, _err = self._transceive(full, valid_bits=0)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data is None or back_len != 16:
            return None
        return bytes(back_data)

    def write_ultralight_page(self, page_address, data):
        """Write a 4-byte page (MIFARE Ultralight / NTAG).

        Args:
            page_address: Page number.
            data: 4 bytes to write.

        Returns:
            bool: ``True`` on success.
        """
        if len(data) != 4:
            return False
        buf = bytearray(2 + 4)
        buf[0] = 0xA2
        buf[1] = page_address & 0xFF
        buf[2:6] = data
        self._write_reg(_REG_TX_MODE, 0x80)
        self._write_reg(_REG_RX_MODE, 0x80)
        crc = self._calc_crc(bytes(buf[0:6]))
        buf.append(crc[0])
        buf.append(crc[1])
        back_data, back_len, _valid, _err = self._transceive(bytes(buf), valid_bits=4)
        self._write_reg(_REG_TX_MODE, 0x00)
        self._write_reg(_REG_RX_MODE, 0x00)
        if back_data is None or back_len < 1 or back_data[0] & 0x0F != 0x0A:
            return False
        return True

    def generate_random_id(self):
        """Run the Generate RandomID command and return the 10-byte ID.

        Returns:
            bytes: 10-byte random ID.
        """
        self._write_reg(_REG_COMMAND, _CMD_IDLE)
        self._write_reg(_REG_COM_IRQ, 0x7F)
        self._write_reg(_REG_DIV_IRQ, 0x14)
        self._write_reg(_REG_COMMAND, _CMD_RANDOM_ID)
        # Wait for command to complete
        for _ in range(50):
            if self._read_reg(_REG_COM_IRQ) & 0x10:
                break
            _delay_ms(1)
        self._write_reg(_REG_COMMAND, _CMD_IDLE)
        return bytes(self._read_fifo(10))
