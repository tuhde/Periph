package adcdac

import "github.com/tuhde/Periph/go/periph/connection"

// PCF8591 input mode constants (AIP field of the control byte).
const (
	// PCF8591Mode4SingleEnded — 4 single-ended inputs (AIN0..AIN3).
	PCF8591Mode4SingleEnded uint8 = 0
	// PCF8591Mode3Differential — 3 differential inputs vs AIN3.
	PCF8591Mode3Differential uint8 = 1
	// PCF8591ModeMixed — AIN0 SE, AIN1 SE, AIN2–AIN3 differential.
	PCF8591ModeMixed uint8 = 2
	// PCF8591Mode2Differential — 2 differential inputs.
	PCF8591Mode2Differential uint8 = 3
)

// PCF8591 control byte bit positions.
const (
	pcf8591BitAOE  = 0x40
	pcf8591BitAI   = 0x04
	pcf8591MaskAIP = 0x30
	pcf8591MaskCHN = 0x03
)

// PCF8591Minimal is the 8-bit quad ADC + DAC driver — minimal interface.
//
// Operates in 4 single-ended mode. Each read transaction returns
// n+1 bytes; the first byte is the previous conversion result and must
// be discarded. The next n bytes are fresh channel samples.
type PCF8591Minimal struct {
	connection connection.Connection
}

// NewPCF8591Minimal creates a new PCF8591Minimal bound to the given
// connection. No I²C traffic is required at construction.
func NewPCF8591Minimal(t connection.Connection) (*PCF8591Minimal, error) {
	return &PCF8591Minimal{connection: t}, nil
}

// ReadChannel reads a single channel as an unsigned 8-bit value.
//
// channel is clamped to [0, 3]. The first returned byte from the chip is
// always stale and is discarded.
func (d *PCF8591Minimal) ReadChannel(channel uint8) (uint8, error) {
	if channel > 3 {
		channel = 0
	}
	ctrl := byte(channel & 0x03)
	if err := d.connection.Write([]byte{ctrl}); err != nil {
		return 0, err
	}
	buf, err := d.connection.Read(2)
	if err != nil {
		return 0, err
	}
	return buf[1], nil
}

// ReadAll reads all four channels using auto-increment.
//
// Returns [ch0, ch1, ch2, ch3] as raw 8-bit values.
func (d *PCF8591Minimal) ReadAll() ([4]uint8, error) {
	ctrl := byte(pcf8591BitAI) // AI=1, AIP=00, AOE=0
	if err := d.connection.Write([]byte{ctrl}); err != nil {
		return [4]uint8{}, err
	}
	buf, err := d.connection.Read(5)
	if err != nil {
		return [4]uint8{}, err
	}
	return [4]uint8{buf[1], buf[2], buf[3], buf[4]}, nil
}

// PCF8591Full is the 8-bit quad ADC + DAC driver — full interface.
// Extends PCF8591Minimal with configuration, differential reads,
// voltage-calibrated reads, and DAC output.
type PCF8591Full struct {
	*PCF8591Minimal
	control       byte
	inputMode     uint8
	dacEnabled    bool
	autoIncrement bool
	lastChannel   uint8
}

// NewPCF8591Full creates a new PCF8591Full bound to the given connection.
// Initial state: 4 single-ended mode, DAC disabled, auto-increment off.
func NewPCF8591Full(t connection.Connection) (*PCF8591Full, error) {
	m, err := NewPCF8591Minimal(t)
	if err != nil {
		return nil, err
	}
	return &PCF8591Full{
		PCF8591Minimal: m,
		control:        0,
		inputMode:      PCF8591Mode4SingleEnded,
		dacEnabled:     false,
		autoIncrement:  false,
		lastChannel:    0,
	}, nil
}

// Configure sets the analog input mode, auto-increment, and DAC enable.
//
// input_mode is one of PCF8591Mode* constants. When auto_increment is
// true the channel number increments after each conversion. When
// dac_enabled is true the AOUT pin is driven.
func (d *PCF8591Full) Configure(inputMode uint8, autoIncrement bool, dacEnabled bool) error {
	aip := (inputMode & 0x03) << 4
	ai := byte(0)
	if autoIncrement {
		ai = pcf8591BitAI
	}
	aoe := byte(0)
	if dacEnabled {
		aoe = pcf8591BitAOE
	}
	d.control = aip | aoe | ai | byte(d.lastChannel&pcf8591MaskCHN)
	d.inputMode = inputMode & 0x03
	d.dacEnabled = dacEnabled
	d.autoIncrement = autoIncrement
	return d.connection.Write([]byte{d.control})
}

// ReadChannelVoltage reads one channel and returns the value as a voltage.
//
// vref is the ADC reference voltage; vagnd is the analog ground voltage.
func (d *PCF8591Full) ReadChannelVoltage(channel uint8, vref float32, vagnd float32) (float32, error) {
	raw, err := d.ReadChannel(channel)
	if err != nil {
		return 0, err
	}
	return vagnd + float32(raw)*(vref-vagnd)/256.0, nil
}

// ReadAllVoltage reads all four channels and returns each as a voltage.
func (d *PCF8591Full) ReadAllVoltage(vref float32, vagnd float32) ([4]float32, error) {
	raws, err := d.ReadAll()
	if err != nil {
		return [4]float32{}, err
	}
	vfs := vref - vagnd
	var out [4]float32
	for i := 0; i < 4; i++ {
		out[i] = vagnd + float32(raws[i])*vfs/256.0
	}
	return out, nil
}

// ReadDifferential reads a differential channel as a signed 8-bit value
// in two's complement (-128 to +127).
//
// The chip must be in a differential mode (input_mode 1, 2, or 3).
func (d *PCF8591Full) ReadDifferential(channel uint8) (int8, error) {
	ch := channel & pcf8591MaskCHN
	d.lastChannel = ch
	ctrl := d.control | byte(ch&pcf8591MaskCHN)
	if err := d.connection.Write([]byte{ctrl}); err != nil {
		return 0, err
	}
	buf, err := d.connection.Read(2)
	if err != nil {
		return 0, err
	}
	return int8(buf[1]), nil
}

// SetDAC enables the DAC and writes a raw 8-bit value.
//
// V_AOUT = V_AGND + value × (V_REF − V_AGND) / 256.
func (d *PCF8591Full) SetDAC(value uint8) error {
	ctrl := (d.control | pcf8591BitAOE) &^ pcf8591BitAI
	d.control = ctrl
	d.dacEnabled = true
	return d.connection.Write([]byte{ctrl, value})
}

// SetDACVoltage sets the DAC output as a fraction of (V_REF − V_AGND).
//
// fraction is clamped to [0.0, 1.0].
func (d *PCF8591Full) SetDACVoltage(fraction float32) error {
	f := fraction
	if f < 0.0 {
		f = 0.0
	}
	if f > 1.0 {
		f = 1.0
	}
	return d.SetDAC(uint8(f * 255.0))
}

// DisableDAC clears the AOE bit; AOUT returns to high-impedance.
func (d *PCF8591Full) DisableDAC() error {
	ctrl := d.control &^ pcf8591BitAOE
	d.control = ctrl
	d.dacEnabled = false
	return d.connection.Write([]byte{ctrl})
}
