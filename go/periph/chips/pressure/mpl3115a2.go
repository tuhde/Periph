package pressure

import (
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// MPL3115A2 register addresses.
const (
	mpl3115a2RegDRStatus  uint8 = 0x06
	mpl3115a2RegOutPMSB   uint8 = 0x01
	mpl3115a2RegOutPDelta uint8 = 0x07
	mpl3115a2RegWhoAmI    uint8 = 0x0C
	mpl3115a2RegFStatus   uint8 = 0x0D
	mpl3115a2RegFData     uint8 = 0x0E
	mpl3115a2RegFSetup    uint8 = 0x0F
	mpl3115a2RegSysmod    uint8 = 0x11
	mpl3115a2RegIntSource uint8 = 0x12
	mpl3115a2RegPTDataCfg uint8 = 0x13
	mpl3115a2RegBarInMSB  uint8 = 0x14
	mpl3115a2RegPTgtMSB   uint8 = 0x16
	mpl3115a2RegTTgt      uint8 = 0x18
	mpl3115a2RegPWndMSB   uint8 = 0x19
	mpl3115a2RegTWnd      uint8 = 0x1B
	mpl3115a2RegPMin      uint8 = 0x1C
	mpl3115a2RegTMin      uint8 = 0x1F
	mpl3115a2RegPMax      uint8 = 0x21
	mpl3115a2RegTMax      uint8 = 0x24
	mpl3115a2RegCtrlReg1  uint8 = 0x26
	mpl3115a2RegCtrlReg2  uint8 = 0x27
	mpl3115a2RegCtrlReg3  uint8 = 0x28
	mpl3115a2RegCtrlReg4  uint8 = 0x29
	mpl3115a2RegCtrlReg5  uint8 = 0x2A
	mpl3115a2RegOffP      uint8 = 0x2B
	mpl3115a2RegOffT      uint8 = 0x2C
	mpl3115a2RegOffH      uint8 = 0x2D
)

const (
	mpl3115a2ChipID   uint8 = 0xC4
	mpl3115a2Ctrl1Alt uint8 = 0x80
	mpl3115a2Ctrl1Rst uint8 = 0x04
	mpl3115a2Ctrl1Ost uint8 = 0x02
	mpl3115a2Ctrl1Sby uint8 = 0x01
	mpl3115a2DRPtdr   uint8 = 0x08
)

// Minimum time between samples in ms for OS = 0..7 (oversample ratio 1..128).
var mpl3115a2OSRTimeMs = [8]int{6, 10, 18, 34, 66, 130, 258, 512}

// MPL3115A2 interrupt sources (INT_SOURCE / CTRL_REG4 / CTRL_REG5 bit layout).
const (
	MPL3115A2SourceDRDY uint8 = 0x80
	MPL3115A2SourceFIFO uint8 = 0x40
	MPL3115A2SourcePW   uint8 = 0x20
	MPL3115A2SourceTW   uint8 = 0x10
	MPL3115A2SourcePTH  uint8 = 0x08
	MPL3115A2SourceTTH  uint8 = 0x04
	MPL3115A2SourcePCHG uint8 = 0x02
	MPL3115A2SourceTCHG uint8 = 0x01
)

// MPL3115A2 FIFO modes.
const (
	MPL3115A2FIFOOff      uint8 = 0
	MPL3115A2FIFOCircular uint8 = 1
	MPL3115A2FIFOStop     uint8 = 2
)

// Errors returned by the MPL3115A2 driver.
var (
	// ErrMPL3115A2ChipID is returned by Init when WHO_AM_I is not 0xC4.
	ErrMPL3115A2ChipID = errors.New("mpl3115a2: unexpected chip ID")
	// ErrMPL3115A2Timeout is returned when a reset or conversion does not finish in time.
	ErrMPL3115A2Timeout = errors.New("mpl3115a2: timeout")
	// ErrMPL3115A2Argument is returned for an argument outside the supported range.
	ErrMPL3115A2Argument = errors.New("mpl3115a2: argument out of range")
)

func mpl3115a2Pressure(b []byte) float32 {
	return float32((uint32(b[0])<<12)|(uint32(b[1])<<4)|uint32(b[2]>>4)) / 4.0
}

func mpl3115a2Altitude(b []byte) float32 {
	return float32(int16(uint16(b[0])<<8|uint16(b[1]))) + float32(b[2]>>4)/16.0
}

func mpl3115a2Temperature(b []byte) float32 {
	return float32(int8(b[0])) + float32(b[1]>>4)/16.0
}

// MPL3115A2Minimal is the MPL3115A2 barometric pressure / altitude / temperature
// sensor driver (NXP) — minimal interface: one-shot barometer readings.
//
// The constructor does no bus access. Init checks WHO_AM_I (0xC4), resets the chip,
// enables the data-ready flags (PT_DATA_CFG = 0x07) and selects barometer mode with
// oversample ratio 128 (about 512 ms per sample) in STANDBY.
type MPL3115A2Minimal struct {
	conn connection.RegisterConnection
	os   uint8
	alt  bool
}

// NewMPL3115A2Minimal creates the driver. conn must be a register-addressed I²C
// connection bound to address 0x60.
func NewMPL3115A2Minimal(conn connection.RegisterConnection) *MPL3115A2Minimal {
	return &MPL3115A2Minimal{conn: conn, os: 7}
}

func (d *MPL3115A2Minimal) writeReg(reg uint8, data ...byte) error {
	return d.conn.WriteReg(uint32(reg), data)
}

func (d *MPL3115A2Minimal) readReg(reg uint8, n int) ([]byte, error) {
	return d.conn.ReadReg(uint32(reg), n)
}

func (d *MPL3115A2Minimal) readByte(reg uint8) (uint8, error) {
	b, err := d.readReg(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *MPL3115A2Minimal) ctrl1() uint8 {
	c := d.os << 3
	if d.alt {
		c |= mpl3115a2Ctrl1Alt
	}
	return c
}

func (d *MPL3115A2Minimal) timeout() time.Duration {
	return time.Duration(mpl3115a2OSRTimeMs[d.os]*2+50) * time.Millisecond
}

func (d *MPL3115A2Minimal) softReset() error {
	_ = d.writeReg(mpl3115a2RegCtrlReg1, mpl3115a2Ctrl1Rst) // the chip may not ACK while rebooting
	for i := 0; i < 100; i++ {
		time.Sleep(2 * time.Millisecond)
		if c1, err := d.readByte(mpl3115a2RegCtrlReg1); err == nil && c1&mpl3115a2Ctrl1Rst == 0 {
			return nil
		}
	}
	return ErrMPL3115A2Timeout
}

func (d *MPL3115A2Minimal) applyConfig() error {
	if err := d.writeReg(mpl3115a2RegPTDataCfg, 0x07); err != nil {
		return err
	}
	return d.writeReg(mpl3115a2RegCtrlReg1, d.ctrl1())
}

func (d *MPL3115A2Minimal) waitPTDR() error {
	deadline := time.Now().Add(d.timeout() + time.Second)
	for {
		time.Sleep(3 * time.Millisecond)
		s, err := d.readByte(mpl3115a2RegDRStatus)
		if err != nil {
			return err
		}
		if s&mpl3115a2DRPtdr != 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrMPL3115A2Timeout
		}
	}
}

func (d *MPL3115A2Minimal) oneShot() ([]byte, error) {
	c1 := d.ctrl1()
	if err := d.writeReg(mpl3115a2RegCtrlReg1, c1); err != nil { // OST must be 0 before it is set
		return nil, err
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg1, c1|mpl3115a2Ctrl1Ost); err != nil {
		return nil, err
	}
	if err := d.waitPTDR(); err != nil {
		return nil, err
	}
	return d.readReg(mpl3115a2RegOutPMSB, 5)
}

// Init checks WHO_AM_I, soft-resets and applies the default configuration.
func (d *MPL3115A2Minimal) Init() error {
	id, err := d.readByte(mpl3115a2RegWhoAmI)
	if err != nil {
		return err
	}
	if id != mpl3115a2ChipID {
		return ErrMPL3115A2ChipID
	}
	if err := d.softReset(); err != nil {
		return err
	}
	d.os, d.alt = 7, false
	return d.applyConfig()
}

// Read runs one conversion and returns the pressure in pascals and the temperature in °C.
func (d *MPL3115A2Minimal) Read() (pressurePa, temperatureC float32, err error) {
	raw, err := d.oneShot()
	if err != nil {
		return 0, 0, err
	}
	return mpl3115a2Pressure(raw[0:3]), mpl3115a2Temperature(raw[3:5]), nil
}

// ReadPressure runs one conversion and returns the pressure in pascals.
func (d *MPL3115A2Minimal) ReadPressure() (float32, error) {
	p, _, err := d.Read()
	return p, err
}

// ReadTemperature runs one conversion and returns the temperature in °C.
func (d *MPL3115A2Minimal) ReadTemperature() (float32, error) {
	_, t, err := d.Read()
	return t, err
}

// MPL3115A2MinMax holds the captured extremes (pascals or metres per mode, and °C).
type MPL3115A2MinMax struct {
	PMin, PMax, TMin, TMax float32
}

// MPL3115A2Full adds altimeter mode, oversampling, continuous acquisition, offsets,
// sea-level pressure, targets and windows, min/max capture, the 32-sample FIFO and
// interrupts to MPL3115A2Minimal.
type MPL3115A2Full struct {
	MPL3115A2Minimal

	fifoMode    uint8
	mu          sync.Mutex
	callback    func(uint8)
	pin         uint8
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewMPL3115A2Full creates the full driver; same argument as NewMPL3115A2Minimal.
func NewMPL3115A2Full(conn connection.RegisterConnection) *MPL3115A2Full {
	return &MPL3115A2Full{MPL3115A2Minimal: MPL3115A2Minimal{conn: conn, os: 7}}
}

func (d *MPL3115A2Full) standby() (bool, error) {
	c1, err := d.readByte(mpl3115a2RegCtrlReg1)
	if err != nil {
		return false, err
	}
	was := c1&mpl3115a2Ctrl1Sby != 0
	if was {
		if err := d.writeReg(mpl3115a2RegCtrlReg1, c1&^mpl3115a2Ctrl1Sby); err != nil {
			return false, err
		}
	}
	return was, nil
}

func (d *MPL3115A2Full) restore(was bool) error {
	if was {
		return d.writeReg(mpl3115a2RegCtrlReg1, d.ctrl1()|mpl3115a2Ctrl1Sby)
	}
	return nil
}

// SetMode selects barometer (false) or altimeter (true) mode; the chip is dropped to STANDBY and restored.
func (d *MPL3115A2Full) SetMode(altimeter bool) error {
	was, err := d.standby()
	if err != nil {
		return err
	}
	d.alt = altimeter
	if err := d.writeReg(mpl3115a2RegCtrlReg1, d.ctrl1()); err != nil {
		return err
	}
	return d.restore(was)
}

// Read runs one barometer conversion (switching back from altimeter mode if needed).
func (d *MPL3115A2Full) Read() (pressurePa, temperatureC float32, err error) {
	if d.alt {
		if err := d.SetMode(false); err != nil {
			return 0, 0, err
		}
	}
	return d.MPL3115A2Minimal.Read()
}

// ReadPressure runs one barometer conversion and returns the pressure in pascals.
func (d *MPL3115A2Full) ReadPressure() (float32, error) {
	p, _, err := d.Read()
	return p, err
}

// ReadTemperature runs one conversion and returns the temperature in °C.
func (d *MPL3115A2Full) ReadTemperature() (float32, error) {
	_, t, err := d.Read()
	return t, err
}

// ReadAltitude switches to altimeter mode if needed and runs one conversion; the altitude is
// in metres relative to the sea-level pressure (BAR_IN).
func (d *MPL3115A2Full) ReadAltitude() (float32, error) {
	if !d.alt {
		if err := d.SetMode(true); err != nil {
			return 0, err
		}
	}
	raw, err := d.oneShot()
	if err != nil {
		return 0, err
	}
	return mpl3115a2Altitude(raw[0:3]), nil
}

// SetOversample sets the oversample ratio (1, 2, 4, 8, 16, 32, 64 or 128).
func (d *MPL3115A2Full) SetOversample(ratio uint8) error {
	var os uint8
	switch ratio {
	case 1, 2, 4, 8, 16, 32, 64, 128:
		for r := ratio; r > 1; r >>= 1 {
			os++
		}
	default:
		return ErrMPL3115A2Argument
	}
	was, err := d.standby()
	if err != nil {
		return err
	}
	d.os = os
	if err := d.writeReg(mpl3115a2RegCtrlReg1, d.ctrl1()); err != nil {
		return err
	}
	return d.restore(was)
}

// GetOversample returns the current oversample ratio (1..128).
func (d *MPL3115A2Full) GetOversample() uint8 { return 1 << d.os }

// StartContinuous starts periodic acquisition (ACTIVE mode) with a step of 2^stepExp seconds (0-15).
func (d *MPL3115A2Full) StartContinuous(stepExp uint8) error {
	if stepExp > 15 {
		return ErrMPL3115A2Argument
	}
	if _, err := d.standby(); err != nil {
		return err
	}
	c2, err := d.readByte(mpl3115a2RegCtrlReg2)
	if err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg2, c2&0xF0|stepExp); err != nil {
		return err
	}
	return d.writeReg(mpl3115a2RegCtrlReg1, d.ctrl1()|mpl3115a2Ctrl1Sby)
}

// StopContinuous stops periodic acquisition (back to STANDBY).
func (d *MPL3115A2Full) StopContinuous() error {
	return d.writeReg(mpl3115a2RegCtrlReg1, d.ctrl1())
}

// DataReady reports whether new pressure/altitude or temperature data is ready (DR_STATUS.PTDR).
func (d *MPL3115A2Full) DataReady() (bool, error) {
	s, err := d.readByte(mpl3115a2RegDRStatus)
	return s&mpl3115a2DRPtdr != 0, err
}

// ReadContinuous waits for PTDR and reads the latest sample: value in pascals (barometer) or
// metres (altimeter) and the temperature in °C.
func (d *MPL3115A2Full) ReadContinuous() (value, temperatureC float32, err error) {
	if err := d.waitPTDR(); err != nil {
		return 0, 0, err
	}
	raw, err := d.readReg(mpl3115a2RegOutPMSB, 5)
	if err != nil {
		return 0, 0, err
	}
	if d.alt {
		value = mpl3115a2Altitude(raw[0:3])
	} else {
		value = mpl3115a2Pressure(raw[0:3])
	}
	return value, mpl3115a2Temperature(raw[3:5]), nil
}

// ReadDelta returns the change since the previous sample (pascals or metres, and °C).
func (d *MPL3115A2Full) ReadDelta() (deltaValue, deltaTemperatureC float32, err error) {
	raw, err := d.readReg(mpl3115a2RegOutPDelta, 5)
	if err != nil {
		return 0, 0, err
	}
	if d.alt {
		deltaValue = mpl3115a2Altitude(raw[0:3])
	} else {
		deltaValue = mpl3115a2Pressure(raw[0:3])
		if deltaValue >= 262144 {
			deltaValue -= 524288
		}
	}
	return deltaValue, mpl3115a2Temperature(raw[3:5]), nil
}

// SetSeaLevelPressure writes BAR_IN, the altitude reference (2 Pa resolution, default 101326 Pa).
func (d *MPL3115A2Full) SetSeaLevelPressure(pa float32) error {
	raw := uint16(math.Round(float64(pa) / 2))
	return d.writeReg(mpl3115a2RegBarInMSB, byte(raw>>8), byte(raw))
}

// SetOffsets writes the offset trims: pressure (4 Pa/LSB), temperature (0.0625 °C/LSB), altitude (m).
func (d *MPL3115A2Full) SetOffsets(pressurePa, temperatureC float32, altitudeM int8) error {
	op := math.Round(float64(pressurePa) / 4)
	ot := math.Round(float64(temperatureC) / 0.0625)
	if op < -128 || op > 127 || ot < -128 || ot > 127 {
		return ErrMPL3115A2Argument
	}
	if err := d.writeReg(mpl3115a2RegOffP, byte(int8(op))); err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegOffT, byte(int8(ot))); err != nil {
		return err
	}
	return d.writeReg(mpl3115a2RegOffH, byte(altitudeM))
}

// SetTarget programs the threshold/window targets (pascals or metres per mode; temperature in °C).
func (d *MPL3115A2Full) SetTarget(value, window float32, temperatureC int8, temperatureWindow uint8) error {
	var tgt, wnd uint16
	if d.alt {
		tgt, wnd = uint16(int16(math.Round(float64(value)))), uint16(math.Round(float64(window)))
	} else {
		tgt, wnd = uint16(math.Round(float64(value)/2)), uint16(math.Round(float64(window)/2))
	}
	was, err := d.standby()
	if err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegPTgtMSB, byte(tgt>>8), byte(tgt)); err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegTTgt, byte(temperatureC)); err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegPWndMSB, byte(wnd>>8), byte(wnd)); err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegTWnd, temperatureWindow); err != nil {
		return err
	}
	return d.restore(was)
}

// LoadCurrentAsTarget uses the current output as the threshold target (ALARM_SEL = 1, pulse LOAD_OUTPUT).
func (d *MPL3115A2Full) LoadCurrentAsTarget() error {
	c2, err := d.readByte(mpl3115a2RegCtrlReg2)
	if err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg2, (c2|0x10)&^0x20); err != nil {
		return err
	}
	return d.writeReg(mpl3115a2RegCtrlReg2, c2|0x30)
}

// ReadMinMax returns the captured minimum and maximum values.
func (d *MPL3115A2Full) ReadMinMax() (MPL3115A2MinMax, error) {
	var out MPL3115A2MinMax
	pm, err := d.readReg(mpl3115a2RegPMin, 3)
	if err != nil {
		return out, err
	}
	px, err := d.readReg(mpl3115a2RegPMax, 3)
	if err != nil {
		return out, err
	}
	tm, err := d.readReg(mpl3115a2RegTMin, 2)
	if err != nil {
		return out, err
	}
	tx, err := d.readReg(mpl3115a2RegTMax, 2)
	if err != nil {
		return out, err
	}
	conv := mpl3115a2Pressure
	if d.alt {
		conv = mpl3115a2Altitude
	}
	return MPL3115A2MinMax{PMin: conv(pm), PMax: conv(px), TMin: mpl3115a2Temperature(tm), TMax: mpl3115a2Temperature(tx)}, nil
}

// ClearMinMax clears the min/max registers.
func (d *MPL3115A2Full) ClearMinMax() error {
	for _, w := range []struct {
		reg uint8
		n   int
	}{{mpl3115a2RegPMin, 3}, {mpl3115a2RegTMin, 2}, {mpl3115a2RegPMax, 3}, {mpl3115a2RegTMax, 2}} {
		if err := d.writeReg(w.reg, make([]byte, w.n)...); err != nil {
			return err
		}
	}
	return nil
}

// FIFOSetup configures the FIFO: mode 0 off, 1 circular, 2 stop when full; watermark 0-63.
func (d *MPL3115A2Full) FIFOSetup(mode, watermark uint8) error {
	if mode > 2 || (d.fifoMode != 0 && mode != 0 && mode != d.fifoMode) {
		return ErrMPL3115A2Argument
	}
	was, err := d.standby()
	if err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegFSetup, mode<<6|watermark&0x3F); err != nil {
		return err
	}
	d.fifoMode = mode
	return d.restore(was)
}

// FIFOStatus reads F_STATUS: overflow flag, watermark flag and sample count.
func (d *MPL3115A2Full) FIFOStatus() (overflow, watermark bool, count uint8, err error) {
	s, err := d.readByte(mpl3115a2RegFStatus)
	if err != nil {
		return false, false, 0, err
	}
	return s&0x80 != 0, s&0x40 != 0, s & 0x3F, nil
}

// MPL3115A2Sample is one FIFO sample: pascals or metres per mode, and °C.
type MPL3115A2Sample struct {
	Value, TemperatureC float32
}

// FIFORead drains the FIFO and returns the converted samples.
func (d *MPL3115A2Full) FIFORead() ([]MPL3115A2Sample, error) {
	_, _, count, err := d.FIFOStatus()
	if err != nil {
		return nil, err
	}
	out := make([]MPL3115A2Sample, 0, count)
	for i := uint8(0); i < count; i++ {
		raw, err := d.readReg(mpl3115a2RegFData, 5)
		if err != nil {
			return out, err
		}
		v := mpl3115a2Pressure(raw[0:3])
		if d.alt {
			v = mpl3115a2Altitude(raw[0:3])
		}
		out = append(out, MPL3115A2Sample{Value: v, TemperatureC: mpl3115a2Temperature(raw[3:5])})
	}
	return out, nil
}

// Reset soft-resets the chip and restores the default configuration (as Init).
func (d *MPL3115A2Full) Reset() error {
	if err := d.softReset(); err != nil {
		return err
	}
	d.os, d.alt, d.fifoMode = 7, false, 0
	return d.applyConfig()
}

// WhoAmI returns the WHO_AM_I register (0xC4).
func (d *MPL3115A2Full) WhoAmI() (uint8, error) { return d.readByte(mpl3115a2RegWhoAmI) }

// IsActive reports whether SYSMOD says ACTIVE mode.
func (d *MPL3115A2Full) IsActive() (bool, error) {
	s, err := d.readByte(mpl3115a2RegSysmod)
	return s&0x01 != 0, err
}

// SetInterruptPins writes CTRL_REG3: INT polarity (true = active high) and open-drain per pin.
func (d *MPL3115A2Full) SetInterruptPins(polarity1, openDrain1, polarity2, openDrain2 bool) error {
	was, err := d.standby()
	if err != nil {
		return err
	}
	var v uint8
	if polarity1 {
		v |= 0x20
	}
	if openDrain1 {
		v |= 0x10
	}
	if polarity2 {
		v |= 0x02
	}
	if openDrain2 {
		v |= 0x01
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg3, v); err != nil {
		return err
	}
	return d.restore(was)
}

// EnableInterrupt enables an interrupt source (MPL3115A2Source* mask) and routes it to INT1 (pin 1) or INT2 (pin 2).
func (d *MPL3115A2Full) EnableInterrupt(source, pin uint8) error {
	if pin != 1 && pin != 2 {
		return ErrMPL3115A2Argument
	}
	was, err := d.standby()
	if err != nil {
		return err
	}
	c4, err := d.readByte(mpl3115a2RegCtrlReg4)
	if err != nil {
		return err
	}
	c5, err := d.readByte(mpl3115a2RegCtrlReg5)
	if err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg4, c4|source); err != nil {
		return err
	}
	if pin == 1 {
		c5 |= source
	} else {
		c5 &^= source
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg5, c5); err != nil {
		return err
	}
	return d.restore(was)
}

// DisableInterrupt disables an interrupt source (clears it in CTRL_REG4).
func (d *MPL3115A2Full) DisableInterrupt(source uint8) error {
	was, err := d.standby()
	if err != nil {
		return err
	}
	c4, err := d.readByte(mpl3115a2RegCtrlReg4)
	if err != nil {
		return err
	}
	if err := d.writeReg(mpl3115a2RegCtrlReg4, c4&^source); err != nil {
		return err
	}
	return d.restore(was)
}

// PollInterrupt reads INT_SOURCE (bitmask of MPL3115A2Source* flags).
func (d *MPL3115A2Full) PollInterrupt() (uint8, error) { return d.readByte(mpl3115a2RegIntSource) }

// OnInterrupt subscribes callback to INT1 (pin 1) or INT2 (pin 2); it receives INT_SOURCE masked
// to the sources routed to that pin. With the connection's IntPin wired it fires on falling edges
// (the default active-low polarity); otherwise a 5 ms polling goroutine calls back whenever a
// routed source is flagged.
func (d *MPL3115A2Full) OnInterrupt(callback func(status uint8), pin uint8) error {
	if pin != 1 && pin != 2 {
		return ErrMPL3115A2Argument
	}
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	d.pin = pin
	d.mu.Unlock()
	ip := d.conn.IntPin()
	if ip == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		ip = poll
	}
	unsub := ip.OnEdge(connection.Falling, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *MPL3115A2Full) OffInterrupt() error {
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

func (d *MPL3115A2Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	c5, err := d.readByte(mpl3115a2RegCtrlReg5)
	if err != nil {
		return
	}
	d.mu.Lock()
	cb, pin := d.callback, d.pin
	d.mu.Unlock()
	if pin == 1 {
		status &= c5
	} else {
		status &^= c5
	}
	if status != 0 && cb != nil {
		cb(status)
	}
}
