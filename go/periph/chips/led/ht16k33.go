// Package display contains drivers for Display controllers (LCD segment drivers, etc.).
package display

import (
	"github.com/tuhde/Periph/go/periph/connection"
)

// HT16K33 command prefixes (the chip uses a command-stream protocol; the
// continuation bit C is OR'd in for all but the last command byte).
const (
	ht16k33CmdModeSet      uint8 = 0x40
	ht16k33CmdLoadPtr      uint8 = 0x00
	ht16k33CmdDeviceSelect uint8 = 0x60
	ht16k33CmdBankSelect   uint8 = 0x78
	ht16k33CmdBlinkSelect  uint8 = 0x70
)

// Drive mode constants.
const (
	HT16K33Mode1_4    uint8 = 0x00
	HT16K33ModeStatic uint8 = 0x01
	HT16K33Mode1_2    uint8 = 0x02
	HT16K33Mode1_3    uint8 = 0x03
)

// Bias constants.
const (
	HT16K33Bias1_3 uint8 = 0x00
	HT16K33Bias1_2 uint8 = 0x04
)

// Display enable bit.
const (
	HT16K33DisplayOff uint8 = 0x00
	HT16K33DisplayOn  uint8 = 0x08
)

// Backplane count constants for setMode().
const (
	HT16K33Backplanes1 uint8 = 1
	HT16K33Backplanes2 uint8 = 2
	HT16K33Backplanes3 uint8 = 3
	HT16K33Backplanes4 uint8 = 4
)

// Blink frequency constants.
const (
	HT16K33BlinkOff    uint8 = 0
	HT16K33Blink2Hz    uint8 = 1
	HT16K33Blink1Hz    uint8 = 2
	HT16K33Blink0_5Hz  uint8 = 3
)

// RAM bank constants.
const (
	HT16K33Bank0 uint8 = 0
	HT16K33Bank1 uint8 = 1
)

// HT16K33SevenSeg is the 7-segment lookup table for 1:4 multiplex mode.
// Bit assignment (MSB first): a, c, b, DP, f, e, g, d.
// Add 0x10 to set the decimal point.
var HT16K33SevenSeg = [10]uint8{
	0xED, 0x60, 0xA7, 0xE3, 0x6A,
	0xCB, 0xCF, 0xE0, 0xEF, 0xEB,
}

// HT16K33Minimal is the 40x4 universal LCD segment driver — minimal interface.
//
// Drives a single 7-segment LCD display (static or 1:4 multiplex) with no
// configuration required beyond the connection. The chip is write-only — the
// driver only ever calls connection.Write.
//
// Default configuration: 1:4 multiplex drive mode, 1/3 bias, display enabled,
// display RAM cleared to zero on init.
type HT16K33Minimal struct {
	connection  connection.Connection
	backplanes uint8
}

// NewHT16K33Minimal creates a new HT16K33Minimal and initialises the chip with
// defaults (1:4 multiplex, 1/3 bias, display on, RAM cleared).
//
// connection must be a configured I²C connection bound to the chip's 7-bit
// address (0x38 when SA0 = VSS, 0x39 when SA0 = VDD).
func NewHT16K33Minimal(t connection.Connection) (*HT16K33Minimal, error) {
	d := &HT16K33Minimal{connection: t, backplanes: HT16K33Backplanes4}
	if err := d.doClear(); err != nil {
		return nil, err
	}
	return d, nil
}

// sendCommands sends one or more command bytes. The continuation bit (C=0x80)
// is OR'd into all but the last byte.
func (d *HT16K33Minimal) sendCommands(cmds ...uint8) error {
	if len(cmds) == 0 {
		return nil
	}
	buf := make([]byte, len(cmds))
	for i, c := range cmds {
		if i+1 < len(cmds) {
			buf[i] = 0x80 | (c & 0x7F)
		} else {
			buf[i] = c & 0x7F
		}
	}
	return d.connection.Write(buf)
}

// sendCommandWithData sends a single command byte (C=0) followed by data bytes.
func (d *HT16K33Minimal) sendCommandWithData(cmd uint8, data []byte) error {
	if len(data) == 0 {
		return d.sendCommands(cmd)
	}
	buf := make([]byte, 1+len(data))
	buf[0] = cmd & 0x7F
	copy(buf[1:], data)
	return d.connection.Write(buf)
}

// cmdMode assembles the mode-set command byte from enable, bias, and mode fields.
func (d *HT16K33Minimal) cmdMode(enable bool, bias, mode uint8) uint8 {
	en := HT16K33DisplayOff
	if enable {
		en = HT16K33DisplayOn
	}
	return ht16k33CmdModeSet | en | (bias & 0x04) | (mode & 0x03)
}

// doClear issues a mode-set followed by a load-data-pointer + 20 zero bytes
// (1:4 multiplex) to blank the display.
func (d *HT16K33Minimal) doClear() error {
	if err := d.sendCommands(d.cmdMode(true, HT16K33Bias1_3, HT16K33Mode1_4)); err != nil {
		return err
	}
	zeros := make([]byte, 20)
	return d.sendCommandWithData(ht16k33CmdLoadPtr, zeros)
}

// Clear zeros all 40 columns of display RAM; all segments off.
func (d *HT16K33Minimal) Clear() error {
	return d.doClear()
}

// WriteRaw sets the data pointer to address and writes raw data bytes.
//
// In 1:4 multiplex mode (the default), one byte covers two adjacent RAM
// columns and the data pointer auto-increments by 2 after each byte. Maximum
// 20 bytes covers all 40 columns.
func (d *HT16K33Minimal) WriteRaw(address uint8, data []byte) error {
	if len(data) == 0 {
		return nil
	}
	return d.sendCommandWithData(ht16k33CmdLoadPtr|(address&0x3F), data)
}

// SetDigit7seg writes one 7-segment byte at column position * 2.
//
// Add 0x10 to segments to light the decimal point.
func (d *HT16K33Minimal) SetDigit7seg(position uint8, segments uint8) error {
	return d.WriteRaw(position*2, []byte{segments})
}

// HT16K33Full is the 40x4 universal LCD segment driver — full interface.
// Extends HT16K33Minimal with drive mode, bias, blink, bank select, and
// cascaded-device (subaddress) configuration.
//
// Embeds HT16K33Minimal so all minimal methods are promoted onto the full
// driver automatically — no one-line delegate forwards.
type HT16K33Full struct {
	*HT16K33Minimal
	enabled bool
	bias    uint8
}

// NewHT16K33Full creates a new HT16K33Full and initialises the chip with
// defaults (1:4 multiplex, 1/3 bias, display on, RAM cleared).
func NewHT16K33Full(t connection.Connection) (*HT16K33Full, error) {
	m, err := NewHT16K33Minimal(t)
	if err != nil {
		return nil, err
	}
	return &HT16K33Full{HT16K33Minimal: m, enabled: true, bias: HT16K33Bias1_3}, nil
}

// modeCode maps the backplanes count to the corresponding mode field value.
func modeCode(backplanes uint8) uint8 {
	switch backplanes {
	case HT16K33Backplanes1:
		return HT16K33ModeStatic
	case HT16K33Backplanes2:
		return HT16K33Mode1_2
	case HT16K33Backplanes3:
		return HT16K33Mode1_3
	default:
		return HT16K33Mode1_4
	}
}

// applyMode writes the current mode-set configuration.
func (d *HT16K33Full) applyMode() error {
	bias := HT16K33Bias1_3
	if d.bias == HT16K33Bias1_2 {
		bias = HT16K33Bias1_2
	}
	return d.sendCommands(d.cmdMode(d.enabled, bias, modeCode(d.backplanes)))
}

// Enable turns the display on (E = 1). RAM contents are preserved.
func (d *HT16K33Full) Enable() error {
	d.enabled = true
	return d.applyMode()
}

// Disable blanks the display output (E = 0). RAM contents are preserved.
func (d *HT16K33Full) Disable() error {
	d.enabled = false
	return d.applyMode()
}

// SetMode reconfigures drive mode and bias at runtime.
//
// backplanes: 1 (static), 2 (1:2), 3 (1:3), 4 (1:4 multiplex).
// bias: 0 = 1/3 bias, 1 = 1/2 bias.
func (d *HT16K33Full) SetMode(backplanes uint8, bias uint8) error {
	d.backplanes = backplanes
	d.bias = bias
	return d.applyMode()
}

// SetBlink sets the blink frequency.
//
// frequency: 0 = off, 1 = ~2 Hz, 2 = ~1 Hz, 3 = ~0.5 Hz.
// alternateBank: true to enable alternate-RAM-bank blinking (static and 1:2
// multiplex only).
func (d *HT16K33Full) SetBlink(frequency uint8, alternateBank bool) error {
	ab := uint8(0x00)
	if alternateBank {
		ab = 0x04
	}
	return d.sendCommands(ht16k33CmdBlinkSelect | ab | (frequency & 0x03))
}

// SetBank selects the active RAM bank.
//
// inputBank/outputBank: 0 (rows 0-1) or 1 (rows 2-3). Only meaningful in
// static and 1:2 multiplex modes.
func (d *HT16K33Full) SetBank(inputBank uint8, outputBank uint8) error {
	return d.sendCommands(ht16k33CmdBankSelect | ((inputBank & 1) << 1) | (outputBank & 1))
}

// DeviceSelect changes the subaddress counter for cascaded displays.
//
// subaddress: 0-7; must match the A0/A1/A2 pin state of the target device
// on the bus.
func (d *HT16K33Full) DeviceSelect(subaddress uint8) error {
	return d.sendCommands(ht16k33CmdDeviceSelect | (subaddress & 0x07))
}
