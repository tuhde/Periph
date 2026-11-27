package rfid

import (
	"bytes"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// PN532 frame bytes, command codes and driver limits.
const (
	pn532TFIHost  byte = 0xD4
	pn532TFIChip  byte = 0xD5
	pn532TFIError byte = 0x7F

	pn532CmdDiagnose            byte = 0x00
	pn532CmdGetFirmwareVersion  byte = 0x02
	pn532CmdGetGeneralStatus    byte = 0x04
	pn532CmdReadRegister        byte = 0x06
	pn532CmdWriteRegister       byte = 0x08
	pn532CmdSAMConfiguration    byte = 0x14
	pn532CmdPowerDown           byte = 0x16
	pn532CmdRFConfiguration     byte = 0x32
	pn532CmdInDataExchange      byte = 0x40
	pn532CmdInCommunicateThru   byte = 0x42
	pn532CmdInDeselect          byte = 0x44
	pn532CmdInListPassiveTarget byte = 0x4A
	pn532CmdInRelease           byte = 0x52
	pn532CmdInSelect            byte = 0x54

	pn532IC           byte = 0x32
	pn532WriteRetries      = 10
	pn532AckTimeout        = 15 * time.Millisecond
	pn532AckAttempts       = 3
)

// PN532 I²C address and InListPassiveTarget baud rates.
const (
	// PN532Address is the fixed 7-bit I²C address.
	PN532Address uint8 = 0x24
	// PN532Baud106A is 106 kbps ISO/IEC 14443 A.
	PN532Baud106A uint8 = 0x00
	// PN532Baud212F is 212 kbps FeliCa.
	PN532Baud212F uint8 = 0x01
	// PN532Baud424F is 424 kbps FeliCa.
	PN532Baud424F uint8 = 0x02
	// PN532Baud106B is 106 kbps ISO/IEC 14443 B.
	PN532Baud106B uint8 = 0x03
)

var (
	pn532Ack     = []byte{0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00}
	pn532Nack    = []byte{0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00}
	pn532Pattern = []byte("PERIPH01")

	// ErrPN532NoAck is returned when the chip sent no ACK frame after 3 attempts.
	ErrPN532NoAck = errors.New("pn532: no ACK")
	// ErrPN532Timeout is returned when no response arrived in time (the command was aborted).
	ErrPN532Timeout = errors.New("pn532: response timeout")
	// ErrPN532Frame is returned for a bad preamble, checksum, TFI, response code or length.
	ErrPN532Frame = errors.New("pn532: bad frame")
	// ErrPN532ErrorFrame is returned when the chip answered an application-level error frame.
	ErrPN532ErrorFrame = errors.New("pn532: error frame")
)

// PN532StatusError carries a non-zero PN532 status code (Status & 0x3F), e.g. 0x14 for a
// MIFARE authentication error.
type PN532StatusError struct {
	Code    uint8
	Command byte
}

func (e *PN532StatusError) Error() string {
	return fmt.Sprintf("pn532: status error 0x%02X (command 0x%02X)", e.Code, e.Command)
}

// PN532FirmwareVersion is the GetFirmwareVersion result.
type PN532FirmwareVersion struct {
	IC, Version, Revision, Support uint8
	ISO14443A, ISO14443B, ISO18092 bool
}

// PN532GeneralStatus is the decoded GetGeneralStatus (per-target entries are skipped).
type PN532GeneralStatus struct {
	Error     uint8
	Field     bool
	Targets   uint8
	SAMStatus uint8
}

// PN532Target is a target found by InListPassiveTarget. UID is the NFCID1 (type A), NFCID2
// (FeliCa) or PUPI (type B); ATS is the ATS (type A), PAD + system code (FeliCa) or
// ATTRIB_RES (type B). SensRes and SelRes are 0 for FeliCa and type B.
type PN532Target struct {
	Tg      uint8
	SensRes uint16
	SelRes  uint8
	UID     []byte
	ATS     []byte
}

// PN532Minimal detects one ISO/IEC 14443 A card at 106 kbps and returns its UID.
//
// The host exchanges frames with the PN532 firmware: a command frame (TFI 0xD4), the chip's
// ACK frame and a response frame (TFI 0xD5, code + 1). Every I²C read starts with a status
// byte (bit 0 = frame ready); a 1-byte read does not consume the frame. The driver reads the
// 6-byte header, sends a NACK so the chip resends the response, then reads 1 + LEN + 7 bytes.
// Writes are retried up to 10 times (2 ms apart) to wake the chip; the ACK is awaited 15 ms
// and the frame resent up to 3 times. Wire I0 low and I1 high; address 0x24.
type PN532Minimal struct {
	conn connection.Connection
	mu   sync.Mutex
}

// NewPN532Minimal creates the minimal driver; it does no bus access.
func NewPN532Minimal(conn connection.Connection) (*PN532Minimal, error) {
	return &PN532Minimal{conn: conn}, nil
}

// Init wakes the chip, checks GetFirmwareVersion (IC 0x32), sets SAMConfiguration normal
// mode with the IRQ pin enabled and MxRtyPassiveActivation = 2.
func (d *PN532Minimal) Init() error {
	v, err := d.command(pn532CmdGetFirmwareVersion, nil, time.Second)
	if err != nil {
		return err
	}
	if len(v) < 4 || v[0] != pn532IC {
		ic := byte(0)
		if len(v) > 0 {
			ic = v[0]
		}
		return fmt.Errorf("pn532: unexpected IC 0x%02X, expected 0x32", ic)
	}
	if _, err := d.command(pn532CmdSAMConfiguration, []byte{0x01, 0x14, 0x01}, time.Second); err != nil {
		return err
	}
	_, err = d.command(pn532CmdRFConfiguration, []byte{0x05, 0xFF, 0x01, 0x02}, time.Second)
	return err
}

// ReadUID detects one type A card and returns its NFCID1 (4, 7 or 10 bytes); the target is
// released. It returns nil when no card answered or no response arrived within timeout
// (the command is then aborted). The spec default timeout is 1 s.
func (d *PN532Minimal) ReadUID(timeout time.Duration) ([]byte, error) {
	d.mu.Lock()
	r, err := d.list(1, PN532Baud106A, nil, timeout)
	d.mu.Unlock()
	if err != nil || r == nil || len(r) < 6 || r[0] == 0 {
		return nil, err
	}
	n := int(r[5])
	if 6+n > len(r) {
		return nil, ErrPN532Frame
	}
	uid := append([]byte(nil), r[6:6+n]...)
	if _, err := d.command(pn532CmdInRelease, []byte{r[1]}, time.Second); err != nil {
		return nil, err
	}
	return uid, nil
}

func (d *PN532Minimal) list(maxTargets, baud uint8, initiatorData []byte, timeout time.Duration) ([]byte, error) {
	p := append([]byte{maxTargets, baud}, initiatorData...)
	if err := d.start(pn532CmdInListPassiveTarget, p); err != nil {
		return nil, err
	}
	r, err := d.response(pn532CmdInListPassiveTarget, timeout)
	if errors.Is(err, ErrPN532Timeout) {
		return nil, nil
	}
	return r, err
}

func (d *PN532Minimal) write(data []byte) error {
	var err error
	for i := 0; i < pn532WriteRetries; i++ {
		if err = d.conn.Write(data); err == nil {
			return nil
		}
		time.Sleep(2 * time.Millisecond)
	}
	return err
}

func (d *PN532Minimal) ready() (bool, error) {
	s, err := d.conn.Read(1)
	if err != nil {
		return false, err
	}
	return len(s) > 0 && s[0]&0x01 != 0, nil
}

func (d *PN532Minimal) wait(timeout time.Duration) (bool, error) {
	polls := int(timeout/time.Millisecond) + 1
	for i := 0; i < polls; i++ {
		ok, err := d.ready()
		if err != nil {
			return false, err
		}
		if ok {
			return true, nil
		}
		time.Sleep(time.Millisecond)
	}
	return false, nil
}

func pn532Frame(command byte, data []byte) []byte {
	length := byte(len(data) + 2)
	sum := pn532TFIHost + command
	f := []byte{0x00, 0x00, 0xFF, length, -length, pn532TFIHost, command}
	for _, b := range data {
		sum += b
	}
	f = append(f, data...)
	return append(f, -sum, 0x00)
}

func (d *PN532Minimal) start(command byte, data []byte) error {
	if len(data) > 252 {
		return errors.New("pn532: command data too long")
	}
	frame := pn532Frame(command, data)
	for i := 0; i < pn532AckAttempts; i++ {
		if err := d.write(frame); err != nil {
			return err
		}
		ok, err := d.wait(pn532AckTimeout)
		if err != nil {
			return err
		}
		if ok {
			ack, err := d.conn.Read(7)
			if err != nil {
				return err
			}
			if len(ack) == 7 && bytes.Equal(ack[1:7], pn532Ack) {
				return nil
			}
		}
	}
	return ErrPN532NoAck
}

func (d *PN532Minimal) response(command byte, timeout time.Duration) ([]byte, error) {
	ok, err := d.wait(timeout)
	if err != nil {
		return nil, err
	}
	if !ok {
		if err := d.write(pn532Ack); err != nil { // abort the running command
			return nil, err
		}
		return nil, ErrPN532Timeout
	}
	header, err := d.conn.Read(6)
	if err != nil {
		return nil, err
	}
	if len(header) < 6 || !bytes.Equal(header[1:4], []byte{0x00, 0x00, 0xFF}) || header[4]+header[5] != 0 {
		return nil, ErrPN532Frame
	}
	length := int(header[4])
	if err := d.write(pn532Nack); err != nil { // have the chip resend the whole frame
		return nil, err
	}
	if ok, err = d.wait(timeout); err != nil {
		return nil, err
	} else if !ok {
		return nil, ErrPN532Timeout
	}
	frame, err := d.conn.Read(1 + length + 7)
	if err != nil {
		return nil, err
	}
	if len(frame) < 1+length+7 {
		return nil, ErrPN532Frame
	}
	body := frame[6 : 6+length]
	if length == 1 && body[0] == pn532TFIError {
		return nil, ErrPN532ErrorFrame
	}
	if length < 2 || body[0] != pn532TFIChip || body[1] != command+1 {
		return nil, ErrPN532Frame
	}
	sum := frame[6+length]
	for _, b := range body {
		sum += b
	}
	if sum != 0 {
		return nil, ErrPN532Frame
	}
	return append([]byte(nil), body[2:]...), nil
}

func (d *PN532Minimal) command(command byte, data []byte, timeout time.Duration) ([]byte, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if err := d.start(command, data); err != nil {
		return nil, err
	}
	return d.response(command, timeout)
}

// PN532Full adds target listing at every baud rate, data exchange, MIFARE Classic and NTAG
// helpers, RF and retry configuration, register access, power-down, the self test, raw
// commands and the IRQ interrupt API.
type PN532Full struct {
	PN532Minimal

	imu         sync.Mutex
	callback    func()
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	wasReady    bool
}

// NewPN532Full creates the full driver; it does no bus access.
func NewPN532Full(conn connection.Connection) (*PN532Full, error) {
	return &PN532Full{PN532Minimal: PN532Minimal{conn: conn}}, nil
}

// FirmwareVersion runs GetFirmwareVersion.
func (d *PN532Full) FirmwareVersion() (PN532FirmwareVersion, error) {
	v, err := d.command(pn532CmdGetFirmwareVersion, nil, time.Second)
	if err != nil {
		return PN532FirmwareVersion{}, err
	}
	if len(v) < 4 {
		return PN532FirmwareVersion{}, ErrPN532Frame
	}
	return PN532FirmwareVersion{IC: v[0], Version: v[1], Revision: v[2], Support: v[3],
		ISO14443A: v[3]&0x01 != 0, ISO14443B: v[3]&0x02 != 0, ISO18092: v[3]&0x04 != 0}, nil
}

// GeneralStatus runs GetGeneralStatus.
func (d *PN532Full) GeneralStatus() (PN532GeneralStatus, error) {
	v, err := d.command(pn532CmdGetGeneralStatus, nil, time.Second)
	if err != nil {
		return PN532GeneralStatus{}, err
	}
	if len(v) < 3 {
		return PN532GeneralStatus{}, ErrPN532Frame
	}
	s := PN532GeneralStatus{Error: v[0], Field: v[1] != 0, Targets: v[2]}
	if i := 3 + 4*int(v[2]); i < len(v) {
		s.SAMStatus = v[i]
	}
	return s, nil
}

// ListPassiveTargets runs InListPassiveTarget (spec defaults: baud PN532Baud106A, maxTargets
// 1, no initiator data, timeout 1 s). It returns an empty slice when no target answered or on
// a timeout, which aborts the command.
func (d *PN532Full) ListPassiveTargets(baud, maxTargets uint8, initiatorData []byte, timeout time.Duration) ([]PN532Target, error) {
	if maxTargets < 1 || maxTargets > 2 || baud > 4 {
		return nil, errors.New("pn532: invalid baud or maxTargets")
	}
	d.mu.Lock()
	r, err := d.list(maxTargets, baud, initiatorData, timeout)
	d.mu.Unlock()
	if err != nil || len(r) == 0 {
		return []PN532Target{}, err
	}
	targets := []PN532Target{}
	n := len(r)
	i := 1
	for k := 0; k < int(r[0]) && i < n; k++ {
		t := PN532Target{Tg: r[i]}
		switch baud {
		case PN532Baud106A:
			if i+4 >= n {
				return targets, ErrPN532Frame
			}
			t.SensRes = uint16(r[i+1])<<8 | uint16(r[i+2])
			t.SelRes = r[i+3]
			l := int(r[i+4])
			if i+5+l > n {
				return targets, ErrPN532Frame
			}
			t.UID = append([]byte(nil), r[i+5:i+5+l]...)
			i += 5 + l
			t.ATS = []byte{}
			if t.SelRes&0x20 != 0 && i < n {
				tl := int(r[i])
				end := i + tl
				if end > n {
					end = n
				}
				t.ATS = append([]byte(nil), r[i:end]...)
				i += tl
			}
		case PN532Baud212F, PN532Baud424F:
			if i+1 >= n {
				return targets, ErrPN532Frame
			}
			l := int(r[i+1])
			if l < 10 || i+1+l > n {
				return targets, ErrPN532Frame
			}
			t.UID = append([]byte(nil), r[i+3:i+11]...)
			t.ATS = append([]byte(nil), r[i+11:i+1+l]...)
			i += 1 + l
		default:
			if i+13 >= n {
				return targets, ErrPN532Frame
			}
			l := int(r[i+13])
			if i+14+l > n {
				return targets, ErrPN532Frame
			}
			t.UID = append([]byte(nil), r[i+2:i+6]...)
			t.ATS = append([]byte(nil), r[i+14:i+14+l]...)
			i += 14 + l
		}
		targets = append(targets, t)
	}
	return targets, nil
}

func (d *PN532Full) statusCommand(command byte, data []byte) ([]byte, error) {
	v, err := d.command(command, data, time.Second)
	if err != nil {
		return nil, err
	}
	if len(v) == 0 {
		return nil, ErrPN532Frame
	}
	if code := v[0] & 0x3F; code != 0 {
		return nil, &PN532StatusError{Code: code, Command: command}
	}
	return v[1:], nil
}

// DataExchange runs InDataExchange with an activated target; a non-zero status returns a
// *PN532StatusError.
func (d *PN532Full) DataExchange(target uint8, data []byte) ([]byte, error) {
	return d.statusCommand(pn532CmdInDataExchange, append([]byte{target}, data...))
}

// CommunicateThru runs InCommunicateThru: a raw RF exchange with the current target.
func (d *PN532Full) CommunicateThru(data []byte) ([]byte, error) {
	return d.statusCommand(pn532CmdInCommunicateThru, data)
}

// Release runs InRelease; target 0 releases all.
func (d *PN532Full) Release(target uint8) error {
	_, err := d.statusCommand(pn532CmdInRelease, []byte{target})
	return err
}

// Select runs InSelect on a listed target.
func (d *PN532Full) Select(target uint8) error {
	_, err := d.statusCommand(pn532CmdInSelect, []byte{target})
	return err
}

// Deselect runs InDeselect (keeps the target's information); target 0 = all.
func (d *PN532Full) Deselect(target uint8) error {
	_, err := d.statusCommand(pn532CmdInDeselect, []byte{target})
	return err
}

// MifareAuthenticate authenticates a MIFARE Classic sector (0x60 key A, 0x61 key B) using
// the last 4 bytes of uid.
func (d *PN532Full) MifareAuthenticate(target, block uint8, key [6]byte, uid []byte, keyB bool) error {
	if len(uid) < 4 {
		return errors.New("pn532: uid must have at least 4 bytes")
	}
	cmd := byte(0x60)
	if keyB {
		cmd = 0x61
	}
	p := append([]byte{cmd, block}, key[:]...)
	p = append(p, uid[len(uid)-4:]...)
	_, err := d.DataExchange(target, p)
	return err
}

// MifareReadBlock reads a 16-byte MIFARE Classic block (0x30).
func (d *PN532Full) MifareReadBlock(target, block uint8) ([16]byte, error) {
	var out [16]byte
	v, err := d.DataExchange(target, []byte{0x30, block})
	if err != nil {
		return out, err
	}
	if len(v) < 16 {
		return out, ErrPN532Frame
	}
	copy(out[:], v)
	return out, nil
}

// MifareWriteBlock writes a 16-byte MIFARE Classic block (0xA0).
func (d *PN532Full) MifareWriteBlock(target, block uint8, data [16]byte) error {
	_, err := d.DataExchange(target, append([]byte{0xA0, block}, data[:]...))
	return err
}

// NTAGReadPage reads four NTAG / Ultralight pages starting at page (0x30), 16 bytes.
func (d *PN532Full) NTAGReadPage(target, page uint8) ([16]byte, error) {
	return d.MifareReadBlock(target, page)
}

// NTAGWritePage writes one 4-byte NTAG / Ultralight page (0xA2).
func (d *PN532Full) NTAGWritePage(target, page uint8, data [4]byte) error {
	_, err := d.DataExchange(target, append([]byte{0xA2, page}, data[:]...))
	return err
}

// SetRFField runs RFConfiguration item 0x01: RF field on/off and auto RF collision avoidance.
func (d *PN532Full) SetRFField(on, autoRFCA bool) error {
	v := byte(0)
	if on {
		v |= 0x02
	}
	if autoRFCA {
		v |= 0x01
	}
	_, err := d.command(pn532CmdRFConfiguration, []byte{0x01, v}, time.Second)
	return err
}

// SetMaxRetries runs RFConfiguration item 0x05; 0xFF = infinite (spec defaults:
// passiveActivation 2, atr 0xFF, psl 1).
func (d *PN532Full) SetMaxRetries(passiveActivation, atr, psl uint8) error {
	_, err := d.command(pn532CmdRFConfiguration, []byte{0x05, atr, psl, passiveActivation}, time.Second)
	return err
}

// ReadRegister runs ReadRegister (16-bit address).
func (d *PN532Full) ReadRegister(address uint16) (uint8, error) {
	v, err := d.command(pn532CmdReadRegister, []byte{byte(address >> 8), byte(address)}, time.Second)
	if err != nil {
		return 0, err
	}
	if len(v) < 1 {
		return 0, ErrPN532Frame
	}
	return v[0], nil
}

// WriteRegister runs WriteRegister (16-bit address).
func (d *PN532Full) WriteRegister(address uint16, value uint8) error {
	_, err := d.command(pn532CmdWriteRegister, []byte{byte(address >> 8), byte(address), value}, time.Second)
	return err
}

// PowerDown runs PowerDown with WakeUpEnable (spec default 0x20 = I²C); the next transaction
// wakes the chip.
func (d *PN532Full) PowerDown(wakeupSources uint8) error {
	_, err := d.statusCommand(pn532CmdPowerDown, []byte{wakeupSources})
	return err
}

// SelfTest runs Diagnose: test 0x00 (communication line) echoes an 8-byte pattern, any other
// test passes when the first response byte is 0x00 (ROM 0x01, RAM 0x02).
func (d *PN532Full) SelfTest(test uint8) (bool, error) {
	if test == 0x00 {
		req := append([]byte{0x00}, pn532Pattern...)
		v, err := d.command(pn532CmdDiagnose, req, time.Second)
		if err != nil {
			return false, err
		}
		return bytes.Equal(v, req), nil
	}
	v, err := d.command(pn532CmdDiagnose, []byte{test}, time.Second)
	if err != nil {
		return false, err
	}
	return len(v) > 0 && v[0] == 0x00, nil
}

// SendCommand sends a raw command and returns its response data (after the response code).
func (d *PN532Full) SendCommand(command uint8, data []byte, timeout time.Duration) ([]byte, error) {
	return d.command(command, data, timeout)
}

// StartCommand writes a command frame and reads its ACK, without waiting for the response.
func (d *PN532Full) StartCommand(command uint8, data []byte) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.start(command, data)
}

// ReadResponse reads the response to a command started with StartCommand; a timeout aborts it.
func (d *PN532Full) ReadResponse(command uint8, timeout time.Duration) ([]byte, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.response(command, timeout)
}

// WaitReady polls the status byte every 1 ms until a frame is ready.
func (d *PN532Full) WaitReady(timeout time.Duration) (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.wait(timeout)
}

// PollInterrupt reports whether the status byte shows a ready ACK or response frame.
func (d *PN532Full) PollInterrupt() (bool, error) {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.ready()
}

// OnInterrupt calls callback when the chip has a frame ready; read it with ReadResponse.
// With the connection's IntPin wired it fires on the falling edge of IRQ; otherwise a
// polling goroutine checks the status byte and fires on each not-ready → ready transition.
func (d *PN532Full) OnInterrupt(callback func()) error {
	_ = d.OffInterrupt()
	d.imu.Lock()
	d.callback = callback
	d.wasReady = false
	d.imu.Unlock()
	ip := d.conn.IntPin()
	polled := ip == nil
	if polled {
		poll := connection.NewPollingInputPin(10 * time.Millisecond)
		d.imu.Lock()
		d.pollPin = poll
		d.imu.Unlock()
		ip = poll
	}
	unsub := ip.OnEdge(connection.Falling, func() { d.handleEdge(polled) })
	d.imu.Lock()
	d.unsubscribe = unsub
	d.imu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *PN532Full) OffInterrupt() error {
	d.imu.Lock()
	unsub, poll := d.unsubscribe, d.pollPin
	d.unsubscribe, d.pollPin, d.callback = nil, nil, nil
	d.imu.Unlock()
	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *PN532Full) handleEdge(polled bool) {
	if polled {
		now, err := d.PollInterrupt()
		if err != nil {
			return
		}
		d.imu.Lock()
		was := d.wasReady
		d.wasReady = now
		d.imu.Unlock()
		if !now || was {
			return
		}
	}
	d.imu.Lock()
	cb := d.callback
	d.imu.Unlock()
	if cb != nil {
		cb()
	}
}
