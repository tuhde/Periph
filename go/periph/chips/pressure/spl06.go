package pressure

import (
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// SPL06 register addresses.
const (
	spl06RegPrsB2   uint8 = 0x00
	spl06RegTmpB2   uint8 = 0x03
	spl06RegPrsCfg  uint8 = 0x06
	spl06RegTmpCfg  uint8 = 0x07
	spl06RegMeasCfg uint8 = 0x08
	spl06RegCfgReg  uint8 = 0x09
	spl06RegIntSts  uint8 = 0x0A
	spl06RegFifoSts uint8 = 0x0B
	spl06RegReset   uint8 = 0x0C
	spl06RegID      uint8 = 0x0D
	spl06RegCoef    uint8 = 0x10
)

const (
	spl06ChipID        uint8 = 0x10
	spl06MeasCoefRdy   uint8 = 0x80
	spl06MeasSensorRdy uint8 = 0x40
	spl06MeasTmpRdy    uint8 = 0x20
	spl06MeasPrsRdy    uint8 = 0x10
	spl06CmdIdle       uint8 = 0x00
	spl06CmdPressure   uint8 = 0x01
	spl06CmdTemp       uint8 = 0x02
	spl06CfgIntHL      uint8 = 0x80
	spl06CfgIntFifo    uint8 = 0x40
	spl06CfgIntPrs     uint8 = 0x20
	spl06CfgIntTmp     uint8 = 0x10
	spl06CfgTShift     uint8 = 0x08
	spl06CfgPShift     uint8 = 0x04
	spl06CfgFifoEn     uint8 = 0x02
	spl06TmpExt        uint8 = 0x80
)

// Scale factors kP / kT indexed by log2(oversampling) (datasheet Table 4).
var spl06Scale = [8]float64{524288, 1572864, 3670016, 7864320, 253952, 516096, 1040384, 2088960}

// Typical measurement time in ms indexed by log2(oversampling), rounded up.
var spl06MeasMs = [8]int{4, 6, 9, 15, 28, 54, 105, 207}

// SPL06 interrupt sources (INT_STS bit layout).
const (
	// SPL06SourcePressure flags a ready pressure result (INT_STS.INT_PRS).
	SPL06SourcePressure uint8 = 0x01
	// SPL06SourceTemperature flags a ready temperature result (INT_STS.INT_TMP).
	SPL06SourceTemperature uint8 = 0x02
	// SPL06SourceFIFOFull flags a full FIFO (INT_STS.INT_FIFO_FULL).
	SPL06SourceFIFOFull uint8 = 0x04
)

// SPL06 FIFO entry kinds (the LSB of the raw value).
const (
	// SPL06KindTemperature marks a temperature FIFO entry (°C).
	SPL06KindTemperature = "temperature"
	// SPL06KindPressure marks a pressure FIFO entry (Pa).
	SPL06KindPressure = "pressure"
)

// Errors returned by the SPL06 driver.
var (
	// ErrSPL06ChipID is returned by Init when the ID register is not 0x10.
	ErrSPL06ChipID = errors.New("spl06: unexpected chip ID")
	// ErrSPL06Timeout is returned when the chip does not become ready or finish a measurement in time.
	ErrSPL06Timeout = errors.New("spl06: timeout")
	// ErrSPL06Argument is returned for an oversampling or rate other than a power of two 1-128.
	ErrSPL06Argument = errors.New("spl06: argument out of range")
)

// SPL06Measurement is one compensated reading.
type SPL06Measurement struct {
	TemperatureC float64 // degrees Celsius
	PressurePa   float64 // pascals
}

// SPL06Coefficients holds the decoded calibration coefficients (two's complement, sign-extended).
type SPL06Coefficients struct {
	C0, C1, C00, C10, C01, C11, C20, C21, C30 int32
}

// SPL06FifoEntry is one FIFO entry: Kind is SPL06KindPressure (Value in Pa) or
// SPL06KindTemperature (Value in °C).
type SPL06FifoEntry struct {
	Kind  string
	Value float64
}

func spl06Signed(v uint32, bits uint) int32 {
	sign := uint32(1) << (bits - 1)
	v &= (sign << 1) - 1
	if v&sign != 0 {
		return int32(v) - int32(sign<<1)
	}
	return int32(v)
}

func spl06Raw24(b []byte) int32 {
	return spl06Signed(uint32(b[0])<<16|uint32(b[1])<<8|uint32(b[2]), 24)
}

func spl06Log2(v int) (uint8, bool) {
	for i := uint8(0); i < 8; i++ {
		if v == 1<<i {
			return i, true
		}
	}
	return 0, false
}

// SPL06Minimal is the SPL06-007 barometric pressure and temperature sensor driver
// (Goertek) — minimal interface: one call returns compensated temperature and pressure.
//
// The constructor does no bus access. Init checks ID (0x10), waits for SENSOR_RDY /
// COEF_RDY, reads the 18 coefficient bytes and writes TMP_CFG = 0x80 (external
// temperature sensor, 1×), PRS_CFG = 0x04 (16×) and CFG_REG = 0x04 (P_SHIFT). The chip
// stays in standby between readings; each Read runs one temperature and one pressure
// measurement in command mode. I²C address 0x77 (SDO high or open) or 0x76 (SDO low).
type SPL06Minimal struct {
	conn     connection.RegisterConnection
	coef     SPL06Coefficients
	pOs      uint8
	tOs      uint8
	cfg      uint8
	lastTraw int32
}

// NewSPL06Minimal creates the driver. conn must be a register-addressed I²C connection
// bound to address 0x77 or 0x76. It never returns an error (no bus access).
func NewSPL06Minimal(conn connection.RegisterConnection) (*SPL06Minimal, error) {
	return &SPL06Minimal{conn: conn, pOs: 4, cfg: spl06CfgPShift}, nil
}

func (d *SPL06Minimal) writeReg(reg uint8, value uint8) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *SPL06Minimal) readReg(reg uint8, n int) ([]byte, error) {
	return d.conn.ReadReg(uint32(reg), n)
}

func (d *SPL06Minimal) readByte(reg uint8) (uint8, error) {
	b, err := d.readReg(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// Init verifies the chip, reads the calibration coefficients and applies the defaults.
func (d *SPL06Minimal) Init() error {
	id, err := d.readByte(spl06RegID)
	if err != nil {
		return err
	}
	if id != spl06ChipID {
		return ErrSPL06ChipID
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	if err := d.readCoefficients(); err != nil {
		return err
	}
	return d.applyDefaults()
}

func (d *SPL06Minimal) waitReady() error {
	mask := spl06MeasCoefRdy | spl06MeasSensorRdy
	for i := 0; i < 40; i++ {
		if m, err := d.readByte(spl06RegMeasCfg); err == nil && m&mask == mask {
			return nil
		}
		time.Sleep(5 * time.Millisecond)
	}
	return ErrSPL06Timeout
}

func (d *SPL06Minimal) readCoefficients() error {
	b, err := d.readReg(spl06RegCoef, 18)
	if err != nil {
		return err
	}
	u := func(i int) uint32 { return uint32(b[i]) }
	d.coef = SPL06Coefficients{
		C0:  spl06Signed(u(0)<<4|u(1)>>4, 12),
		C1:  spl06Signed((u(1)&0x0F)<<8|u(2), 12),
		C00: spl06Signed(u(3)<<12|u(4)<<4|u(5)>>4, 20),
		C10: spl06Signed((u(5)&0x0F)<<16|u(6)<<8|u(7), 20),
		C01: spl06Signed(u(8)<<8|u(9), 16),
		C11: spl06Signed(u(10)<<8|u(11), 16),
		C20: spl06Signed(u(12)<<8|u(13), 16),
		C21: spl06Signed(u(14)<<8|u(15), 16),
		C30: spl06Signed(u(16)<<8|u(17), 16),
	}
	return nil
}

func (d *SPL06Minimal) applyDefaults() error {
	d.pOs, d.tOs, d.cfg = 4, 0, spl06CfgPShift
	if err := d.writeReg(spl06RegTmpCfg, spl06TmpExt); err != nil {
		return err
	}
	if err := d.writeReg(spl06RegPrsCfg, 0x04); err != nil {
		return err
	}
	return d.writeReg(spl06RegCfgReg, d.cfg)
}

func (d *SPL06Minimal) measure(command, flag, os uint8) (int32, error) {
	if err := d.writeReg(spl06RegMeasCfg, command); err != nil {
		return 0, err
	}
	wait := spl06MeasMs[os]
	time.Sleep(time.Duration(wait) * time.Millisecond)
	deadline := time.Now().Add(time.Duration(wait+50) * time.Millisecond)
	for {
		m, err := d.readByte(spl06RegMeasCfg)
		if err != nil {
			return 0, err
		}
		if m&flag != 0 {
			break
		}
		if time.Now().After(deadline) {
			return 0, ErrSPL06Timeout
		}
		time.Sleep(2 * time.Millisecond)
	}
	reg := spl06RegTmpB2
	if command == spl06CmdPressure {
		reg = spl06RegPrsB2
	}
	b, err := d.readReg(reg, 3)
	if err != nil {
		return 0, err
	}
	return spl06Raw24(b), nil
}

func (d *SPL06Minimal) measureRaw() (int32, int32, error) {
	traw, err := d.measure(spl06CmdTemp, spl06MeasTmpRdy, d.tOs)
	if err != nil {
		return 0, 0, err
	}
	praw, err := d.measure(spl06CmdPressure, spl06MeasPrsRdy, d.pOs)
	if err != nil {
		return 0, 0, err
	}
	d.lastTraw = traw
	return praw, traw, nil
}

func (d *SPL06Minimal) temperature(traw int32) float64 {
	return spl06CompT(&d.coef, float64(traw)/spl06Scale[d.tOs])
}

func (d *SPL06Minimal) pressure(praw, traw int32) float64 {
	return spl06CompP(&d.coef, float64(praw)/spl06Scale[d.pOs], float64(traw)/spl06Scale[d.tOs])
}

func spl06CompT(c *SPL06Coefficients, t float64) float64 {
	return float64(c.C0)*0.5 + float64(c.C1)*t
}

func spl06CompP(c *SPL06Coefficients, p, t float64) float64 {
	return float64(c.C00) + p*(float64(c.C10)+p*(float64(c.C20)+p*float64(c.C30))) +
		t*float64(c.C01) + t*p*(float64(c.C11)+p*float64(c.C21))
}

// Read measures temperature, then pressure (command mode), and compensates both.
func (d *SPL06Minimal) Read() (SPL06Measurement, error) {
	praw, traw, err := d.measureRaw()
	if err != nil {
		return SPL06Measurement{}, err
	}
	return SPL06Measurement{TemperatureC: d.temperature(traw), PressurePa: d.pressure(praw, traw)}, nil
}

// SPL06Full extends SPL06Minimal with oversampling and rate configuration, background
// (continuous) mode, the 32-entry FIFO, raw values and compensation, altitude, soft
// reset and the data-ready / FIFO-full interrupt.
//
// The interrupt output shares the SDO pin. INT_HL stays at its reset value 0 (active
// low), whose idle-high level matches the default address 0x77.
type SPL06Full struct {
	SPL06Minimal

	pRate       uint8
	tRate       uint8
	continuous  uint8
	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewSPL06Full creates the full driver; same argument as NewSPL06Minimal.
func NewSPL06Full(conn connection.RegisterConnection) (*SPL06Full, error) {
	return &SPL06Full{SPL06Minimal: SPL06Minimal{conn: conn, pOs: 4, cfg: spl06CfgPShift}}, nil
}

func (d *SPL06Full) writeCfg() error { return d.writeReg(spl06RegCfgReg, d.cfg) }

// Read measures temperature, then pressure; in background mode it returns the latest
// results instead (see ReadContinuous).
func (d *SPL06Full) Read() (SPL06Measurement, error) {
	if d.continuous != 0 {
		return d.ReadContinuous()
	}
	return d.SPL06Minimal.Read()
}

// ReadPressure measures temperature and pressure and returns the pressure in pascals.
func (d *SPL06Full) ReadPressure() (float64, error) {
	m, err := d.Read()
	return m.PressurePa, err
}

// ReadTemperature measures and returns the temperature in degrees Celsius.
func (d *SPL06Full) ReadTemperature() (float64, error) {
	if d.continuous != 0 {
		m, err := d.ReadContinuous()
		return m.TemperatureC, err
	}
	traw, err := d.measure(spl06CmdTemp, spl06MeasTmpRdy, d.tOs)
	if err != nil {
		return 0, err
	}
	d.lastTraw = traw
	return d.temperature(traw), nil
}

// ReadRaw returns the raw 24-bit two's-complement results (praw, traw). Command mode
// measures temperature, then pressure; background mode reads the latest results
// without starting a measurement.
func (d *SPL06Full) ReadRaw() (int32, int32, error) {
	if d.continuous != 0 {
		b, err := d.readReg(spl06RegPrsB2, 6)
		if err != nil {
			return 0, 0, err
		}
		praw, traw := spl06Raw24(b[0:3]), spl06Raw24(b[3:6])
		d.lastTraw = traw
		return praw, traw, nil
	}
	return d.measureRaw()
}

// SetPressureConfig writes PRS_CFG and P_SHIFT (set above 8×). oversampling is 1, 2, 4,
// …, 128 (spec default 16); rate is the background measurements per second, 1-128
// (default 1).
func (d *SPL06Full) SetPressureConfig(oversampling, rate int) error {
	os, ok1 := spl06Log2(oversampling)
	r, ok2 := spl06Log2(rate)
	if !ok1 || !ok2 {
		return ErrSPL06Argument
	}
	if err := d.writeReg(spl06RegPrsCfg, r<<4|os); err != nil {
		return err
	}
	d.pOs, d.pRate = os, r
	if oversampling > 8 {
		d.cfg |= spl06CfgPShift
	} else {
		d.cfg &^= spl06CfgPShift
	}
	return d.writeCfg()
}

// SetTemperatureConfig writes TMP_CFG (always TMP_EXT = 1) and T_SHIFT (set above 8×).
// oversampling is 1, 2, 4, …, 128 (spec default 1); rate is 1-128 per second (default 1).
func (d *SPL06Full) SetTemperatureConfig(oversampling, rate int) error {
	os, ok1 := spl06Log2(oversampling)
	r, ok2 := spl06Log2(rate)
	if !ok1 || !ok2 {
		return ErrSPL06Argument
	}
	if err := d.writeReg(spl06RegTmpCfg, spl06TmpExt|r<<4|os); err != nil {
		return err
	}
	d.tOs, d.tRate = os, r
	if oversampling > 8 {
		d.cfg |= spl06CfgTShift
	} else {
		d.cfg &^= spl06CfgTShift
	}
	return d.writeCfg()
}

// StartContinuous starts background mode (MEAS_CTRL = 101 / 110 / 111). It returns
// ErrSPL06Argument if both pressure and temperature are false.
func (d *SPL06Full) StartContinuous(pressure, temperature bool) error {
	if !pressure && !temperature {
		return ErrSPL06Argument
	}
	mode := uint8(0x04)
	if pressure {
		mode |= 0x01
	}
	if temperature {
		mode |= 0x02
	}
	if err := d.writeReg(spl06RegMeasCfg, mode); err != nil {
		return err
	}
	d.continuous = mode
	return nil
}

// Stop ends background mode and returns to standby (MEAS_CTRL = 000).
func (d *SPL06Full) Stop() error {
	if err := d.writeReg(spl06RegMeasCfg, spl06CmdIdle); err != nil {
		return err
	}
	d.continuous = 0
	return nil
}

// ReadContinuous waits for the background ready flags and returns the latest results
// without starting a measurement.
func (d *SPL06Full) ReadContinuous() (SPL06Measurement, error) {
	var flags uint8
	if d.continuous&0x01 != 0 {
		flags |= spl06MeasPrsRdy
	}
	if d.continuous&0x02 != 0 {
		flags |= spl06MeasTmpRdy
	}
	if flags != 0 {
		slow := d.pRate
		if d.tRate < slow {
			slow = d.tRate
		}
		deadline := time.Now().Add(time.Duration(2000/(1<<slow)+500) * time.Millisecond)
		for {
			m, err := d.readByte(spl06RegMeasCfg)
			if err != nil {
				return SPL06Measurement{}, err
			}
			if m&flags == flags {
				break
			}
			if time.Now().After(deadline) {
				return SPL06Measurement{}, ErrSPL06Timeout
			}
			time.Sleep(2 * time.Millisecond)
		}
	}
	b, err := d.readReg(spl06RegPrsB2, 6)
	if err != nil {
		return SPL06Measurement{}, err
	}
	praw, traw := spl06Raw24(b[0:3]), spl06Raw24(b[3:6])
	d.lastTraw = traw
	return SPL06Measurement{TemperatureC: d.temperature(traw), PressurePa: d.pressure(praw, traw)}, nil
}

// DataReady reads the MEAS_CFG ready flags (pressureReady, temperatureReady).
func (d *SPL06Full) DataReady() (bool, bool, error) {
	m, err := d.readByte(spl06RegMeasCfg)
	if err != nil {
		return false, false, err
	}
	return m&spl06MeasPrsRdy != 0, m&spl06MeasTmpRdy != 0, nil
}

// EnableFIFO enables the 32-entry result FIFO (CFG_REG.FIFO_EN).
func (d *SPL06Full) EnableFIFO() error {
	d.cfg |= spl06CfgFifoEn
	return d.writeCfg()
}

// DisableFIFO disables the result FIFO.
func (d *SPL06Full) DisableFIFO() error {
	d.cfg &^= spl06CfgFifoEn
	return d.writeCfg()
}

// ReadFIFO drains the FIFO until FIFO_EMPTY (at most 32 entries). The LSB of each raw
// entry flags its kind (1 = pressure, 0 = temperature); pressure entries are compensated
// with the most recent temperature entry (or the last measured temperature).
func (d *SPL06Full) ReadFIFO() ([]SPL06FifoEntry, error) {
	out := make([]SPL06FifoEntry, 0, 32)
	for i := 0; i < 32; i++ {
		s, err := d.readByte(spl06RegFifoSts)
		if err != nil {
			return out, err
		}
		if s&0x01 != 0 {
			break
		}
		b, err := d.readReg(spl06RegPrsB2, 3)
		if err != nil {
			return out, err
		}
		raw := spl06Raw24(b)
		if raw == -0x800000 {
			break
		}
		if raw&1 != 0 {
			out = append(out, SPL06FifoEntry{Kind: SPL06KindPressure, Value: d.pressure(raw&^1, d.lastTraw)})
		} else {
			d.lastTraw = raw
			out = append(out, SPL06FifoEntry{Kind: SPL06KindTemperature, Value: d.temperature(raw)})
		}
	}
	return out, nil
}

// FlushFIFO discards the FIFO contents (RESET.FIFO_FLUSH).
func (d *SPL06Full) FlushFIFO() error { return d.writeReg(spl06RegReset, 0x80) }

// FIFOStatus reads FIFO_STS (empty, full).
func (d *SPL06Full) FIFOStatus() (bool, bool, error) {
	s, err := d.readByte(spl06RegFifoSts)
	if err != nil {
		return false, false, err
	}
	return s&0x01 != 0, s&0x02 != 0, nil
}

// Reset performs a soft reset (RESET = 0x09), waits for the ready flags, re-reads the
// coefficients and restores the Init defaults.
func (d *SPL06Full) Reset() error {
	_ = d.writeReg(spl06RegReset, 0x09) // the chip may not acknowledge while it resets
	time.Sleep(12 * time.Millisecond)
	if err := d.waitReady(); err != nil {
		return err
	}
	if err := d.readCoefficients(); err != nil {
		return err
	}
	if err := d.applyDefaults(); err != nil {
		return err
	}
	d.pRate, d.tRate, d.continuous = 0, 0, 0
	return nil
}

// Coefficients returns the decoded calibration coefficients.
func (d *SPL06Full) Coefficients() SPL06Coefficients { return d.coef }

// Compensate converts raw values with the stored coefficients (no bus access).
// pOversampling and tOversampling are the oversampling rates praw and traw were
// measured with.
func (d *SPL06Full) Compensate(praw, traw int32, pOversampling, tOversampling int) (SPL06Measurement, error) {
	po, ok1 := spl06Log2(pOversampling)
	to, ok2 := spl06Log2(tOversampling)
	if !ok1 || !ok2 {
		return SPL06Measurement{}, ErrSPL06Argument
	}
	p := float64(praw) / spl06Scale[po]
	t := float64(traw) / spl06Scale[to]
	return SPL06Measurement{TemperatureC: spl06CompT(&d.coef, t), PressurePa: spl06CompP(&d.coef, p, t)}, nil
}

// Altitude measures the pressure and converts it to altitude in metres (international
// barometric formula) against seaLevelPa (spec default 101325 Pa).
func (d *SPL06Full) Altitude(seaLevelPa float64) (float64, error) {
	p, err := d.ReadPressure()
	if err != nil {
		return 0, err
	}
	return 44330.0 * (1.0 - math.Pow(p/seaLevelPa, 1.0/5.255)), nil
}

func spl06IntBits(source uint8) uint8 {
	var bits uint8
	if source&SPL06SourcePressure != 0 {
		bits |= spl06CfgIntPrs
	}
	if source&SPL06SourceTemperature != 0 {
		bits |= spl06CfgIntTmp
	}
	if source&SPL06SourceFIFOFull != 0 {
		bits |= spl06CfgIntFifo
	}
	return bits
}

// EnableInterrupt enables interrupt sources on SDO (SPL06Source* bitwise OR).
func (d *SPL06Full) EnableInterrupt(source uint8) error {
	d.cfg |= spl06IntBits(source)
	return d.writeCfg()
}

// DisableInterrupt disables interrupt sources (SPL06Source* bitwise OR).
func (d *SPL06Full) DisableInterrupt(source uint8) error {
	d.cfg &^= spl06IntBits(source)
	return d.writeCfg()
}

// PollInterrupt reads INT_STS (cleared on read) and returns the SPL06Source* bitmask
// (0 = none).
func (d *SPL06Full) PollInterrupt() (uint8, error) {
	s, err := d.readByte(spl06RegIntSts)
	return s & 0x07, err
}

// OnInterrupt subscribes callback; it receives the SPL06Source* bitmask. With the
// connection's IntPin wired it fires on the edge matching INT_HL (falling by default);
// otherwise a 5 ms polling goroutine calls back whenever a source is flagged.
func (d *SPL06Full) OnInterrupt(callback func(status uint8)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()
	ip := d.conn.IntPin()
	if ip == nil {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		ip = poll
	}
	trigger := connection.Falling
	if d.cfg&spl06CfgIntHL != 0 {
		trigger = connection.Rising
	}
	unsub := ip.OnEdge(trigger, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *SPL06Full) OffInterrupt() error {
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

func (d *SPL06Full) handleEdge() {
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
