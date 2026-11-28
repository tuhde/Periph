package rfid

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ST25DV register map and timing.
const (
	st25dvSysGPO        = 0x0000
	st25dvSysITTime     = 0x0001
	st25dvSysEHMode     = 0x0002
	st25dvSysRFA1SS     = 0x0004
	st25dvSysENDA1      = 0x0005
	st25dvSysI2CSS      = 0x000B
	st25dvSysLockCCFile = 0x000C
	st25dvSysMBMode     = 0x000D
	st25dvSysMBWDG      = 0x000E
	st25dvSysMemSize    = 0x0014
	st25dvSysUID        = 0x0018
	st25dvSysICRev      = 0x0020
	st25dvSysI2CPwd     = 0x0900

	st25dvDynGPOCtrl = 0x2000
	st25dvDynEHCtrl  = 0x2002
	st25dvDynRFMngt  = 0x2003
	st25dvDynI2CSSO  = 0x2004
	st25dvDynITSts   = 0x2005
	st25dvDynMBCtrl  = 0x2006
	st25dvDynMBLen   = 0x2007
	st25dvMailbox    = 0x2008
	st25dvMailboxMax = 256

	st25dvChunk        = 16
	st25dvWriteTimeout = 50 * time.Millisecond
	st25dvReadRetries  = 3
)

// ST25DV interrupt sources: bit layout of IT_STS_Dyn (0x2005).
const (
	ST25DVSourceRFUser       uint8 = 0x01
	ST25DVSourceRFActivity   uint8 = 0x02
	ST25DVSourceRFInterrupt  uint8 = 0x04
	ST25DVSourceFieldFalling uint8 = 0x08
	ST25DVSourceFieldRising  uint8 = 0x10
	ST25DVSourceRFPutMsg     uint8 = 0x20
	ST25DVSourceRFGetMsg     uint8 = 0x40
	ST25DVSourceRFWrite      uint8 = 0x80
)

// ST25DV GPO / GPO_CTRL_Dyn bits.
const (
	ST25DVGPORFUser      uint8 = 0x01
	ST25DVGPORFActivity  uint8 = 0x02
	ST25DVGPORFInterrupt uint8 = 0x04
	ST25DVGPOFieldChange uint8 = 0x08
	ST25DVGPORFPutMsg    uint8 = 0x10
	ST25DVGPORFGetMsg    uint8 = 0x20
	ST25DVGPORFWrite     uint8 = 0x40
	ST25DVGPOEnable      uint8 = 0x80
)

// ErrST25DVRange is returned for an address range outside the user memory.
var ErrST25DVRange = errors.New("st25dv: range outside the user memory")

// ErrST25DVArgument is returned for an argument outside its documented range.
var ErrST25DVArgument = errors.New("st25dv: argument out of range")

// ErrST25DVTimeout is returned when the chip does not answer again within the
// write-cycle timeout.
var ErrST25DVTimeout = errors.New("st25dv: write cycle did not complete")

// ErrST25DVSessionClosed is returned by WritePassword without an open I²C
// security session.
var ErrST25DVSessionClosed = errors.New("st25dv: security session is closed")

// ErrST25DVMailboxDisabled is returned by MailboxWrite when fast transfer mode is off.
var ErrST25DVMailboxDisabled = errors.New("st25dv: mailbox is disabled")

// ErrST25DVMailboxBusy is returned by MailboxWrite while the mailbox holds a message.
var ErrST25DVMailboxBusy = errors.New("st25dv: mailbox is busy")

// ST25DVMailboxStatus is the decoded MB_CTRL_Dyn register.
type ST25DVMailboxStatus struct {
	Enabled     bool // MB_EN: fast transfer mode active
	HostPut     bool // HOST_PUT_MSG: the host wrote the current message
	RFPut       bool // RF_PUT_MSG: the RF reader wrote the current message
	HostMiss    bool // HOST_MISS_MSG: host message not read before the watchdog
	RFMiss      bool // RF_MISS_MSG: RF message not read before the watchdog
	HostCurrent bool // HOST_CURRENT_MSG: the host is reading the message
	RFCurrent   bool // RF_CURRENT_MSG: the RF reader is reading the message
}

// ST25DVMinimal is the ST25DV04K/16K/64K dynamic NFC tag — minimal interface:
// user memory as a byte store.
//
// The chip answers on two I²C addresses with 16-bit register addresses: 0x53
// (user memory, dynamic registers, mailbox) and 0x57 (system configuration).
// Both connections are RegisterConnections with a 2-byte register width
// (connection.NewI2CConnectionWithWidth(bus, addr, 2, nil, nil)) on the same
// bus. Reads and writes are split into transfers of at most 16 bytes aligned to
// 16-byte boundaries; every write is completed by ACK polling (5 ms per 4-byte
// EEPROM page), and reads are retried up to 3 times while an RF reader holds
// the EEPROM.
type ST25DVMinimal struct {
	user          connection.RegisterConnection
	system        connection.RegisterConnection
	gpoActiveHigh bool
	size          int
	icRef         uint8
}

// NewST25DVMinimal reads MEM_SIZE, BLK_SIZE and IC_REF from the system area and
// derives the user memory size. No configuration is written. gpoActiveHigh is
// true for -JF variants (CMOS GPO, rising edge) and false for -IE variants
// (open drain, falling edge); the spec default is false.
func NewST25DVMinimal(user, system connection.RegisterConnection, gpoActiveHigh bool) (*ST25DVMinimal, error) {
	d := &ST25DVMinimal{user: user, system: system, gpoActiveHigh: gpoActiveHigh}
	id, err := d.readReg(system, st25dvSysMemSize, 4)
	if err != nil {
		return nil, err
	}
	mem := int(id[0]) | int(id[1])<<8
	d.size = (mem + 1) * (int(id[2]) + 1)
	d.icRef = id[3]
	return d, nil
}

func (d *ST25DVMinimal) readReg(c connection.RegisterConnection, reg uint32, n int) ([]byte, error) {
	var err error
	for attempt := 1; ; attempt++ {
		var b []byte
		b, err = c.ReadReg(reg, n)
		if err == nil {
			return b, nil
		}
		if attempt >= st25dvReadRetries {
			return nil, err
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func (d *ST25DVMinimal) ackPoll(c connection.RegisterConnection, timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		if err := c.WriteReg(0, nil); err == nil {
			return true
		}
		if !time.Now().Before(deadline) {
			return false
		}
		time.Sleep(time.Millisecond)
	}
}

func (d *ST25DVMinimal) commit(c connection.RegisterConnection) error {
	if !d.ackPoll(c, st25dvWriteTimeout) {
		return ErrST25DVTimeout
	}
	return nil
}

func (d *ST25DVMinimal) checkRange(address, length int) error {
	if address < 0 || length < 0 || address+length > d.size {
		return ErrST25DVRange
	}
	return nil
}

// Read reads length bytes of user memory starting at address. Returns
// ErrST25DVRange if the range lies outside the memory.
func (d *ST25DVMinimal) Read(address, length int) ([]byte, error) {
	if err := d.checkRange(address, length); err != nil {
		return nil, err
	}
	out := make([]byte, 0, length)
	for length > 0 {
		n := st25dvChunk - address%st25dvChunk
		if n > length {
			n = length
		}
		b, err := d.readReg(d.user, uint32(address), n)
		if err != nil {
			return nil, err
		}
		out = append(out, b...)
		address += n
		length -= n
	}
	return out, nil
}

// Write writes data to user memory in 16-byte-aligned pieces, ACK-polling after
// each. Returns ErrST25DVRange outside the memory, the bus error on a NACK
// (protected area, mailbox active) and ErrST25DVTimeout if a write cycle does
// not complete.
func (d *ST25DVMinimal) Write(address int, data []byte) error {
	if err := d.checkRange(address, len(data)); err != nil {
		return err
	}
	for off := 0; off < len(data); {
		a := address + off
		n := st25dvChunk - a%st25dvChunk
		if n > len(data)-off {
			n = len(data) - off
		}
		if err := d.user.WriteReg(uint32(a), data[off:off+n]); err != nil {
			return err
		}
		if err := d.commit(d.user); err != nil {
			return err
		}
		off += n
	}
	return nil
}

// ST25DVFull extends ST25DVMinimal with identification, system area, password
// session, memory areas, GPO interrupts, energy harvesting, RF state, mailbox
// and a minimal NDEF writer.
//
// System-area writes need an open I²C security session (PresentPassword). Bits
// 0-6 of GPO_CTRL_Dyn are read-only over I²C, so interrupt sources are
// configured in the static GPO register (session required); only GPO_EN
// switches at runtime without a session.
type ST25DVFull struct {
	ST25DVMinimal
	mu          sync.Mutex
	callback    func(status uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewST25DVFull creates the full driver; same arguments as NewST25DVMinimal.
func NewST25DVFull(user, system connection.RegisterConnection, gpoActiveHigh bool) (*ST25DVFull, error) {
	m, err := NewST25DVMinimal(user, system, gpoActiveHigh)
	if err != nil {
		return nil, err
	}
	return &ST25DVFull{ST25DVMinimal: *m}, nil
}

// MemorySize returns the user memory size in bytes ((MEM_SIZE + 1) × (BLK_SIZE + 1)).
func (d *ST25DVFull) MemorySize() int { return d.size }

// ICReference returns IC_REF: 0x24 (ST25DV04K) or 0x26 (ST25DV16K/64K).
func (d *ST25DVFull) ICReference() uint8 { return d.icRef }

// UID reads the 64-bit unique ID, most significant byte first (E0 02 …).
func (d *ST25DVFull) UID() ([]byte, error) {
	raw, err := d.readReg(d.system, st25dvSysUID, 8)
	if err != nil {
		return nil, err
	}
	out := make([]byte, 8)
	for i := range out {
		out[i] = raw[7-i]
	}
	return out, nil
}

// ICRevision reads IC_REV.
func (d *ST25DVFull) ICRevision() (uint8, error) { return d.sysByte(st25dvSysICRev) }

// ReadSystem reads length raw bytes of the system configuration area (device 0x57).
func (d *ST25DVFull) ReadSystem(address uint16, length int) ([]byte, error) {
	return d.readReg(d.system, uint32(address), length)
}

// WriteSystem writes raw bytes to the system area, then ACK-polls (session required).
func (d *ST25DVFull) WriteSystem(address uint16, data []byte) error {
	if err := d.system.WriteReg(uint32(address), data); err != nil {
		return err
	}
	return d.commit(d.system)
}

func (d *ST25DVFull) sysByte(reg uint32) (uint8, error) {
	b, err := d.readReg(d.system, reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *ST25DVFull) dynByte(reg uint32) (uint8, error) {
	b, err := d.readReg(d.user, reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *ST25DVFull) writeDyn(reg uint32, value uint8) error {
	return d.user.WriteReg(reg, []byte{value})
}

func st25dvPasswordFrame(password [8]byte, code byte) []byte {
	f := make([]byte, 0, 17)
	f = append(f, password[:]...)
	f = append(f, code)
	return append(f, password[:]...)
}

// PresentPassword presents the I²C password (most significant byte first,
// factory 00 × 8) and returns whether the security session is open afterwards.
func (d *ST25DVFull) PresentPassword(password [8]byte) (bool, error) {
	if err := d.system.WriteReg(st25dvSysI2CPwd, st25dvPasswordFrame(password, 0x09)); err != nil {
		return false, err
	}
	d.ackPoll(d.system, st25dvWriteTimeout)
	return d.SessionOpen()
}

// WritePassword changes the I²C password; returns ErrST25DVSessionClosed
// without an open session.
func (d *ST25DVFull) WritePassword(password [8]byte) error {
	open, err := d.SessionOpen()
	if err != nil {
		return err
	}
	if !open {
		return ErrST25DVSessionClosed
	}
	if err := d.system.WriteReg(st25dvSysI2CPwd, st25dvPasswordFrame(password, 0x07)); err != nil {
		return err
	}
	return d.commit(d.system)
}

// SessionOpen reports I2C_SSO_Dyn bit 0: the I²C security session is open.
func (d *ST25DVFull) SessionOpen() (bool, error) {
	v, err := d.dynByte(st25dvDynI2CSSO)
	return v&0x01 != 0, err
}

// SetAreaEnd sets ENDA1..3 (area 1-3, session required); area n ends at byte
// endBlock × 32 + 31.
func (d *ST25DVFull) SetAreaEnd(area int, endBlock uint8) error {
	if area < 1 || area > 3 {
		return ErrST25DVArgument
	}
	return d.WriteSystem(uint16(st25dvSysENDA1+2*(area-1)), []byte{endBlock})
}

// SetAreaProtection sets RFAnSS (rf 0-15: bits 3:2 RW protection, bits 1:0 RF
// password) and the area's I2CSS level (i2c 0-3: bit 0 write, bit 1 read
// protection) for area 1-4 (session required).
func (d *ST25DVFull) SetAreaProtection(area int, rf, i2c uint8) error {
	if area < 1 || area > 4 || rf > 0x0F || i2c > 3 {
		return ErrST25DVArgument
	}
	if err := d.WriteSystem(uint16(st25dvSysRFA1SS+2*(area-1)), []byte{rf}); err != nil {
		return err
	}
	shift := uint(2 * (area - 1))
	i2css, err := d.sysByte(st25dvSysI2CSS)
	if err != nil {
		return err
	}
	i2css = (i2css &^ (0x03 << shift)) | (i2c << shift)
	return d.WriteSystem(st25dvSysI2CSS, []byte{i2css})
}

// GetAreaProtection reads the (rf, i2c) protection of area 1-4, as for SetAreaProtection.
func (d *ST25DVFull) GetAreaProtection(area int) (rf, i2c uint8, err error) {
	if area < 1 || area > 4 {
		return 0, 0, ErrST25DVArgument
	}
	r, err := d.sysByte(uint32(st25dvSysRFA1SS + 2*(area-1)))
	if err != nil {
		return 0, 0, err
	}
	s, err := d.sysByte(st25dvSysI2CSS)
	if err != nil {
		return 0, 0, err
	}
	return r & 0x0F, (s >> uint(2*(area-1))) & 0x03, nil
}

// SetGPO writes the static GPO register (session required): sources
// (ST25DVGPO* bits 0-6) plus GPO_EN when enable. The chip copies it into GPO_CTRL_Dyn.
func (d *ST25DVFull) SetGPO(sources uint8, enable bool) error {
	v := sources & 0x7F
	if enable {
		v |= ST25DVGPOEnable
	}
	return d.WriteSystem(st25dvSysGPO, []byte{v})
}

// GetGPO reads the static GPO register.
func (d *ST25DVFull) GetGPO() (uint8, error) { return d.sysByte(st25dvSysGPO) }

// SetGPORuntime switches the GPO output at runtime (GPO_CTRL_Dyn.GPO_EN, no
// session; bits 0-6 are read-only over I²C).
func (d *ST25DVFull) SetGPORuntime(enable bool) error {
	var v uint8
	if enable {
		v = ST25DVGPOEnable
	}
	return d.writeDyn(st25dvDynGPOCtrl, v)
}

// SetInterruptPulse sets IT_TIME 0-7 (session required); pulse = 301 µs − value × 37.65 µs.
func (d *ST25DVFull) SetInterruptPulse(value uint8) error {
	if value > 7 {
		return ErrST25DVArgument
	}
	return d.WriteSystem(st25dvSysITTime, []byte{value})
}

// FieldPresent reports EH_CTRL_Dyn.FIELD_ON: an RF field is present.
func (d *ST25DVFull) FieldPresent() (bool, error) {
	v, err := d.dynByte(st25dvDynEHCtrl)
	return v&0x04 != 0, err
}

// SetEnergyHarvesting sets EH_CTRL_Dyn.EH_EN; EH_MODE (forced after power-up
// instead of on demand) is written only when it changes, which needs a session.
func (d *ST25DVFull) SetEnergyHarvesting(enabled, forced bool) error {
	var mode uint8 = 1
	if forced {
		mode = 0
	}
	cur, err := d.sysByte(st25dvSysEHMode)
	if err != nil {
		return err
	}
	if cur&0x01 != mode {
		if err := d.WriteSystem(st25dvSysEHMode, []byte{mode}); err != nil {
			return err
		}
	}
	var v uint8
	if enabled {
		v = 0x01
	}
	return d.writeDyn(st25dvDynEHCtrl, v)
}

// SetRFState sets the runtime RF state RF_MNGT_Dyn (RF_DISABLE, RF_SLEEP); the
// static RF_MNGT is unchanged.
func (d *ST25DVFull) SetRFState(disable, sleep bool) error {
	var v uint8
	if disable {
		v |= 0x01
	}
	if sleep {
		v |= 0x02
	}
	return d.writeDyn(st25dvDynRFMngt, v)
}

// EnableMailbox sets MB_MODE if needed (session required), then MB_CTRL_Dyn.MB_EN.
func (d *ST25DVFull) EnableMailbox() error {
	mode, err := d.sysByte(st25dvSysMBMode)
	if err != nil {
		return err
	}
	if mode&0x01 == 0 {
		if err := d.WriteSystem(st25dvSysMBMode, []byte{0x01}); err != nil {
			return err
		}
	}
	return d.writeDyn(st25dvDynMBCtrl, 0x01)
}

// DisableMailbox clears MB_CTRL_Dyn.MB_EN (MB_MODE stays).
func (d *ST25DVFull) DisableMailbox() error { return d.writeDyn(st25dvDynMBCtrl, 0x00) }

// MailboxStatus decodes MB_CTRL_Dyn.
func (d *ST25DVFull) MailboxStatus() (ST25DVMailboxStatus, error) {
	v, err := d.dynByte(st25dvDynMBCtrl)
	if err != nil {
		return ST25DVMailboxStatus{}, err
	}
	return ST25DVMailboxStatus{
		Enabled:     v&0x01 != 0,
		HostPut:     v&0x02 != 0,
		RFPut:       v&0x04 != 0,
		HostMiss:    v&0x10 != 0,
		RFMiss:      v&0x20 != 0,
		HostCurrent: v&0x40 != 0,
		RFCurrent:   v&0x80 != 0,
	}, nil
}

// MailboxWrite puts a 1-256 byte message into the mailbox (one write starting
// at 0x2008). Returns ErrST25DVMailboxDisabled or ErrST25DVMailboxBusy when the
// mailbox cannot take a message.
func (d *ST25DVFull) MailboxWrite(data []byte) error {
	if len(data) < 1 || len(data) > st25dvMailboxMax {
		return ErrST25DVArgument
	}
	ctrl, err := d.dynByte(st25dvDynMBCtrl)
	if err != nil {
		return err
	}
	if ctrl&0x01 == 0 {
		return ErrST25DVMailboxDisabled
	}
	if ctrl&0x06 != 0 {
		return ErrST25DVMailboxBusy
	}
	return d.user.WriteReg(st25dvMailbox, data)
}

// MailboxRead reads the mailbox message (MB_LEN_Dyn + 1 bytes); empty when no
// message is present.
func (d *ST25DVFull) MailboxRead() ([]byte, error) {
	ctrl, err := d.dynByte(st25dvDynMBCtrl)
	if err != nil {
		return nil, err
	}
	if ctrl&0x06 == 0 {
		return []byte{}, nil
	}
	l, err := d.dynByte(st25dvDynMBLen)
	if err != nil {
		return nil, err
	}
	length := int(l) + 1
	out := make([]byte, 0, length)
	for off := 0; off < length; off += st25dvChunk {
		n := length - off
		if n > st25dvChunk {
			n = st25dvChunk
		}
		b, err := d.readReg(d.user, uint32(st25dvMailbox+off), n)
		if err != nil {
			return nil, err
		}
		out = append(out, b...)
	}
	return out, nil
}

// SetMailboxWatchdog sets MB_WDG 0-7 (session required); watchdog = 2^(value − 1) × 30 ms, 0 disables it.
func (d *ST25DVFull) SetMailboxWatchdog(value uint8) error {
	if value > 7 {
		return ErrST25DVArgument
	}
	return d.WriteSystem(st25dvSysMBWDG, []byte{value})
}

// LockCCFile writes LOCK_CCFILE (session required): RF write protection of blocks 0 and 1.
func (d *ST25DVFull) LockCCFile(lock bool) error {
	var v uint8
	if lock {
		v = 0x03
	}
	return d.WriteSystem(st25dvSysLockCCFile, []byte{v})
}

func (d *ST25DVFull) capabilityContainer() []byte {
	if d.size <= 2048 {
		mlen := d.size / 8
		if mlen > 0xFF {
			mlen = 0xFF
		}
		return []byte{0xE1, 0x40, byte(mlen), 0x01}
	}
	mlen := d.size/8 - 1
	return []byte{0xE2, 0x40, 0x00, 0x01, 0x00, 0x00, byte(mlen >> 8), byte(mlen)}
}

// WriteNDEF writes a Type 5 capability container and one NDEF TLV (03 len … FE;
// 03 FF hi lo … for 255 bytes and more). Returns ErrST25DVRange if it does not fit.
func (d *ST25DVFull) WriteNDEF(message []byte) error {
	image := d.capabilityContainer()
	if len(message) < 0xFF {
		image = append(image, 0x03, byte(len(message)))
	} else {
		image = append(image, 0x03, 0xFF, byte(len(message)>>8), byte(len(message)))
	}
	image = append(image, message...)
	image = append(image, 0xFE)
	if len(image) > d.size {
		return ErrST25DVRange
	}
	return d.Write(0, image)
}

// ReadNDEF reads the message of the first NDEF TLV; empty when no capability
// container or NDEF TLV is found.
func (d *ST25DVFull) ReadNDEF() ([]byte, error) {
	cc, err := d.Read(0, 4)
	if err != nil {
		return nil, err
	}
	var pos int
	switch cc[0] {
	case 0xE1:
		pos = 4
	case 0xE2:
		pos = 8
	default:
		return []byte{}, nil
	}
	for pos < d.size {
		t, err := d.Read(pos, 1)
		if err != nil {
			return nil, err
		}
		if t[0] == 0x00 {
			pos++
			continue
		}
		if t[0] == 0xFE || pos+2 > d.size {
			return []byte{}, nil
		}
		l, err := d.Read(pos+1, 1)
		if err != nil {
			return nil, err
		}
		pos += 2
		length := int(l[0])
		if l[0] == 0xFF {
			if pos+2 > d.size {
				return []byte{}, nil
			}
			lb, err := d.Read(pos, 2)
			if err != nil {
				return nil, err
			}
			length = int(lb[0])<<8 | int(lb[1])
			pos += 2
		}
		if t[0] == 0x03 {
			if pos+length > d.size {
				return []byte{}, nil
			}
			return d.Read(pos, length)
		}
		pos += length
	}
	return []byte{}, nil
}

// WaitWriteComplete ACK-polls the user address until the write cycle has
// finished; ErrST25DVTimeout otherwise.
func (d *ST25DVFull) WaitWriteComplete() error { return d.commit(d.user) }

func st25dvSourceToGPO(source uint8) uint8 {
	gpo := source & 0x07
	if source&(ST25DVSourceFieldRising|ST25DVSourceFieldFalling) != 0 {
		gpo |= ST25DVGPOFieldChange
	}
	return gpo | (source>>1)&0x70
}

// EnableInterrupt enables ST25DVSource* sources in the static GPO register plus
// GPO_EN (session required). FieldRising and FieldFalling share FIELD_CHANGE_EN.
func (d *ST25DVFull) EnableInterrupt(source uint8) error {
	gpo, err := d.GetGPO()
	if err != nil {
		return err
	}
	if err := d.WriteSystem(st25dvSysGPO, []byte{gpo | st25dvSourceToGPO(source) | ST25DVGPOEnable}); err != nil {
		return err
	}
	return d.SetGPORuntime(true)
}

// DisableInterrupt clears ST25DVSource* sources in the static GPO register (session required).
func (d *ST25DVFull) DisableInterrupt(source uint8) error {
	gpo, err := d.GetGPO()
	if err != nil {
		return err
	}
	return d.WriteSystem(st25dvSysGPO, []byte{gpo &^ st25dvSourceToGPO(source)})
}

// PollInterrupt reads IT_STS_Dyn (cleared on read) and returns the ST25DVSource* mask, 0 = none.
func (d *ST25DVFull) PollInterrupt() (uint8, error) { return d.dynByte(st25dvDynITSts) }

// OnInterrupt subscribes callback; it receives the ST25DVSource* mask. With the
// user connection's IntPin wired it fires on the falling edge (-IE) or rising
// edge (-JF, gpoActiveHigh); otherwise a polling goroutine checks IT_STS_Dyn.
func (d *ST25DVFull) OnInterrupt(callback func(status uint8)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()
	ip := d.user.IntPin()
	if ip == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		ip = poll
	}
	trigger := connection.Falling
	if d.gpoActiveHigh {
		trigger = connection.Rising
	}
	unsub := ip.OnEdge(trigger, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *ST25DVFull) OffInterrupt() error {
	d.mu.Lock()
	unsub, poll := d.unsubscribe, d.pollPin
	d.unsubscribe, d.pollPin, d.callback = nil, nil, nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *ST25DVFull) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil || status == 0 {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
