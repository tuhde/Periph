// Package rfid contains drivers for RFID and NFC reader/writer modules.
package rfid

import (
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// MFRC522 register addresses (6 bits, 0x00–0x3F).
const (
	mfrcRegCommand      uint8 = 0x01
	mfrcRegComIEn       uint8 = 0x02
	mfrcRegComIrq       uint8 = 0x04
	mfrcRegError        uint8 = 0x06
	mfrcRegStatus1      uint8 = 0x07
	mfrcRegStatus2      uint8 = 0x08
	mfrcRegFIFOData     uint8 = 0x09
	mfrcRegFIFOLevel    uint8 = 0x0A
	mfrcRegControl      uint8 = 0x0C
	mfrcRegBitFraming   uint8 = 0x0D
	mfrcRegColl         uint8 = 0x0E
	mfrcRegMode         uint8 = 0x11
	mfrcRegTxMode       uint8 = 0x12
	mfrcRegRxMode       uint8 = 0x13
	mfrcRegTxControl    uint8 = 0x14
	mfrcRegTxASK        uint8 = 0x15
	mfrcRegCRCResultH   uint8 = 0x21
	mfrcRegCRCResultL   uint8 = 0x22
	mfrcRegRFCfg        uint8 = 0x26
	mfrcRegTMode        uint8 = 0x2A
	mfrcRegTPrescaler   uint8 = 0x2B
	mfrcRegTReloadH     uint8 = 0x2C
	mfrcRegTReloadL     uint8 = 0x2D
	mfrcRegAutoTest     uint8 = 0x36
	mfrcRegVersion      uint8 = 0x37
)

// MFRC522 command codes (CommandReg.Command[3:0]).
const (
	mfrcCmdIdle       uint8 = 0x00
	mfrcCmdRandomID   uint8 = 0x02
	mfrcCmdCalcCRC    uint8 = 0x03
	mfrcCmdTransceive uint8 = 0x0C
	mfrcCmdMFAuthent  uint8 = 0x0E
	mfrcCmdSoftReset  uint8 = 0x0F
)

// ComIrqReg bits.
const (
	mfrcIRQTx      uint8 = 0x40
	mfrcIRQRx      uint8 = 0x30
	mfrcIRQIdle    uint8 = 0x10
	mfrcIRQHiAlert uint8 = 0x08
	mfrcIRQLoAlert uint8 = 0x04
	mfrcIRQErr     uint8 = 0x02
	mfrcIRQTimer   uint8 = 0x01
	mfrcIRQAll     uint8 = 0x7F
)

// Status2Reg bits.
const (
	mfrcStatus2Crypto1On uint8 = 0x08
)

// FIFOLevelReg bit.
const (
	mfrcFIFOFlush uint8 = 0x80
)

// ISO/IEC 14443-3 Type A short-frame commands.
const (
	piccREQA  uint8 = 0x26
	piccWUPA  uint8 = 0x52
	piccHLTA  uint8 = 0x50
	piccCT    uint8 = 0x88
	piccSelBit uint8 = 0x70
	piccSAKNotComplete uint8 = 0x04
)

// Cascade-level command bytes.
var (
	piccAnticollCL = [3]uint8{0x93, 0x95, 0x97}
	piccSelectCL   = [3]uint8{0x93, 0x95, 0x97}
)

// KEY_A is the MIFARE Crypto1 key A command byte (0x60).
const KEY_A uint8 = 0x60

// KEY_B is the MIFARE Crypto1 key B command byte (0x61).
const KEY_B uint8 = 0x61

// Receiver gain settings.
const (
	RX_GAIN_18_DB uint8 = 0x00
	RX_GAIN_23_DB uint8 = 0x10
	RX_GAIN_33_DB uint8 = 0x40
	RX_GAIN_38_DB uint8 = 0x50
	RX_GAIN_43_DB uint8 = 0x60
	RX_GAIN_48_DB uint8 = 0x70
)

// mfrcDelay is the small settle delay before transceive operations.
const mfrcDelay = 1 * time.Millisecond

// MFRC522Minimal is the NXP MFRC522 13.56 MHz RFID/NFC reader driver —
// minimal interface. Detects ISO/IEC 14443 Type A cards in the field
// and reads their UID.
//
// Communicates over an I²C transport (DDC) bound to the chip's 7-bit
// address (0x28 with all address pins tied LOW). All register
// accesses use the I²C framing documented in the spec: write
// [register] followed by data bytes; read via a write-then-read of
// the register pointer.
//
// Default configuration baked in:
//   - 25 ms receive timeout (TReloadReg = 1000 @ TPrescaler = 169)
//   - Force100ASK modulation
//   - ISO/IEC 14443-3 CRC_A preset (0x6363)
//   - Antenna enabled
//   - 106 kBd, 33 dB RX gain (reset default)
type MFRC522Minimal struct {
	transport transport.Transport
}

// NewMFRC522Minimal creates a new MFRC522Minimal and runs the
// initialization sequence: SoftReset, 25 ms timer settings,
// Force100ASK, CRC_A preset, antenna on.
func NewMFRC522Minimal(t transport.Transport) (*MFRC522Minimal, error) {
	d := &MFRC522Minimal{transport: t}
	if err := d.initChip(); err != nil {
		return nil, err
	}
	return d, nil
}

// writeReg writes one byte to the given register via I²C.
func (d *MFRC522Minimal) writeReg(reg, value uint8) error {
	return d.transport.Write([]byte{reg & 0x3F, value})
}

// readReg reads one byte from the given register via I²C.
func (d *MFRC522Minimal) readReg(reg uint8) (uint8, error) {
	buf, err := d.transport.WriteRead([]byte{reg & 0x3F}, 1)
	if err != nil {
		return 0, err
	}
	return buf[0], nil
}

func (d *MFRC522Minimal) setBits(reg, mask uint8) error {
	cur, err := d.readReg(reg)
	if err != nil {
		return err
	}
	return d.writeReg(reg, cur|mask)
}

func (d *MFRC522Minimal) clearBits(reg, mask uint8) error {
	cur, err := d.readReg(reg)
	if err != nil {
		return err
	}
	return d.writeReg(reg, cur&^mask)
}

func (d *MFRC522Minimal) initChip() error {
	if err := d.writeReg(mfrcRegCommand, mfrcCmdSoftReset); err != nil {
		return err
	}
	for i := 0; i < 50; i++ {
		v, err := d.readReg(mfrcRegCommand)
		if err != nil {
			return err
		}
		if v&0x10 == 0 {
			break
		}
		time.Sleep(mfrcDelay)
	}
	time.Sleep(50 * time.Millisecond)
	// Timer: TAuto + TPrescaler 0xA9 + TReload 0x03E8 (1000) = ~25 ms
	if err := d.writeReg(mfrcRegTMode, 0x80); err != nil {
		return err
	}
	if err := d.writeReg(mfrcRegTPrescaler, 0xA9); err != nil {
		return err
	}
	if err := d.writeReg(mfrcRegTReloadH, 0x03); err != nil {
		return err
	}
	if err := d.writeReg(mfrcRegTReloadL, 0xE8); err != nil {
		return err
	}
	// Force100ASK
	if err := d.writeReg(mfrcRegTxASK, 0x40); err != nil {
		return err
	}
	// Mode: TxWaitRF, PolMFin, CRCPreset=0x6363 (CRC_A)
	if err := d.writeReg(mfrcRegMode, 0x3D); err != nil {
		return err
	}
	// Antenna on
	return d.setBits(mfrcRegTxControl, 0x03)
}

func (d *MFRC522Minimal) readFIFO(n int) ([]byte, error) {
	out := make([]byte, n)
	for i := 0; i < n; i++ {
		v, err := d.readReg(mfrcRegFIFOData)
		if err != nil {
			return nil, err
		}
		out[i] = v
	}
	return out, nil
}

func (d *MFRC522Minimal) writeFIFO(data []byte) error {
	for _, b := range data {
		if err := d.writeReg(mfrcRegFIFOData, b); err != nil {
			return err
		}
	}
	return nil
}

func (d *MFRC522Minimal) flushFIFO() error {
	return d.writeReg(mfrcRegFIFOLevel, mfrcFIFOFlush)
}

func (d *MFRC522Minimal) cardCommand(command, waitIRQ uint8, send []byte) (bool, error) {
	if err := d.writeReg(mfrcRegCommand, mfrcCmdIdle); err != nil {
		return false, err
	}
	if err := d.writeReg(mfrcRegComIrq, mfrcIRQAll); err != nil {
		return false, err
	}
	if err := d.flushFIFO(); err != nil {
		return false, err
	}
	if len(send) > 0 {
		if err := d.writeFIFO(send); err != nil {
			return false, err
		}
	}
	if err := d.writeReg(mfrcRegCommand, command); err != nil {
		return false, err
	}
	if command == mfrcCmdTransceive {
		if err := d.setBits(mfrcRegBitFraming, 0x80); err != nil {
			return false, err
		}
	}
	for i := 0; i < 200; i++ {
		n, err := d.readReg(mfrcRegComIrq)
		if err != nil {
			return false, err
		}
		if n&waitIRQ != 0 {
			return true, nil
		}
		if n&mfrcIRQTimer != 0 {
			return false, nil
		}
	}
	return false, nil
}

func (d *MFRC522Minimal) transceive(send []byte) ([]byte, error) {
	ok, err := d.cardCommand(mfrcCmdTransceive, mfrcIRQRx|mfrcIRQIdle, send)
	if err != nil {
		return nil, err
	}
	if !ok {
		return nil, nil
	}
	errReg, err := d.readReg(mfrcRegError)
	if err != nil {
		return nil, err
	}
	if errReg&0x13 != 0 {
		return nil, nil
	}
	level, err := d.readReg(mfrcRegFIFOLevel)
	if err != nil {
		return nil, err
	}
	if level == 0 {
		return nil, nil
	}
	return d.readFIFO(int(level))
}

func (d *MFRC522Minimal) calcCRC(data []byte) ([2]byte, error) {
	if err := d.writeReg(mfrcRegCommand, mfrcCmdIdle); err != nil {
		return [2]byte{}, err
	}
	if err := d.writeReg(mfrcRegComIrq, 0x04); err != nil {
		return [2]byte{}, err
	}
	if err := d.flushFIFO(); err != nil {
		return [2]byte{}, err
	}
	if err := d.writeFIFO(data); err != nil {
		return [2]byte{}, err
	}
	if err := d.writeReg(mfrcRegCommand, mfrcCmdCalcCRC); err != nil {
		return [2]byte{}, err
	}
	for i := 0; i < 100; i++ {
		v, err := d.readReg(mfrcRegComIrq)
		if err != nil {
			return [2]byte{}, err
		}
		if v&0x04 != 0 {
			break
		}
		time.Sleep(mfrcDelay)
	}
	if err := d.writeReg(mfrcRegCommand, mfrcCmdIdle); err != nil {
		return [2]byte{}, err
	}
	h, err := d.readReg(mfrcRegCRCResultH)
	if err != nil {
		return [2]byte{}, err
	}
	l, err := d.readReg(mfrcRegCRCResultL)
	if err != nil {
		return [2]byte{}, err
	}
	return [2]byte{h, l}, nil
}

func (d *MFRC522Minimal) anticollision(cmd uint8) ([]byte, error) {
	if err := d.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return nil, err
	}
	if err := d.writeReg(mfrcRegBitFraming, 0x00); err != nil {
		return nil, err
	}
	send := []byte{cmd, 0x20}
	time.Sleep(mfrcDelay)
	back, err := d.transceive(send)
	if err != nil {
		return nil, err
	}
	if back == nil || len(back) < 5 {
		return nil, nil
	}
	bcc := byte(0)
	for i := 0; i < 4; i++ {
		bcc ^= back[i]
	}
	if bcc != back[4] {
		return nil, nil
	}
	return back[0:4], nil
}

func (d *MFRC522Minimal) piccSelect(cmd uint8, uidPart []byte) (uint8, error) {
	buf := make([]byte, 9)
	buf[0] = cmd
	buf[1] = piccSelBit
	copy(buf[2:6], uidPart)
	bcc := byte(0)
	for _, b := range uidPart {
		bcc ^= b
	}
	buf[6] = bcc
	crc, err := d.calcCRC(buf[0:7])
	if err != nil {
		return 0, err
	}
	buf[7] = crc[0]
	buf[8] = crc[1]
	if err := d.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return 0, err
	}
	if err := d.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return 0, err
	}
	time.Sleep(mfrcDelay)
	back, err := d.transceive(buf)
	if err != nil {
		return 0, err
	}
	if err := d.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return 0, err
	}
	if err := d.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return 0, err
	}
	if back == nil || len(back) < 1 {
		return 0, nil
	}
	return back[0], nil
}

func (d *MFRC522Minimal) selectCard() ([]byte, error) {
	uid := make([]byte, 0, 10)
	for i := 0; i < 3; i++ {
		part, err := d.anticollision(piccAnticollCL[i])
		if err != nil || part == nil {
			return nil, err
		}
		sak, err := d.piccSelect(piccSelectCL[i], part)
		if err != nil || sak == 0 {
			return nil, err
		}
		if sak&piccSAKNotComplete == 0 {
			if part[0] == piccCT {
				uid = append(uid, part[1:4]...)
			} else {
				uid = append(uid, part[0:4]...)
			}
			return uid, nil
		}
		uid = append(uid, part[1:4]...)
	}
	return nil, nil
}

func (d *MFRC522Minimal) haltCard() error {
	buf := []byte{piccHLTA, 0x00}
	if err := d.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return err
	}
	if err := d.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return err
	}
	crc, err := d.calcCRC(buf)
	if err != nil {
		return err
	}
	buf = append(buf, crc[0], crc[1])
	time.Sleep(mfrcDelay)
	_, _ = d.cardCommand(mfrcCmdTransceive, mfrcIRQRx|mfrcIRQIdle, buf)
	if err := d.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return err
	}
	return d.writeReg(mfrcRegRxMode, 0x00)
}

// IsCardPresent sends a REQA short frame and returns true if a card
// answered with a valid 2-byte ATQA.
func (d *MFRC522Minimal) IsCardPresent() (bool, error) {
	if err := d.writeReg(mfrcRegBitFraming, 0x07); err != nil {
		return false, err
	}
	if err := d.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return false, err
	}
	if err := d.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return false, err
	}
	back, err := d.transceive([]byte{piccREQA})
	if err != nil {
		return false, err
	}
	return back != nil && len(back) == 2, nil
}

// ReadUID detects a card, runs anticollision/Select (all cascade
// levels) and HLTA. Returns the reassembled UID (4, 7, or 10 bytes),
// or nil if no card answered. A card read this way is immediately
// halted, so the next call re-detects it from scratch.
func (d *MFRC522Minimal) ReadUID() ([]byte, error) {
	present, err := d.IsCardPresent()
	if err != nil {
		return nil, err
	}
	if !present {
		return nil, nil
	}
	uid, err := d.selectCard()
	if err != nil {
		return nil, err
	}
	_ = d.haltCard()
	return uid, nil
}

// MFRC522Full is the MFRC522 driver — full interface. Extends
// MFRC522Minimal with configuration, antenna control, self test, the
// MIFARE Classic authenticated read/write/value operations, and MIFARE
// Ultralight / NTAG page read/write.
//
// Embeds MFRC522Minimal to inherit IsCardPresent, ReadUID, and the
// init sequence; adds Full-only methods below.
type MFRC522Full struct {
	*MFRC522Minimal
}

// NewMFRC522Full creates a new MFRC522Full with the same initialisation
// as NewMFRC522Minimal.
func NewMFRC522Full(t transport.Transport) (*MFRC522Full, error) {
	m, err := NewMFRC522Minimal(t)
	if err != nil {
		return nil, err
	}
	return &MFRC522Full{MFRC522Minimal: m}, nil
}

// Reset re-runs SoftReset and the full initialization sequence.
func (f *MFRC522Full) Reset() error { return f.initChip() }

// AntennaOn enables the antenna driver (TX1 + TX2).
func (f *MFRC522Full) AntennaOn() error { return f.setBits(mfrcRegTxControl, 0x03) }

// AntennaOff disables the antenna driver (TX1 + TX2).
func (f *MFRC522Full) AntennaOff() error { return f.clearBits(mfrcRegTxControl, 0x03) }

// SetAntennaGain sets the receiver gain. gain must be one of
// RX_GAIN_18_DB, RX_GAIN_23_DB, RX_GAIN_33_DB, RX_GAIN_38_DB,
// RX_GAIN_43_DB, RX_GAIN_48_DB.
func (f *MFRC522Full) SetAntennaGain(gain uint8) error {
	cur, err := f.readReg(mfrcRegRFCfg)
	if err != nil {
		return err
	}
	return f.writeReg(mfrcRegRFCfg, (cur&0x8F)|(gain&0x70))
}

// AntennaGain reads back the currently configured receiver gain
// (raw register bits 4-6).
func (f *MFRC522Full) AntennaGain() (uint8, error) {
	cur, err := f.readReg(mfrcRegRFCfg)
	if err != nil {
		return 0, err
	}
	return cur & 0x70, nil
}

// Version reads the version register and decodes it into
// (chipType, version). For MFRC522, chipType = 0x09.
func (f *MFRC522Full) Version() (uint8, uint8, error) {
	raw, err := f.readReg(mfrcRegVersion)
	if err != nil {
		return 0, 0, err
	}
	return (raw >> 4) & 0x0F, raw & 0x0F, nil
}

// refV10 is the 64-byte reference output for the MFRC522 v1.0 self test.
var refV10 = [64]byte{
	0x00, 0x87, 0x98, 0x0F, 0x49, 0xFF, 0x07, 0x19,
	0xBF, 0x22, 0x30, 0x49, 0x59, 0x63, 0xAD, 0xCA,
	0x7F, 0xE3, 0x4E, 0x03, 0x5C, 0x4E, 0x49, 0x50,
	0x47, 0x9A, 0x37, 0x61, 0xE7, 0xE2, 0xC6, 0x2E,
	0x75, 0x5A, 0xED, 0x04, 0x3D, 0x02, 0x4B, 0x78,
	0x32, 0xFF, 0x58, 0x3B, 0x7C, 0xE9, 0x00, 0x94,
	0xB4, 0x4A, 0x59, 0x5B, 0xFD, 0xC9, 0x29, 0xDF,
	0x35, 0x96, 0x98, 0x9E, 0x4F, 0x30, 0x32, 0x8D,
}

// refV20 is the 64-byte reference output for the MFRC522 v2.0 self test.
var refV20 = [64]byte{
	0x00, 0xEB, 0x66, 0xBA, 0x57, 0xBF, 0x23, 0x95,
	0xD0, 0xE3, 0x0D, 0x3D, 0x27, 0x89, 0x5C, 0xDE,
	0x9D, 0x3B, 0xA7, 0x00, 0x21, 0x5B, 0x89, 0x82,
	0x51, 0x3A, 0xEB, 0x02, 0x0C, 0xA5, 0x00, 0x49,
	0x7C, 0x84, 0x4D, 0xB3, 0xCC, 0xD2, 0x1B, 0x81,
	0x5D, 0x48, 0x76, 0xD5, 0x71, 0x61, 0x21, 0xA9,
	0x86, 0x96, 0x83, 0x38, 0xCF, 0x9D, 0x5B, 0x6D,
	0xDC, 0x15, 0xBA, 0x3E, 0x7D, 0x95, 0x3B, 0x2F,
}

// SelfTest runs the datasheet-defined digital self test. Returns true
// if all 64 FIFO bytes match the version-specific reference.
func (f *MFRC522Full) SelfTest() (bool, error) {
	_, ver, err := f.Version()
	if err != nil {
		return false, err
	}
	ref := refV20
	if ver == 1 {
		ref = refV10
	}
	if err := f.writeReg(mfrcRegAutoTest, 0x09); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegFIFOLevel, mfrcFIFOFlush); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegCommand, mfrcCmdIdle); err != nil {
		return false, err
	}
	for i := 0; i < 255; i++ {
		level, err := f.readReg(mfrcRegFIFOLevel)
		if err != nil {
			return false, err
		}
		if level >= 64 {
			break
		}
		if err := f.writeReg(mfrcRegCommand, mfrcCmdCalcCRC); err != nil {
			return false, err
		}
		time.Sleep(mfrcDelay)
	}
	if err := f.writeReg(mfrcRegAutoTest, 0x00); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegCommand, mfrcCmdSoftReset); err != nil {
		return false, err
	}
	time.Sleep(50 * time.Millisecond)
	if err := f.initChip(); err != nil {
		return false, err
	}
	got, err := f.readFIFO(64)
	if err != nil {
		return false, err
	}
	for i := 0; i < 64; i++ {
		if got[i] != ref[i] {
			return false, nil
		}
	}
	return true, nil
}

// WakeupCard sends WUPA — same as IsCardPresent but with WUPA,
// waking HALTed cards.
func (f *MFRC522Full) WakeupCard() (bool, error) {
	if err := f.writeReg(mfrcRegBitFraming, 0x07); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return false, err
	}
	back, err := f.transceive([]byte{piccWUPA})
	if err != nil {
		return false, err
	}
	return back != nil && len(back) == 2, nil
}

// SelectCard runs anticollision / Select only — leaves the card active
// for subsequent authenticated operations. Returns the UID, or nil if
// no card answered.
func (f *MFRC522Full) SelectCard() ([]byte, error) {
	present, err := f.WakeupCard()
	if err != nil {
		return nil, err
	}
	if !present {
		return nil, nil
	}
	return f.selectCard()
}

// HaltCard sends HLTA — puts the currently selected card into HALT
// state.
func (f *MFRC522Full) HaltCard() error { return f.haltCard() }

// Authenticate runs MIFARE Classic Crypto1 authentication.
//
// blockAddress is the block to authenticate against. keyType is
// KEY_A (0x60) or KEY_B (0x61). key is a 6-byte key. uid is the
// card's plain 4-byte UID.
func (f *MFRC522Full) Authenticate(blockAddress, keyType uint8, key []byte, uid []byte) (bool, error) {
	if len(key) != 6 || len(uid) != 4 {
		return false, nil
	}
	buf := make([]byte, 12)
	buf[0] = keyType
	buf[1] = blockAddress
	copy(buf[2:8], key)
	copy(buf[8:12], uid)
	if err := f.writeReg(mfrcRegComIrq, mfrcIRQAll); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegStatus2, 0x00); err != nil {
		return false, err
	}
	if err := f.flushFIFO(); err != nil {
		return false, err
	}
	if err := f.writeFIFO(buf); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegCommand, mfrcCmdMFAuthent); err != nil {
		return false, err
	}
	for i := 0; i < 200; i++ {
		s2, err := f.readReg(mfrcRegStatus2)
		if err != nil {
			return false, err
		}
		if s2&mfrcStatus2Crypto1On != 0 {
			return true, nil
		}
		time.Sleep(mfrcDelay)
	}
	return false, nil
}

// StopCrypto clears Status2Reg.MFCrypto1On. Call after finishing
// authenticated access to a card before selecting another.
func (f *MFRC522Full) StopCrypto() error { return f.clearBits(mfrcRegStatus2, mfrcStatus2Crypto1On) }

// ReadBlock reads a 16-byte MIFARE Classic block. Requires prior
// Authenticate for the containing sector.
func (f *MFRC522Full) ReadBlock(blockAddress uint8) ([]byte, error) {
	cmd := []byte{0x30, blockAddress}
	if err := f.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return nil, err
	}
	crc, err := f.calcCRC(cmd)
	if err != nil {
		return nil, err
	}
	full := []byte{cmd[0], cmd[1], crc[0], crc[1]}
	back, err := f.transceive(full)
	if err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return nil, err
	}
	if back == nil || len(back) < 16 {
		return nil, nil
	}
	return back[0:16], nil
}

// WriteBlock writes a 16-byte MIFARE Classic block. Requires prior
// Authenticate for the containing sector.
func (f *MFRC522Full) WriteBlock(blockAddress uint8, data []byte) (bool, error) {
	if len(data) != 16 {
		return false, nil
	}
	// Phase 1: command + address
	c := []byte{0xA0, blockAddress}
	if err := f.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return false, err
	}
	crc, err := f.calcCRC(c)
	if err != nil {
		return false, err
	}
	full := []byte{c[0], c[1], crc[0], crc[1]}
	back, err := f.transceive(full)
	if err != nil {
		return false, err
	}
	if back == nil || len(back) < 1 || back[0]&0x0F != 0x0A {
		if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
			return false, err
		}
		if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
			return false, err
		}
		return false, nil
	}
	// Phase 2: 16 data bytes
	crc2, err := f.calcCRC(data)
	if err != nil {
		return false, err
	}
	buf := make([]byte, 18)
	copy(buf[0:16], data)
	buf[16] = crc2[0]
	buf[17] = crc2[1]
	back2, err := f.transceive(buf)
	if err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return false, err
	}
	return back2 != nil && len(back2) >= 1 && back2[0]&0x0F == 0x0A, nil
}

func (f *MFRC522Full) valueOp(cmd, blockAddress uint8, delta uint32, dummy bool) (bool, error) {
	c := []byte{cmd, blockAddress}
	if err := f.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return false, err
	}
	crc, err := f.calcCRC(c)
	if err != nil {
		return false, err
	}
	full := []byte{c[0], c[1], crc[0], crc[1]}
	back, err := f.transceive(full)
	if err != nil {
		return false, err
	}
	if back == nil || len(back) < 1 || back[0]&0x0F != 0x0A {
		if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
			return false, err
		}
		if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
			return false, err
		}
		return false, nil
	}
	var data [4]byte
	if !dummy {
		data[0] = byte(delta)
		data[1] = byte(delta >> 8)
		data[2] = byte(delta >> 16)
		data[3] = byte(delta >> 24)
	}
	crc2, err := f.calcCRC(data[:])
	if err != nil {
		return false, err
	}
	buf := []byte{data[0], data[1], data[2], data[3], crc2[0], crc2[1]}
	back2, err := f.transceive(buf)
	if err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return false, err
	}
	return back2 != nil && len(back2) >= 1 && back2[0]&0x0F == 0x0A, nil
}

func (f *MFRC522Full) transfer(blockAddress uint8) (bool, error) {
	c := []byte{0xB0, blockAddress}
	if err := f.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return false, err
	}
	crc, err := f.calcCRC(c)
	if err != nil {
		return false, err
	}
	full := []byte{c[0], c[1], crc[0], crc[1]}
	back, err := f.transceive(full)
	if err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return false, err
	}
	return back != nil && len(back) >= 1 && back[0]&0x0F == 0x0A, nil
}

// IncrementValue increments the value block at blockAddress by delta
// and transfers it back.
func (f *MFRC522Full) IncrementValue(blockAddress uint8, delta uint32) (bool, error) {
	ok, err := f.valueOp(0xC1, blockAddress, delta, false)
	if err != nil || !ok {
		return ok, err
	}
	return f.transfer(blockAddress)
}

// DecrementValue decrements the value block at blockAddress by delta
// and transfers it back.
func (f *MFRC522Full) DecrementValue(blockAddress uint8, delta uint32) (bool, error) {
	ok, err := f.valueOp(0xC0, blockAddress, delta, false)
	if err != nil || !ok {
		return ok, err
	}
	return f.transfer(blockAddress)
}

// RestoreValue restores the value block at blockAddress into the
// internal data register and transfers it back.
func (f *MFRC522Full) RestoreValue(blockAddress uint8) (bool, error) {
	ok, err := f.valueOp(0xC2, blockAddress, 0, true)
	if err != nil || !ok {
		return ok, err
	}
	return f.transfer(blockAddress)
}

// TransferValue commits the internal data register to
// destinationBlock. The source of the data register is left over from
// a prior Increment/Decrement/Restore.
func (f *MFRC522Full) TransferValue(destinationBlock uint8) (bool, error) {
	return f.transfer(destinationBlock)
}

// ReadUltralightPage reads 4 consecutive pages (16 bytes) starting at
// pageAddress. No authentication required.
func (f *MFRC522Full) ReadUltralightPage(pageAddress uint8) ([]byte, error) {
	cmd := []byte{0x30, pageAddress}
	if err := f.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return nil, err
	}
	crc, err := f.calcCRC(cmd)
	if err != nil {
		return nil, err
	}
	full := []byte{cmd[0], cmd[1], crc[0], crc[1]}
	back, err := f.transceive(full)
	if err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return nil, err
	}
	if back == nil || len(back) < 16 {
		return nil, nil
	}
	return back[0:16], nil
}

// WriteUltralightPage writes a 4-byte page (MIFARE Ultralight / NTAG).
// No authentication required.
func (f *MFRC522Full) WriteUltralightPage(pageAddress uint8, data []byte) (bool, error) {
	if len(data) != 4 {
		return false, nil
	}
	buf := make([]byte, 8)
	buf[0] = 0xA2
	buf[1] = pageAddress
	copy(buf[2:6], data)
	if err := f.writeReg(mfrcRegTxMode, 0x80); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x80); err != nil {
		return false, err
	}
	crc, err := f.calcCRC(buf[0:6])
	if err != nil {
		return false, err
	}
	buf[6] = crc[0]
	buf[7] = crc[1]
	back, err := f.transceive(buf)
	if err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegTxMode, 0x00); err != nil {
		return false, err
	}
	if err := f.writeReg(mfrcRegRxMode, 0x00); err != nil {
		return false, err
	}
	return back != nil && len(back) >= 1 && back[0]&0x0F == 0x0A, nil
}

// GenerateRandomID runs the Generate RandomID command and returns the
// 10-byte ID.
func (f *MFRC522Full) GenerateRandomID() ([]byte, error) {
	if err := f.writeReg(mfrcRegCommand, mfrcCmdIdle); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegComIrq, mfrcIRQAll); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegComIrq, 0x14); err != nil {
		return nil, err
	}
	if err := f.writeReg(mfrcRegCommand, mfrcCmdRandomID); err != nil {
		return nil, err
	}
	for i := 0; i < 50; i++ {
		v, err := f.readReg(mfrcRegComIrq)
		if err != nil {
			return nil, err
		}
		if v&mfrcIRQIdle != 0 {
			break
		}
		time.Sleep(mfrcDelay)
	}
	if err := f.writeReg(mfrcRegCommand, mfrcCmdIdle); err != nil {
		return nil, err
	}
	return f.readFIFO(10)
}
