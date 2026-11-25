package power

import (
	"errors"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// LTC4151 register addresses.
const (
	ltc4151RegSense   uint8 = 0x00
	ltc4151RegVin     uint8 = 0x02
	ltc4151RegAdin    uint8 = 0x04
	ltc4151RegControl uint8 = 0x06
)

// LTC4151 conversion LSBs in volts.
const (
	ltc4151LsbSense float32 = 20e-6
	ltc4151LsbVin   float32 = 0.025
	ltc4151LsbAdin  float32 = 0.0005
)

// LTC4151 CONTROL register bits.
const (
	ltc4151CtrlSnapshot uint8 = 0x80
	ltc4151CtrlTest     uint8 = 0x10
	ltc4151CtrlPageRW   uint8 = 0x08
	ltc4151CtrlStuckBus uint8 = 0x04
)

// Channel selectors for LTC4151Full.ReadRaw, Snapshot and IsBusy.
const (
	LTC4151Sense uint8 = 0
	LTC4151Vin   uint8 = 1
	LTC4151Adin  uint8 = 2
)

// Errors returned by the LTC4151 drivers.
var (
	// ErrLTC4151Channel is returned for a channel other than 0, 1 or 2.
	ErrLTC4151Channel = errors.New("ltc4151: channel must be 0, 1 or 2")
	// ErrLTC4151Resistor is returned when the sense resistor is not greater than 0.
	ErrLTC4151Resistor = errors.New("ltc4151: sense resistor must be > 0")
	// ErrLTC4151Timeout is returned when a snapshot conversion does not finish within 100 ms.
	ErrLTC4151Timeout = errors.New("ltc4151: snapshot timeout")
)

func ltc4151Code(high, low byte) uint16 {
	return uint16(high)<<4 | uint16(low>>4)
}

// LTC4151Readings is the result of LTC4151Full.ReadAll.
type LTC4151Readings struct {
	Vin    float32 // supply voltage in V
	SenseV float32 // sense voltage in V
	// Current is the load current in A.
	Current float32
	// Power is the load power in W.
	Power float32
	// Adin is the ADIN pin voltage in V.
	Adin float32
}

// LTC4151Minimal is the LTC4151 7–80 V high-side current and voltage monitor
// driver — minimal interface.
//
// The chip scans SENSE, VIN and ADIN continuously after power-up, so no
// configuration is needed beyond the sense resistor; the constructor makes no
// bus access. Power-on state: CONTROL = 0x0C (continuous scan, page R/W and
// stuck-bus timer enabled).
type LTC4151Minimal struct {
	connection connection.RegisterConnection
	rSense     float32
}

// NewLTC4151Minimal creates a new LTC4151Minimal. connection must be a
// configured I²C connection bound to the device's 7-bit address (0x67–0x6F).
// rSense is the sense resistor in ohms (typically 0.02); a value <= 0 falls
// back to 0.02.
func NewLTC4151Minimal(connection connection.RegisterConnection, rSense float32) (*LTC4151Minimal, error) {
	if !(rSense > 0) {
		rSense = 0.02
	}
	return &LTC4151Minimal{connection: connection, rSense: rSense}, nil
}

// Voltage reads the supply voltage at VIN in volts (code × 25 mV).
func (d *LTC4151Minimal) Voltage() (float32, error) {
	b, err := d.connection.ReadReg(uint32(ltc4151RegVin), 2)
	if err != nil {
		return 0, err
	}
	return float32(ltc4151Code(b[0], b[1])) * ltc4151LsbVin, nil
}

// Current reads the load current in amperes (sense voltage / rSense).
func (d *LTC4151Minimal) Current() (float32, error) {
	b, err := d.connection.ReadReg(uint32(ltc4151RegSense), 2)
	if err != nil {
		return 0, err
	}
	return float32(ltc4151Code(b[0], b[1])) * ltc4151LsbSense / d.rSense, nil
}

// Power reads the load power in watts (VIN × current) from one consistent
// block read of SENSE and VIN.
func (d *LTC4151Minimal) Power() (float32, error) {
	b, err := d.connection.ReadReg(uint32(ltc4151RegSense), 4)
	if err != nil {
		return 0, err
	}
	sense := float32(ltc4151Code(b[0], b[1])) * ltc4151LsbSense
	vin := float32(ltc4151Code(b[2], b[3])) * ltc4151LsbVin
	return vin * (sense / d.rSense), nil
}

// LTC4151Full is the LTC4151 driver — full interface. Extends LTC4151Minimal
// with all channels, snapshot mode and CONTROL access.
type LTC4151Full struct {
	*LTC4151Minimal
	pageRW bool
}

// NewLTC4151Full creates a new LTC4151Full. Makes no bus access.
func NewLTC4151Full(connection connection.RegisterConnection, rSense float32) (*LTC4151Full, error) {
	m, err := NewLTC4151Minimal(connection, rSense)
	if err != nil {
		return nil, err
	}
	return &LTC4151Full{LTC4151Minimal: m, pageRW: true}, nil
}

func (d *LTC4151Full) readBlock(reg uint8, n int) ([]byte, error) {
	if d.pageRW || n == 1 {
		return d.connection.ReadReg(uint32(reg), n)
	}
	out := make([]byte, 0, n)
	for i := 0; i < n; i++ {
		b, err := d.connection.ReadReg(uint32(reg)+uint32(i), 1)
		if err != nil {
			return nil, err
		}
		out = append(out, b[0])
	}
	return out, nil
}

func (d *LTC4151Full) readControlRaw() (uint8, error) {
	b, err := d.connection.ReadReg(uint32(ltc4151RegControl), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *LTC4151Full) writeControlRaw(v uint8) error {
	return d.connection.WriteReg(uint32(ltc4151RegControl), []byte{v})
}

// SenseVoltage reads SENSE+ − SENSE− in volts (code × 20 µV).
func (d *LTC4151Full) SenseVoltage() (float32, error) {
	b, err := d.readBlock(ltc4151RegSense, 2)
	if err != nil {
		return 0, err
	}
	return float32(ltc4151Code(b[0], b[1])) * ltc4151LsbSense, nil
}

// AdinVoltage reads the ADIN pin voltage in volts (code × 0.5 mV).
func (d *LTC4151Full) AdinVoltage() (float32, error) {
	b, err := d.readBlock(ltc4151RegAdin, 2)
	if err != nil {
		return 0, err
	}
	return float32(ltc4151Code(b[0], b[1])) * ltc4151LsbAdin, nil
}

// Voltage reads the supply voltage at VIN in volts (code × 25 mV).
func (d *LTC4151Full) Voltage() (float32, error) {
	b, err := d.readBlock(ltc4151RegVin, 2)
	if err != nil {
		return 0, err
	}
	return float32(ltc4151Code(b[0], b[1])) * ltc4151LsbVin, nil
}

// Current reads the load current in amperes (sense voltage / rSense).
func (d *LTC4151Full) Current() (float32, error) {
	v, err := d.SenseVoltage()
	if err != nil {
		return 0, err
	}
	return v / d.rSense, nil
}

// Power reads the load power in watts from one consistent block read.
func (d *LTC4151Full) Power() (float32, error) {
	b, err := d.readBlock(ltc4151RegSense, 4)
	if err != nil {
		return 0, err
	}
	sense := float32(ltc4151Code(b[0], b[1])) * ltc4151LsbSense
	vin := float32(ltc4151Code(b[2], b[3])) * ltc4151LsbVin
	return vin * (sense / d.rSense), nil
}

// ReadAll reads all three channels from one block read of registers 0x00–0x05.
func (d *LTC4151Full) ReadAll() (LTC4151Readings, error) {
	b, err := d.readBlock(ltc4151RegSense, 6)
	if err != nil {
		return LTC4151Readings{}, err
	}
	sense := float32(ltc4151Code(b[0], b[1])) * ltc4151LsbSense
	vin := float32(ltc4151Code(b[2], b[3])) * ltc4151LsbVin
	adin := float32(ltc4151Code(b[4], b[5])) * ltc4151LsbAdin
	cur := sense / d.rSense
	return LTC4151Readings{Vin: vin, SenseV: sense, Current: cur, Power: vin * cur, Adin: adin}, nil
}

// ReadRaw reads the 12-bit code (0–4095) of a channel (LTC4151Sense,
// LTC4151Vin or LTC4151Adin).
func (d *LTC4151Full) ReadRaw(channel uint8) (uint16, error) {
	if channel > LTC4151Adin {
		return 0, ErrLTC4151Channel
	}
	b, err := d.readBlock(channel*2, 2)
	if err != nil {
		return 0, err
	}
	return ltc4151Code(b[0], b[1]), nil
}

// SetSenseResistor sets the sense resistor in ohms; it must be greater than 0.
func (d *LTC4151Full) SetSenseResistor(ohms float32) error {
	if !(ohms > 0) {
		return ErrLTC4151Resistor
	}
	d.rSense = ohms
	return nil
}

// GetSenseResistor returns the sense resistor in ohms.
func (d *LTC4151Full) GetSenseResistor() float32 { return d.rSense }

// Snapshot runs one snapshot conversion of a channel, then returns to
// continuous scan. It clears the snapshot bit first, writes the channel with
// the snapshot bit set, polls the channel's BUSY bit (timeout 100 ms), reads
// the result and restores continuous scan. The value is in volts (sense
// voltage, VIN or ADIN). Returns ErrLTC4151Timeout after 100 ms.
func (d *LTC4151Full) Snapshot(channel uint8) (float32, error) {
	if channel > LTC4151Adin {
		return 0, ErrLTC4151Channel
	}
	ctrl, err := d.readControlRaw()
	if err != nil {
		return 0, err
	}
	base := ctrl & (ltc4151CtrlPageRW | ltc4151CtrlStuckBus)
	if err := d.writeControlRaw(base); err != nil {
		return 0, err
	}
	if err := d.writeControlRaw(base | ltc4151CtrlSnapshot | channel<<5); err != nil {
		return 0, err
	}
	for polls := 0; ; polls++ {
		busy, err := d.IsBusy(channel)
		if err != nil {
			return 0, err
		}
		if !busy {
			break
		}
		if polls >= 50 {
			_ = d.writeControlRaw(base)
			return 0, ErrLTC4151Timeout
		}
		time.Sleep(2 * time.Millisecond)
	}
	raw, err := d.ReadRaw(channel)
	if err != nil {
		return 0, err
	}
	if err := d.writeControlRaw(base); err != nil {
		return 0, err
	}
	lsb := [3]float32{ltc4151LsbSense, ltc4151LsbVin, ltc4151LsbAdin}[channel]
	return float32(raw) * lsb, nil
}

// SetContinuous switches to continuous scan mode (snapshot bit cleared).
func (d *LTC4151Full) SetContinuous() error {
	ctrl, err := d.readControlRaw()
	if err != nil {
		return err
	}
	return d.writeControlRaw(ctrl &^ (ltc4151CtrlSnapshot | ltc4151CtrlTest))
}

// IsSnapshotMode reports whether the snapshot bit of CONTROL is set.
func (d *LTC4151Full) IsSnapshotMode() (bool, error) {
	ctrl, err := d.readControlRaw()
	if err != nil {
		return false, err
	}
	return ctrl&ltc4151CtrlSnapshot != 0, nil
}

// IsBusy reports the BUSY bit of a channel (snapshot mode).
func (d *LTC4151Full) IsBusy(channel uint8) (bool, error) {
	if channel > LTC4151Adin {
		return false, ErrLTC4151Channel
	}
	b, err := d.connection.ReadReg(uint32(channel*2+1), 1)
	if err != nil {
		return false, err
	}
	return b[0]&0x08 != 0, nil
}

// SetStuckBusTimer enables or disables the stuck-bus reset timer
// (CONTROL.G2, read-modify-write).
func (d *LTC4151Full) SetStuckBusTimer(enabled bool) error {
	ctrl, err := d.readControlRaw()
	if err != nil {
		return err
	}
	ctrl &^= ltc4151CtrlTest
	if enabled {
		ctrl |= ltc4151CtrlStuckBus
	} else {
		ctrl &^= ltc4151CtrlStuckBus
	}
	return d.writeControlRaw(ctrl)
}

// SetPageRW enables or disables I²C page read/write (CONTROL.G3,
// read-modify-write). When disabled the driver reads each register
// separately.
func (d *LTC4151Full) SetPageRW(enabled bool) error {
	ctrl, err := d.readControlRaw()
	if err != nil {
		return err
	}
	ctrl &^= ltc4151CtrlTest
	if enabled {
		ctrl |= ltc4151CtrlPageRW
	} else {
		ctrl &^= ltc4151CtrlPageRW
	}
	if err := d.writeControlRaw(ctrl); err != nil {
		return err
	}
	d.pageRW = enabled
	return nil
}

// ReadControl reads the CONTROL register (power-on default 0x0C).
func (d *LTC4151Full) ReadControl() (uint8, error) { return d.readControlRaw() }

// WriteControl writes the CONTROL register. Test mode (bit 4) is always
// masked to 0.
func (d *LTC4151Full) WriteControl(value uint8) error {
	value &^= ltc4151CtrlTest
	if err := d.writeControlRaw(value); err != nil {
		return err
	}
	d.pageRW = value&ltc4151CtrlPageRW != 0
	return nil
}

// LTC4151TemperatureFromAdin converts an ADIN/VIN code pair to temperature in
// °C using the datasheet NTC example circuit (58.82 × (adin/vin − 0.1066));
// valid for roughly 20–60 °C only.
func LTC4151TemperatureFromAdin(adinCode, vinCode uint16) float32 {
	if vinCode == 0 {
		return 0
	}
	return 58.82 * (float32(adinCode)/float32(vinCode) - 0.1066)
}
