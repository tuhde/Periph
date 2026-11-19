// Package color holds the colour / spectral sensor drivers.
package color

import (
	"encoding/binary"
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// AS7262 hardware and virtual registers.
const (
	as7262HWStatus = 0x00
	as7262HWWrite  = 0x01
	as7262HWRead   = 0x02
	as7262TxValid  = 0x02
	as7262RxValid  = 0x01

	as7262RegHWVersion = 0x00
	as7262RegFWVersion = 0x02
	as7262RegControl   = 0x04
	as7262RegIntT      = 0x05
	as7262RegTemp      = 0x06
	as7262RegLED       = 0x07
	as7262RegRaw       = 0x08
	as7262RegCal       = 0x14

	as7262CtrlRst     = 0x80
	as7262CtrlInt     = 0x40
	as7262CtrlDataRdy = 0x02

	as7262IntTDefault = 0x3B
	as7262GainDefault = 2
	as7262StepMS      = 2.8
)

var (
	as7262Gains       = [4]float32{1, 3.7, 16, 64}
	as7262DrvCurrents = [4]float32{12.5, 25, 50, 100}
	as7262IndCurrents = [4]float32{1, 2, 4, 8}
)

// ErrAS7262Timeout is returned when a handshake, boot, reset or conversion poll times out.
var ErrAS7262Timeout = errors.New("as7262: time-out")

// ErrAS7262NotFound is returned when HW_VERSION does not read 0x40.
var ErrAS7262NotFound = errors.New("as7262: device not found (HW_VERSION != 0x40)")

// ErrAS7262Argument is returned for an unsupported gain, integration time, mode, channel or LED current.
var ErrAS7262Argument = errors.New("as7262: invalid argument")

// AS7262Channels holds one value per spectral channel.
type AS7262Channels[T float32 | uint16] struct {
	Violet, Blue, Green, Yellow, Orange, Red T
}

func as7262NewChannels[T float32 | uint16](v [6]T) AS7262Channels[T] {
	return AS7262Channels[T]{v[0], v[1], v[2], v[3], v[4], v[5]}
}

// Array returns the channels in the order V, B, G, Y, O, R.
func (c AS7262Channels[T]) Array() [6]T {
	return [6]T{c.Violet, c.Blue, c.Green, c.Yellow, c.Orange, c.Red}
}

func as7262Nearest(options [4]float32, value float32) (uint8, bool) {
	if value > options[3] {
		return 0, false
	}
	best := 0
	for i := range options {
		if math.Abs(float64(options[i]-value)) < math.Abs(float64(options[best]-value)) {
			best = i
		}
	}
	return uint8(best), true
}

// AS7262Minimal is the AS7262 6-channel visible spectral sensor — minimal interface.
//
// Every sensor register is a virtual register reached through three hardware
// registers (STATUS 0x00, WRITE 0x01, READ 0x02); this type implements the
// handshake. The constructor soft-resets the chip and applies the defaults:
// gain 16x, INT_T 0x3B (165.2 ms), one-shot bank mode 3, both LEDs off, INT off.
type AS7262Minimal struct {
	conn connection.RegisterConnection
}

// NewAS7262Minimal creates the driver, waits for the chip to boot (HW_VERSION 0x40),
// soft-resets it and writes the Minimal defaults. conn is bound to address 0x49.
func NewAS7262Minimal(conn connection.RegisterConnection) (*AS7262Minimal, error) {
	d := &AS7262Minimal{conn: conn}
	if err := d.waitBoot(); err != nil {
		return nil, err
	}
	if err := d.reset(); err != nil {
		return nil, err
	}
	if err := d.WriteVirtual(as7262RegIntT, as7262IntTDefault); err != nil {
		return nil, err
	}
	if err := d.WriteVirtual(as7262RegLED, 0); err != nil {
		return nil, err
	}
	if err := d.WriteVirtual(as7262RegControl, as7262GainDefault<<4); err != nil {
		return nil, err
	}
	return d, nil
}

func (d *AS7262Minimal) status() (byte, error) {
	b, err := d.conn.ReadReg(as7262HWStatus, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *AS7262Minimal) waitStatus(mask, want byte) error {
	deadline := time.Now().Add(2 * time.Second)
	for {
		s, err := d.status()
		if err != nil {
			return err
		}
		if s&mask == want {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrAS7262Timeout
		}
	}
}

// ReadVirtual reads one virtual register through the STATUS/WRITE/READ handshake.
func (d *AS7262Minimal) ReadVirtual(reg byte) (byte, error) {
	if err := d.waitStatus(as7262TxValid, 0); err != nil {
		return 0, err
	}
	s, err := d.status()
	if err != nil {
		return 0, err
	}
	if s&as7262RxValid != 0 {
		if _, err := d.conn.ReadReg(as7262HWRead, 1); err != nil { // drain a stale byte
			return 0, err
		}
	}
	if err := d.conn.WriteReg(as7262HWWrite, []byte{reg & 0x7F}); err != nil {
		return 0, err
	}
	if err := d.waitStatus(as7262RxValid, as7262RxValid); err != nil {
		return 0, err
	}
	b, err := d.conn.ReadReg(as7262HWRead, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// WriteVirtual writes one virtual register through the STATUS/WRITE handshake.
func (d *AS7262Minimal) WriteVirtual(reg, value byte) error {
	if err := d.waitStatus(as7262TxValid, 0); err != nil {
		return err
	}
	if err := d.conn.WriteReg(as7262HWWrite, []byte{(reg & 0x7F) | 0x80}); err != nil {
		return err
	}
	if err := d.waitStatus(as7262TxValid, 0); err != nil {
		return err
	}
	return d.conn.WriteReg(as7262HWWrite, []byte{value})
}

func (d *AS7262Minimal) waitBoot() error {
	deadline := time.Now().Add(2 * time.Second)
	for {
		if v, err := d.ReadVirtual(as7262RegHWVersion); err == nil && v == 0x40 {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrAS7262NotFound
		}
		time.Sleep(10 * time.Millisecond)
	}
}

func (d *AS7262Minimal) reset() error {
	if err := d.WriteVirtual(as7262RegControl, as7262CtrlRst); err != nil {
		return err
	}
	deadline := time.Now().Add(time.Second)
	for {
		time.Sleep(10 * time.Millisecond)
		if v, err := d.ReadVirtual(as7262RegControl); err == nil && v&as7262CtrlRst == 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrAS7262Timeout
		}
	}
}

func (d *AS7262Minimal) startOneShot() error {
	ctrl, err := d.ReadVirtual(as7262RegControl)
	if err != nil {
		return err
	}
	return d.WriteVirtual(as7262RegControl, (ctrl&0x70)|(3<<2))
}

func (d *AS7262Minimal) waitData() error {
	intT, err := d.ReadVirtual(as7262RegIntT)
	if err != nil {
		return err
	}
	deadline := time.Now().Add(time.Duration(2*float64(intT)*as7262StepMS)*time.Millisecond + time.Second)
	for {
		v, err := d.ReadVirtual(as7262RegControl)
		if err != nil {
			return err
		}
		if v&as7262CtrlDataRdy != 0 {
			return nil
		}
		if time.Now().After(deadline) {
			return ErrAS7262Timeout
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func (d *AS7262Minimal) readCalibrated() (AS7262Channels[float32], error) {
	var out [6]float32
	for ch := 0; ch < 6; ch++ {
		var b [4]byte
		for i := 0; i < 4; i++ {
			v, err := d.ReadVirtual(byte(as7262RegCal + 4*ch + i))
			if err != nil {
				return AS7262Channels[float32]{}, err
			}
			b[i] = v
		}
		out[ch] = math.Float32frombits(binary.BigEndian.Uint32(b[:]))
	}
	return as7262NewChannels(out), nil
}

// Read runs one one-shot conversion and returns the six calibrated channels.
func (d *AS7262Minimal) Read() (AS7262Channels[float32], error) {
	if err := d.startOneShot(); err != nil {
		return AS7262Channels[float32]{}, err
	}
	if err := d.waitData(); err != nil {
		return AS7262Channels[float32]{}, err
	}
	return d.readCalibrated()
}

// Temperature returns the device temperature in °C.
func (d *AS7262Minimal) Temperature() (float32, error) {
	v, err := d.ReadVirtual(as7262RegTemp)
	return float32(v), err
}

// AS7262Full is the full AS7262 driver — raw counts, gain, integration time, bank modes,
// LEDs, interrupt and version registers. It embeds AS7262Minimal.
type AS7262Full struct {
	AS7262Minimal

	mu          sync.Mutex
	callback    func(status byte)
	unsubscribe func()
	pollStop    chan struct{}
}

// NewAS7262Full creates the full driver; see NewAS7262Minimal.
func NewAS7262Full(conn connection.RegisterConnection) (*AS7262Full, error) {
	m, err := NewAS7262Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &AS7262Full{AS7262Minimal: *m}, nil
}

func (d *AS7262Full) readRaw() (AS7262Channels[uint16], error) {
	var out [6]uint16
	for ch := 0; ch < 6; ch++ {
		hi, err := d.ReadVirtual(byte(as7262RegRaw + 2*ch))
		if err != nil {
			return AS7262Channels[uint16]{}, err
		}
		lo, err := d.ReadVirtual(byte(as7262RegRaw + 2*ch + 1))
		if err != nil {
			return AS7262Channels[uint16]{}, err
		}
		out[ch] = uint16(hi)<<8 | uint16(lo)
	}
	return as7262NewChannels(out), nil
}

// ReadRaw runs one one-shot conversion and returns the raw 16-bit counts.
func (d *AS7262Full) ReadRaw() (AS7262Channels[uint16], error) {
	if err := d.startOneShot(); err != nil {
		return AS7262Channels[uint16]{}, err
	}
	if err := d.waitData(); err != nil {
		return AS7262Channels[uint16]{}, err
	}
	return d.readRaw()
}

// Measure starts a one-shot conversion without waiting for it (BANK = 3).
func (d *AS7262Full) Measure() error { return d.startOneShot() }

// DataReady reports whether a conversion result is waiting (CONTROL_SETUP.DATA_RDY).
func (d *AS7262Full) DataReady() (bool, error) {
	v, err := d.ReadVirtual(as7262RegControl)
	return v&as7262CtrlDataRdy != 0, err
}

// ReadCalibrated reads the last calibrated results without starting a conversion.
func (d *AS7262Full) ReadCalibrated() (AS7262Channels[float32], error) { return d.readCalibrated() }

// ReadRawLast reads the last raw counts without starting a conversion.
func (d *AS7262Full) ReadRawLast() (AS7262Channels[uint16], error) { return d.readRaw() }

// ReadChannel reads one calibrated channel (0-5 = V, B, G, Y, O, R) of the last conversion.
func (d *AS7262Full) ReadChannel(index int) (float32, error) {
	if index < 0 || index > 5 {
		return 0, ErrAS7262Argument
	}
	var b [4]byte
	for i := 0; i < 4; i++ {
		v, err := d.ReadVirtual(byte(as7262RegCal + 4*index + i))
		if err != nil {
			return 0, err
		}
		b[i] = v
	}
	return math.Float32frombits(binary.BigEndian.Uint32(b[:])), nil
}

// ReadChannelRaw reads one raw channel (0-5) of the last conversion.
func (d *AS7262Full) ReadChannelRaw(index int) (uint16, error) {
	if index < 0 || index > 5 {
		return 0, ErrAS7262Argument
	}
	hi, err := d.ReadVirtual(byte(as7262RegRaw + 2*index))
	if err != nil {
		return 0, err
	}
	lo, err := d.ReadVirtual(byte(as7262RegRaw + 2*index + 1))
	return uint16(hi)<<8 | uint16(lo), err
}

func (d *AS7262Full) updateControl(mask, bits byte) error {
	ctrl, err := d.ReadVirtual(as7262RegControl)
	if err != nil {
		return err
	}
	return d.WriteVirtual(as7262RegControl, (ctrl&0x7C&^mask)|bits)
}

// SetGain sets the analog gain: index 0-3, or the gain 1, 3.7, 16 or 64 (1 means 1x).
func (d *AS7262Full) SetGain(gain float32) error {
	index := -1
	for i, g := range as7262Gains {
		if g == gain {
			index = i
		}
	}
	if index < 0 && (gain == 0 || gain == 2 || gain == 3) {
		index = int(gain)
	}
	if index < 0 {
		return ErrAS7262Argument
	}
	return d.updateControl(0x30, byte(index)<<4)
}

// GetGain returns the analog gain multiplier (1, 3.7, 16 or 64).
func (d *AS7262Full) GetGain() (float32, error) {
	v, err := d.ReadVirtual(as7262RegControl)
	return as7262Gains[(v>>4)&3], err
}

// SetIntegrationTime sets the integration time per bank in milliseconds (2.8-714).
func (d *AS7262Full) SetIntegrationTime(ms float64) error {
	n := int(math.Round(ms / as7262StepMS))
	if n < 1 || n > 255 {
		return ErrAS7262Argument
	}
	return d.WriteVirtual(as7262RegIntT, byte(n))
}

// GetIntegrationTime returns the integration time per bank in milliseconds.
func (d *AS7262Full) GetIntegrationTime() (float64, error) {
	v, err := d.ReadVirtual(as7262RegIntT)
	return float64(v) * as7262StepMS, err
}

// SetMode sets the bank mode: 0 (V,B,G,Y), 1 (G,Y,O,R), 2 (all six) continuous, 3 one-shot.
func (d *AS7262Full) SetMode(mode int) error {
	if mode < 0 || mode > 3 {
		return ErrAS7262Argument
	}
	return d.updateControl(0x0C, byte(mode)<<2)
}

// GetMode returns the bank mode (0-3).
func (d *AS7262Full) GetMode() (int, error) {
	v, err := d.ReadVirtual(as7262RegControl)
	return int((v >> 2) & 3), err
}

// SetContinuous switches to continuous conversions (bank mode 0, 1 or 2).
func (d *AS7262Full) SetContinuous(mode int) error {
	if mode < 0 || mode > 2 {
		return ErrAS7262Argument
	}
	return d.SetMode(mode)
}

// SetInterrupt enables or disables the INT pin output (CONTROL_SETUP.INT).
func (d *AS7262Full) SetInterrupt(enabled bool) error {
	var bits byte
	if enabled {
		bits = as7262CtrlInt
	}
	return d.updateControl(as7262CtrlInt, bits)
}

// SetLED controls the driver LED output; currentMA is rounded to 12.5, 25, 50 or 100 mA.
func (d *AS7262Full) SetLED(on bool, currentMA float32) error {
	index, ok := as7262Nearest(as7262DrvCurrents, currentMA)
	if !ok {
		return ErrAS7262Argument
	}
	led, err := d.ReadVirtual(as7262RegLED)
	if err != nil {
		return err
	}
	led = led&0x07 | index<<4
	if on {
		led |= 0x08
	}
	return d.WriteVirtual(as7262RegLED, led)
}

// SetIndicatorLED controls the indicator LED output; currentMA is rounded to 1, 2, 4 or 8 mA.
func (d *AS7262Full) SetIndicatorLED(on bool, currentMA float32) error {
	index, ok := as7262Nearest(as7262IndCurrents, currentMA)
	if !ok {
		return ErrAS7262Argument
	}
	led, err := d.ReadVirtual(as7262RegLED)
	if err != nil {
		return err
	}
	led = led&0x38 | index<<1
	if on {
		led |= 0x01
	}
	return d.WriteVirtual(as7262RegLED, led)
}

// SoftReset resets the chip; all registers return to their defaults.
func (d *AS7262Full) SoftReset() error { return d.reset() }

// HardwareVersion returns (device type, hardware version) — expect (0x40, 0x3E).
func (d *AS7262Full) HardwareVersion() (byte, byte, error) {
	t, err := d.ReadVirtual(as7262RegHWVersion)
	if err != nil {
		return 0, 0, err
	}
	v, err := d.ReadVirtual(as7262RegHWVersion + 1)
	return t, v, err
}

// FirmwareVersion returns the firmware version as (major, minor, sub).
func (d *AS7262Full) FirmwareVersion() (byte, byte, byte, error) {
	r2, err := d.ReadVirtual(as7262RegFWVersion)
	if err != nil {
		return 0, 0, 0, err
	}
	r3, err := d.ReadVirtual(as7262RegFWVersion + 1)
	return r3 >> 4, (r3&0x0F)<<2 | r2>>6, r2 & 0x3F, err
}

// DeviceType returns the device type byte (virtual register 0x00, expect 0x40).
func (d *AS7262Full) DeviceType() (byte, error) { return d.ReadVirtual(as7262RegHWVersion) }

// PollInterrupt reads CONTROL_SETUP (releasing INT) and returns DATA_RDY (0 or 1).
func (d *AS7262Full) PollInterrupt() (byte, error) {
	v, err := d.ReadVirtual(as7262RegControl)
	if v&as7262CtrlDataRdy != 0 {
		return 1, err
	}
	return 0, err
}

// OnInterrupt enables the INT output and calls callback(1) when data is ready: with
// the connection's IntPin wired it fires on the falling edge, otherwise a goroutine
// polls every integration time.
func (d *AS7262Full) OnInterrupt(callback func(status byte)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()
	if err := d.SetInterrupt(true); err != nil {
		return err
	}
	dispatch := func() {
		if status, err := d.PollInterrupt(); err == nil && status != 0 {
			d.mu.Lock()
			cb := d.callback
			d.mu.Unlock()
			if cb != nil {
				cb(status)
			}
		}
	}
	if pin := d.conn.IntPin(); pin != nil {
		unsub := pin.OnEdge(connection.Falling, dispatch)
		d.mu.Lock()
		d.unsubscribe = unsub
		d.mu.Unlock()
		return nil
	}
	ms, err := d.GetIntegrationTime()
	if err != nil {
		return err
	}
	if ms < 10 {
		ms = 10
	}
	stop := make(chan struct{})
	d.mu.Lock()
	d.pollStop = stop
	d.mu.Unlock()
	go func() {
		t := time.NewTicker(time.Duration(ms * float64(time.Millisecond)))
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				dispatch()
			}
		}
	}()
	return nil
}

// OffInterrupt unsubscribes from the INT pin (or stops the polling goroutine) and
// disables the INT output.
func (d *AS7262Full) OffInterrupt() error {
	d.mu.Lock()
	unsub, stop, had := d.unsubscribe, d.pollStop, d.callback != nil
	d.unsubscribe, d.pollStop, d.callback = nil, nil, nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if stop != nil {
		close(stop)
	}
	if had {
		return d.SetInterrupt(false)
	}
	return nil
}
