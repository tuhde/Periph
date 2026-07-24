package adcdac

import (
	"time"

	"github.com/tuhde/Periph/go/periph/transport"
)

// MCP4728 command bytes and General Call opcodes.
const (
	mcp4728AddrGeneralCall = 0x00

	// Multi-Write command: [0 1 0 0 0 DAC1 DAC0 UDAC]
	mcp4728CmdMultiWriteBase uint8 = 0x40
	// Single Write (DAC + EEPROM, one channel): [0 1 0 1 1 DAC1 DAC0 UDAC]
	mcp4728CmdSingleWrite uint8 = 0x58
	// Sequential Write (DAC + EEPROM, A→D): [0 1 0 1 0 DAC1 DAC0 UDAC]
	mcp4728CmdSequentialBase uint8 = 0x50
	// Write V_REF (all channels, DAC only): [1 0 0 X ...]
	mcp4728CmdWriteVREF uint8 = 0x80
	// Write Gain (all channels, DAC only): [1 1 0 X ...]
	mcp4728CmdWriteGain uint8 = 0xC0
	// Write Power-Down (all channels, DAC only): [1 0 1 X ...]
	mcp4728CmdWritePowerDown uint8 = 0xA0

	mcp4728GCReset         = 0x06
	mcp4728GCSoftwareUpdate = 0x08
	mcp4728GCWake          = 0x09
)

const mcp4728EepromWriteDelay = 50 * time.Millisecond

// MCP4728Minimal is the quad-channel 12-bit DAC driver — minimal interface.
//
// Provides single-channel updates (set_voltage / set_raw) via Multi-Write
// and synchronous four-channel updates (set_all) via Fast Write. Defaults
// baked in: V_REF=external (V_DD), gain=×1, PD=00, UDAC=0. EEPROM is
// not written by any Minimal method.
type MCP4728Minimal struct {
	transport transport.Transport
}

// NewMCP4728Minimal creates a new MCP4728Minimal bound to the given transport.
//
// transport must be a configured I²C transport bound to the chip's 7-bit
// address (0x60 default, 0x60–0x67 depending on the stored A2:A1:A0 bits).
func NewMCP4728Minimal(t transport.Transport) (*MCP4728Minimal, error) {
	return &MCP4728Minimal{transport: t}, nil
}

// SetVoltage sets one channel's DAC output as a fraction of V_DD.
//
// channel selects A (0), B (1), C (2), or D (3). fraction is clamped to
// [0.0, 1.0].
func (d *MCP4728Minimal) SetVoltage(channel uint8, fraction float32) error {
	f := fraction
	if f < 0.0 {
		f = 0.0
	}
	if f > 1.0 {
		f = 1.0
	}
	code := uint16(f * 4095.0)
	if code > 4095 {
		code = 4095
	}
	return d.setRaw(channel, code)
}

// SetRaw sets one channel's raw 12-bit DAC code. channel is clamped to
// [0, 3]; code to [0, 4095].
func (d *MCP4728Minimal) SetRaw(channel uint8, code uint16) error {
	return d.multiWrite(channel, code, 0, 0, 0, 0)
}

func (d *MCP4728Minimal) setRaw(channel uint8, code uint16) error {
	return d.SetRaw(channel, code)
}

// SetAll updates all four channels simultaneously via Fast Write.
//
// fractions[0] is channel A, fractions[3] is channel D. Each fraction is
// clamped to [0.0, 1.0]. EEPROM is not written.
func (d *MCP4728Minimal) SetAll(fractions [4]float32) error {
	buf := make([]byte, 8)
	for i := 0; i < 4; i++ {
		f := fractions[i]
		if f < 0.0 {
			f = 0.0
		}
		if f > 1.0 {
			f = 1.0
		}
		code := uint16(f * 4095.0)
		if code > 4095 {
			code = 4095
		}
		buf[i*2] = byte((code >> 8) & 0x0F)
		buf[i*2+1] = byte(code & 0xFF)
	}
	return d.transport.Write(buf)
}

// multiWrite issues the 3-byte Multi-Write command to update a single
// channel's volatile DAC register. UDAC=0 → V_OUT updates immediately.
func (d *MCP4728Minimal) multiWrite(channel uint8, code uint16, vref uint8, pd uint8, gain uint8, udac uint8) error {
	if channel > 3 {
		channel = 3
	}
	if code > 4095 {
		code = 4095
	}
	buf := []byte{
		mcp4728CmdMultiWriteBase | byte((channel&0x03)<<1) | byte(udac&0x01),
		byte((vref&0x01)<<7) | byte((pd&0x03)<<5) | byte((gain&0x01)<<4) | byte((code>>8)&0x0F),
		byte(code & 0xFF),
	}
	return d.transport.Write(buf)
}

// MCP4728ChannelState is the per-channel state read from the chip.
type MCP4728ChannelState struct {
	Code            uint16
	VREF            uint8  // 0 = external (V_DD), 1 = internal (2.048 V)
	Gain            uint8  // 1 or 2
	PowerDown       uint8  // 0–3
	EEPROMCode      uint16
	EEPROMVREF      uint8
	EEPROMGain      uint8
	EEPROMPowerDown uint8
}

// MCP4728ReadResult is the full read-back result for an MCP4728 device.
type MCP4728ReadResult struct {
	Channel     [4]MCP4728ChannelState
	EEPROMReady bool
}

// MCP4728Full is the quad-channel 12-bit DAC driver — full interface.
// Extends MCP4728Minimal with EEPROM persistence, V_REF / gain / power-down
// configuration, read-back, and General Call commands.
type MCP4728Full struct {
	*MCP4728Minimal
}

// NewMCP4728Full creates a new MCP4728Full bound to the given transport.
func NewMCP4728Full(t transport.Transport) (*MCP4728Full, error) {
	m, err := NewMCP4728Minimal(t)
	if err != nil {
		return nil, err
	}
	return &MCP4728Full{MCP4728Minimal: m}, nil
}

// SetVoltageEEPROM sets one channel's output and persists to EEPROM.
//
// vref is 0 (external/V_DD) or 1 (internal 2.048 V). gain is 1 or 2
// (gain=2 is ignored when vref=0).
func (d *MCP4728Full) SetVoltageEEPROM(channel uint8, fraction float32, vref uint8, gain uint8) error {
	f := fraction
	if f < 0.0 {
		f = 0.0
	}
	if f > 1.0 {
		f = 1.0
	}
	code := uint16(f * 4095.0)
	if code > 4095 {
		code = 4095
	}
	return d.singleWrite(channel, code, vref, 0, gain, 0)
}

// SetRawEEPROM sets one channel's raw code and persists to EEPROM.
func (d *MCP4728Full) SetRawEEPROM(channel uint8, code uint16, vref uint8, gain uint8) error {
	return d.singleWrite(channel, code, vref, 0, gain, 0)
}

// SetAllEEPROM updates all four channels and writes EEPROM (Sequential
// Write starting at A).
//
// fractions, vrefs, and gains are 4-element arrays, one per channel.
// vrefs[i] is 0/1, gains[i] is 1/2. Waits the worst-case 50 ms for the
// EEPROM write to complete.
func (d *MCP4728Full) SetAllEEPROM(fractions [4]float32, vrefs [4]uint8, gains [4]uint8) error {
	buf := make([]byte, 9)
	buf[0] = mcp4728CmdSequentialBase
	for i := 0; i < 4; i++ {
		f := fractions[i]
		if f < 0.0 {
			f = 0.0
		}
		if f > 1.0 {
			f = 1.0
		}
		code := uint16(f * 4095.0)
		if code > 4095 {
			code = 4095
		}
		v := vrefs[i] & 0x01
		g := uint8(0)
		if gains[i] == 2 {
			g = 1
		}
		buf[1+i*2] = byte(v<<7) | byte(g<<4) | byte((code>>8)&0x0F)
		buf[1+i*2+1] = byte(code & 0xFF)
	}
	if err := d.transport.Write(buf); err != nil {
		return err
	}
	time.Sleep(mcp4728EepromWriteDelay)
	return nil
}

// SetVREF sets the V_REF selection for all four channels (volatile register
// only). Each value is 0 (external/V_DD) or 1 (internal 2.048 V).
func (d *MCP4728Full) SetVREF(vrefA, vrefB, vrefC, vrefD uint8) error {
	b1 := mcp4728CmdWriteVREF |
		byte((vrefA&0x01)<<3) | byte((vrefB&0x01)<<2) |
		byte((vrefC&0x01)<<1) | byte(vrefD&0x01)
	return d.transport.Write([]byte{b1})
}

// SetGain sets the gain for all four channels (volatile register only).
// Each value is 1 or 2 (gain=2 is ignored when V_REF=external).
func (d *MCP4728Full) SetGain(gainA, gainB, gainC, gainD uint8) error {
	gx := func(g uint8) byte {
		if g == 2 {
			return 1
		}
		return 0
	}
	b1 := mcp4728CmdWriteGain | (gx(gainA) << 3) | (gx(gainB) << 2) | (gx(gainC) << 1) | gx(gainD)
	return d.transport.Write([]byte{b1})
}

// SetPowerDown sets the power-down mode for all four channels (volatile
// register only). Each mode is 0–3.
func (d *MCP4728Full) SetPowerDown(pdA, pdB, pdC, pdD uint8) error {
	clamp := func(p uint8) byte {
		if p > 3 {
			p = 3
		}
		return p & 0x03
	}
	a, b, c, dd := clamp(pdA), clamp(pdB), clamp(pdC), clamp(pdD)
	b1 := mcp4728CmdWritePowerDown | byte((a>>1)&0x01)<<4 | byte(a&0x01)<<3 | byte((b>>1)&0x01)<<2 | byte(b&0x01)<<1
	b2 := byte((c>>1)&0x01)<<6 | byte(c&0x01)<<5 | byte((dd>>1)&0x01)<<4 | byte(dd&0x01)<<3
	return d.transport.Write([]byte{b1, b2})
}

// Read returns all four channels' DAC input registers and EEPROM contents.
//
// Issues a 24-byte read. For each channel, 3 bytes come from the DAC input
// register followed by 3 bytes from EEPROM.
func (d *MCP4728Full) Read() (MCP4728ReadResult, error) {
	buf, err := d.transport.Read(24)
	if err != nil {
		return MCP4728ReadResult{}, err
	}
	result := MCP4728ReadResult{
		EEPROMReady: buf[0]&0x80 != 0,
	}
	for i := 0; i < 4; i++ {
		b := i * 3
		result.Channel[i].Code = (uint16(buf[b+1]&0x0F) << 8) | uint16(buf[b+2])
		result.Channel[i].VREF = (buf[b+1] >> 7) & 0x01
		result.Channel[i].PowerDown = (buf[b+1] >> 5) & 0x03
		if (buf[b+1]>>4)&0x01 != 0 {
			result.Channel[i].Gain = 2
		} else {
			result.Channel[i].Gain = 1
		}
	}
	for i := 0; i < 4; i++ {
		b := 12 + i*3
		result.Channel[i].EEPROMCode = (uint16(buf[b+1]&0x0F) << 8) | uint16(buf[b+2])
		result.Channel[i].EEPROMVREF = (buf[b+1] >> 7) & 0x01
		result.Channel[i].EEPROMPowerDown = (buf[b+1] >> 5) & 0x03
		if (buf[b+1]>>4)&0x01 != 0 {
			result.Channel[i].EEPROMGain = 2
		} else {
			result.Channel[i].EEPROMGain = 1
		}
	}
	return result, nil
}

// IsEEPROMReady returns true when any pending EEPROM write has completed
// (RDY/BSY bit from the first byte of the read response).
func (d *MCP4728Full) IsEEPROMReady() (bool, error) {
	buf, err := d.transport.Read(1)
	if err != nil {
		return false, err
	}
	return buf[0]&0x80 != 0, nil
}

// SoftwareUpdate sends the General Call Software Update command (0x00, 0x08)
// to latch all four V_OUT simultaneously.
func (d *MCP4728Full) SoftwareUpdate() error {
	return d.transport.Write([]byte{mcp4728GCSoftwareUpdate})
}

// WakeUp sends the General Call Wake-Up command (0x00, 0x09) to clear all
// power-down bits.
func (d *MCP4728Full) WakeUp() error {
	return d.transport.Write([]byte{mcp4728GCWake})
}

// Reset sends the General Call Reset command (0x00, 0x06) to reload EEPROM
// into all DAC input and output registers.
func (d *MCP4728Full) Reset() error {
	return d.transport.Write([]byte{mcp4728GCReset})
}

// singleWrite issues the 3-byte Single Write command (DAC + EEPROM) for
// one channel.
func (d *MCP4728Full) singleWrite(channel uint8, code uint16, vref uint8, pd uint8, gain uint8, udac uint8) error {
	if channel > 3 {
		channel = 3
	}
	if code > 4095 {
		code = 4095
	}
	buf := []byte{
		mcp4728CmdSingleWrite | byte((channel&0x03)<<1) | byte(udac&0x01),
		byte((vref&0x01)<<7) | byte((pd&0x03)<<5) | byte((gain&0x01)<<4) | byte((code>>8)&0x0F),
		byte(code & 0xFF),
	}
	if err := d.transport.Write(buf); err != nil {
		return err
	}
	time.Sleep(mcp4728EepromWriteDelay)
	return nil
}
