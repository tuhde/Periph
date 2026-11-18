package power

import (
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MAX17048 register addresses (all registers are 16-bit big-endian words).
const (
	max17048RegVCell   uint8 = 0x02
	max17048RegSOC     uint8 = 0x04
	max17048RegMode    uint8 = 0x06
	max17048RegVersion uint8 = 0x08
	max17048RegHibrt   uint8 = 0x0A
	max17048RegConfig  uint8 = 0x0C
	max17048RegValrt   uint8 = 0x14
	max17048RegCrate   uint8 = 0x16
	max17048RegVreset  uint8 = 0x18
	max17048RegStatus  uint8 = 0x1A
	max17048RegCmd     uint8 = 0xFE
)

// Interrupt sources returned by MAX17048Full.PollInterrupt and passed to the
// callback of OnInterrupt.
const (
	// Max17048SourceReset is STATUS.RI: the chip has reset.
	Max17048SourceReset uint16 = 0x0100
	// Max17048SourceOvervoltage is STATUS.VH: VCELL above VALRT.MAX.
	Max17048SourceOvervoltage uint16 = 0x0200
	// Max17048SourceUndervoltage is STATUS.VL: VCELL below VALRT.MIN.
	Max17048SourceUndervoltage uint16 = 0x0400
	// Max17048SourceVReset is STATUS.VR: voltage-reset alert (needs EnableVoltageResetAlert).
	Max17048SourceVReset uint16 = 0x0800
	// Max17048SourceLowSOC is STATUS.HD: SOC crossed the empty-alert threshold.
	Max17048SourceLowSOC uint16 = 0x1000
	// Max17048SourceSOCChange is STATUS.SC: SOC changed by at least 1 % (needs SetSOCChangeAlert).
	Max17048SourceSOCChange uint16 = 0x2000

	max17048Sources uint16 = 0x3F00
)

// ErrMax17048InvalidArgument is returned for a value the chip does not accept.
var ErrMax17048InvalidArgument = errors.New("max17048: invalid argument")

func max17048ClampByte(v float64) uint16 {
	r := math.Round(v)
	if r < 0 {
		return 0
	}
	if r > 255 {
		return 255
	}
	return uint16(r)
}

// Max17048Minimal is the minimal MAX17048 / MAX17049 lithium fuel gauge
// (ModelGauge) interface: cell voltage and state of charge.
//
// The constructor makes no bus access; the chip runs with its power-on
// defaults. The first SOC result is valid about 1 s after power-up.
type Max17048Minimal struct {
	connection connection.RegisterConnection
	cells      uint8
}

// NewMax17048Minimal creates a new Max17048Minimal. connection must be an I²C
// connection bound to address 0x36; cells is 1 for the MAX17048 and 2 for the
// MAX17049 (scales the voltage).
func NewMax17048Minimal(connection connection.RegisterConnection, cells uint8) (*Max17048Minimal, error) {
	if cells != 2 {
		cells = 1
	}
	return &Max17048Minimal{connection: connection, cells: cells}, nil
}

func (d *Max17048Minimal) readWord(reg uint8) (uint16, error) {
	buf, err := d.connection.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(buf[0])<<8 | uint16(buf[1]), nil
}

func (d *Max17048Minimal) writeWord(reg uint8, value uint16) error {
	return d.connection.WriteReg(uint32(reg), []byte{byte(value >> 8), byte(value)})
}

func (d *Max17048Minimal) modify(reg uint8, clear, set uint16) error {
	v, err := d.readWord(reg)
	if err != nil {
		return err
	}
	return d.writeWord(reg, v&^clear|set)
}

// Voltage reads the cell voltage in volts ((VCELL >> 4) × 1.25 mV × cells).
func (d *Max17048Minimal) Voltage() (float32, error) {
	raw, err := d.readWord(max17048RegVCell)
	if err != nil {
		return 0, err
	}
	return float32(raw>>4) * 1.25e-3 * float32(d.cells), nil
}

// Percent reads the state of charge in %, clamped to 0.0–100.0 (SOC / 256).
func (d *Max17048Minimal) Percent() (float32, error) {
	raw, err := d.readWord(max17048RegSOC)
	if err != nil {
		return 0, err
	}
	p := float32(raw) / 256.0
	if p > 100 {
		p = 100
	}
	return p, nil
}

// Max17048Full extends Max17048Minimal with alerts, RCOMP, hibernate, sleep
// and reset.
type Max17048Full struct {
	Max17048Minimal

	mu           sync.Mutex
	sleepEnabled bool
	callback     func(status uint16)
	unsubscribe  func()
	pollPin      *connection.PollingInputPin
}

// NewMax17048Full creates a new Max17048Full. Arguments as for NewMax17048Minimal.
func NewMax17048Full(connection connection.RegisterConnection, cells uint8) (*Max17048Full, error) {
	m, err := NewMax17048Minimal(connection, cells)
	if err != nil {
		return nil, err
	}
	return &Max17048Full{Max17048Minimal: *m}, nil
}

// RawVoltage reads the raw VCELL register (12-bit result in bits 15:4).
func (d *Max17048Full) RawVoltage() (uint16, error) { return d.readWord(max17048RegVCell) }

// RawSOC reads the raw SOC register (high byte = 1 %).
func (d *Max17048Full) RawSOC() (uint16, error) { return d.readWord(max17048RegSOC) }

// ChargeRate reads the charge/discharge rate in %/h (signed CRATE × 0.208);
// positive while charging.
func (d *Max17048Full) ChargeRate() (float32, error) {
	raw, err := d.readWord(max17048RegCrate)
	if err != nil {
		return 0, err
	}
	return float32(int16(raw)) * 0.208, nil
}

// Version reads the VERSION register (0x001x).
func (d *Max17048Full) Version() (uint16, error) { return d.readWord(max17048RegVersion) }

// ChipID reads the factory-programmed ID (low byte of VRESET/ID).
func (d *Max17048Full) ChipID() (uint8, error) {
	v, err := d.readWord(max17048RegVreset)
	return uint8(v), err
}

// QuickStart restarts the SOC estimate from the present voltage
// (MODE.QuickStart); only use it when the first SOC after power-up is clearly wrong.
func (d *Max17048Full) QuickStart() error {
	v := uint16(0x4000)
	if d.sleepEnabled {
		v |= 0x2000
	}
	return d.writeWord(max17048RegMode, v)
}

// Reset performs a power-on reset (CMD ← 0x5400). The chip does not ACK, so a
// bus error is ignored; it waits 1 s.
func (d *Max17048Full) Reset() error {
	_ = d.writeWord(max17048RegCmd, 0x5400)
	time.Sleep(time.Second)
	return nil
}

// SetRCOMP writes RCOMP (CONFIG bits 15:8, read-modify-write).
func (d *Max17048Full) SetRCOMP(value uint8) error {
	return d.modify(max17048RegConfig, 0xFF00, uint16(value)<<8)
}

// GetRCOMP reads RCOMP.
func (d *Max17048Full) GetRCOMP() (uint8, error) {
	v, err := d.readWord(max17048RegConfig)
	return uint8(v >> 8), err
}

// CompensateTemperature sets RCOMP from the datasheet formula with the default
// coefficients (RCOMP0 = 0x97).
func (d *Max17048Full) CompensateTemperature(celsius float32) error {
	coefficient := -5.0
	if celsius >= 20 {
		coefficient = -0.5
	}
	return d.SetRCOMP(uint8(max17048ClampByte(151 + float64(celsius-20)*coefficient)))
}

// SetAlertSOC sets the empty-alert threshold (CONFIG.ATHD = 32 − percent), 1–32 %.
func (d *Max17048Full) SetAlertSOC(percent uint8) error {
	if percent < 1 {
		percent = 1
	}
	if percent > 32 {
		percent = 32
	}
	return d.modify(max17048RegConfig, 0x001F, uint16(32-percent))
}

// GetAlertSOC reads the empty-alert threshold in % (1–32).
func (d *Max17048Full) GetAlertSOC() (uint8, error) {
	v, err := d.readWord(max17048RegConfig)
	return uint8(32 - v&0x1F), err
}

// SetSOCChangeAlert enables or disables the alert on a SOC change of at least 1 % (CONFIG.ALSC).
func (d *Max17048Full) SetSOCChangeAlert(enabled bool) error {
	return d.modify(max17048RegConfig, 0x0040, boolBits(enabled, 0x0040))
}

func boolBits(enabled bool, bits uint16) uint16 {
	if enabled {
		return bits
	}
	return 0
}

// SetVoltageAlert sets the voltage alert window in 20 mV steps (VALRT).
// minV = 0 and maxV = 5.1 disable the respective limit. It returns
// ErrMax17048InvalidArgument if minV > maxV.
func (d *Max17048Full) SetVoltageAlert(minV, maxV float32) error {
	if minV > maxV {
		return ErrMax17048InvalidArgument
	}
	v := max17048ClampByte(float64(minV)/0.020)<<8 | max17048ClampByte(float64(maxV)/0.020)
	return d.writeWord(max17048RegValrt, v)
}

// GetVoltageAlert reads the voltage alert window in volts.
func (d *Max17048Full) GetVoltageAlert() (minV, maxV float32, err error) {
	raw, err := d.readWord(max17048RegValrt)
	if err != nil {
		return 0, 0, err
	}
	return float32(raw>>8) * 0.020, float32(raw&0xFF) * 0.020, nil
}

// SetResetVoltage sets the reset voltage (VRESET bits 15:9, 40 mV steps), 0.0–5.08 V.
func (d *Max17048Full) SetResetVoltage(volts float32) error {
	steps := math.Round(float64(volts) / 0.040)
	if steps < 0 {
		steps = 0
	}
	if steps > 127 {
		steps = 127
	}
	return d.modify(max17048RegVreset, 0xFE00, uint16(steps)<<9)
}

// GetResetVoltage reads the reset voltage in volts.
func (d *Max17048Full) GetResetVoltage() (float32, error) {
	v, err := d.readWord(max17048RegVreset)
	return float32(v>>9) * 0.040, err
}

// EnableVoltageResetAlert lets a voltage-reset event assert ALRT (STATUS.ENVR).
func (d *Max17048Full) EnableVoltageResetAlert(enabled bool) error {
	return d.modify(max17048RegStatus, 0x4000, boolBits(enabled, 0x4000))
}

// SetComparatorDisabled disables the analog comparator in hibernate mode
// (VRESET/ID.DIS, saves about 0.5 µA).
func (d *Max17048Full) SetComparatorDisabled(disabled bool) error {
	return d.modify(max17048RegVreset, 0x0100, boolBits(disabled, 0x0100))
}

// SetHibernateThresholds programs HIBRT: hibernate below hibThrPctPerH
// (0.208 %/h steps) and wake when |OCV − VCELL| exceeds actThrV (1.25 mV steps).
func (d *Max17048Full) SetHibernateThresholds(hibThrPctPerH, actThrV float32) error {
	v := max17048ClampByte(float64(hibThrPctPerH)/0.208)<<8 | max17048ClampByte(float64(actThrV)/0.00125)
	return d.writeWord(max17048RegHibrt, v)
}

// GetHibernateThresholds reads the hibernate thresholds.
func (d *Max17048Full) GetHibernateThresholds() (hibThrPctPerH, actThrV float32, err error) {
	raw, err := d.readWord(max17048RegHibrt)
	if err != nil {
		return 0, 0, err
	}
	return float32(raw>>8) * 0.208, float32(raw&0xFF) * 0.00125, nil
}

// DisableHibernate never hibernates (HIBRT ← 0x0000).
func (d *Max17048Full) DisableHibernate() error { return d.writeWord(max17048RegHibrt, 0x0000) }

// ForceHibernate stays always in hibernate (HIBRT ← 0xFFFF).
func (d *Max17048Full) ForceHibernate() error { return d.writeWord(max17048RegHibrt, 0xFFFF) }

// IsHibernating reads MODE.HibStat; an unreadable (write-only) register counts as false.
func (d *Max17048Full) IsHibernating() bool {
	v, err := d.readWord(max17048RegMode)
	return err == nil && v&0x1000 != 0
}

// EnableSleep allows or forbids sleep mode (MODE.EnSleep; MODE is written whole).
func (d *Max17048Full) EnableSleep(enabled bool) error {
	d.sleepEnabled = enabled
	return d.writeWord(max17048RegMode, boolBits(enabled, 0x2000))
}

// Sleep enters sleep mode (CONFIG.SLEEP = 1); needs EnableSleep(true) first.
func (d *Max17048Full) Sleep() error { return d.modify(max17048RegConfig, 0, 0x0080) }

// Wake leaves sleep mode (CONFIG.SLEEP = 0).
func (d *Max17048Full) Wake() error { return d.modify(max17048RegConfig, 0x0080, 0) }

// ReadStatus reads the STATUS register.
func (d *Max17048Full) ReadStatus() (uint16, error) { return d.readWord(max17048RegStatus) }

// ClearStatus clears the given STATUS bits and CONFIG.ALRT (releases the ALRT pin).
func (d *Max17048Full) ClearStatus(mask uint16) error {
	if err := d.modify(max17048RegStatus, mask, 0); err != nil {
		return err
	}
	return d.modify(max17048RegConfig, 0x0020, 0)
}

// ReadConfig reads the raw CONFIG register.
func (d *Max17048Full) ReadConfig() (uint16, error) { return d.readWord(max17048RegConfig) }

// WriteConfig writes the raw CONFIG register.
func (d *Max17048Full) WriteConfig(value uint16) error { return d.writeWord(max17048RegConfig, value) }

// EnableInterrupt enables an alert source that has a control bit
// (Max17048SourceSOCChange, Max17048SourceVReset).
func (d *Max17048Full) EnableInterrupt(source uint16) error {
	switch source {
	case Max17048SourceSOCChange:
		return d.SetSOCChangeAlert(true)
	case Max17048SourceVReset:
		return d.EnableVoltageResetAlert(true)
	}
	return nil
}

// DisableInterrupt disables an alert source (control bit, or VALRT.MIN = 0 / VALRT.MAX = 0xFF).
func (d *Max17048Full) DisableInterrupt(source uint16) error {
	switch source {
	case Max17048SourceSOCChange:
		return d.SetSOCChangeAlert(false)
	case Max17048SourceVReset:
		return d.EnableVoltageResetAlert(false)
	case Max17048SourceUndervoltage:
		return d.modify(max17048RegValrt, 0xFF00, 0)
	case Max17048SourceOvervoltage:
		return d.modify(max17048RegValrt, 0x00FF, 0x00FF)
	}
	return nil
}

// OnInterrupt subscribes callback to ALRT (falling edge): it receives the
// STATUS word masked to the Max17048Source* bits. Uses the connection's
// InputPin if wired, otherwise a polling goroutine (1 s tick).
func (d *Max17048Full) OnInterrupt(callback func(status uint16)) error {
	d.mu.Lock()
	if d.unsubscribe != nil {
		d.unsubscribe()
		d.unsubscribe = nil
	}
	if d.pollPin != nil {
		_ = d.pollPin.Close()
		d.pollPin = nil
	}
	d.callback = callback
	pin := d.connection.IntPin()
	if pin == nil {
		poll := connection.NewPollingInputPin(time.Second)
		d.pollPin = poll
		pin = poll
	}
	d.mu.Unlock()

	unsub := pin.OnEdge(connection.Falling, d.handleEdge)
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *Max17048Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		return poll.Close()
	}
	return nil
}

func (d *Max17048Full) handleEdge() {
	status, err := d.PollInterrupt()
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if err == nil && status != 0 && cb != nil {
		cb(status)
	}
}

// PollInterrupt reads STATUS, clears the reported flags and CONFIG.ALRT, and
// returns the Max17048Source* bits (0 = no alert).
func (d *Max17048Full) PollInterrupt() (uint16, error) {
	v, err := d.readWord(max17048RegStatus)
	if err != nil {
		return 0, err
	}
	flags := v & max17048Sources
	if flags != 0 {
		if err := d.ClearStatus(flags); err != nil {
			return 0, err
		}
	}
	return flags, nil
}
