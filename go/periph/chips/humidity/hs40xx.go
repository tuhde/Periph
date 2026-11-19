package humidity

import (
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HS40xx commands and registers.
const (
	hs40xxCmdTNoHold      = 0xF3
	hs40xxCmdHTHold       = 0xE5
	hs40xxCmdHTNoHold     = 0xF5
	hs40xxCmdReadReg      = 0xA7
	hs40xxCmdWriteReg     = 0xA6
	hs40xxCmdStopPeriodic = 0x30
	hs40xxCmdReadID       = 0xD7
	hs40xxCmdSoftReset    = 0xFE

	hs40xxRegResolution = 0x00
	hs40xxRegPeriodic   = 0x02
	hs40xxRegAlertEn    = 0x03

	hs40xxFullScale = 16383.0
)

// Alert source bits of HS40xxFull (register 0x03 enables and the periodic status byte).
const (
	HS40xxSourceRHLow    byte = 0x01
	HS40xxSourceRHHigh   byte = 0x02
	HS40xxSourceTempLow  byte = 0x04
	HS40xxSourceTempHigh byte = 0x08
)

var hs40xxBits = [4]int{8, 10, 12, 14}

// ErrHS40xxCRC is returned when the CRC does not match after one retry.
var ErrHS40xxCRC = errors.New("hs40xx: CRC mismatch")

// ErrHS40xxArgument is returned for an unsupported resolution or period.
var ErrHS40xxArgument = errors.New("hs40xx: invalid argument")

// ErrHS40xxAlertsDisabled is returned by PollInterrupt when no alert source is enabled.
var ErrHS40xxAlertsDisabled = errors.New("hs40xx: alerts are not enabled")

// HS40xxPeriodic is a periodic-mode result.
type HS40xxPeriodic struct {
	TemperatureC float32
	HumidityPct  float32
	// Status is the HS40xxSource* bitmask of the active alerts (0 when alerts are off).
	Status byte
}

// HS40xxThresholds are the alert thresholds for SetAlertThresholds; nil leaves a
// threshold unchanged. Humidity values are in %RH, temperatures in °C.
type HS40xxThresholds struct {
	RHHigh, RHHighReset, RHLow, RHLowReset         *float32
	TempHigh, TempHighReset, TempLow, TempLowReset *float32
}

func hs40xxCRC8(data []byte) byte {
	crc := byte(0xFF)
	for _, b := range data {
		crc ^= b
		for i := 0; i < 8; i++ {
			if crc&0x80 != 0 {
				crc = crc<<1 ^ 0x1D
			} else {
				crc <<= 1
			}
		}
	}
	return crc
}

func hs40xxCRCOK(frame []byte) bool {
	n := len(frame)
	if n == 3 {
		return hs40xxCRC8([]byte{0, 0, frame[0], frame[1]}) == frame[2]
	}
	return hs40xxCRC8(frame[:4]) == frame[n-1]
}

func hs40xxRawHumidity(d []byte) uint16 { return uint16(d[0]&0x3F)<<8 | uint16(d[1]) }
func hs40xxRawTemperature(d []byte, o int) uint16 {
	return uint16(d[o]&0x3F)<<8 | uint16(d[o+1])
}
func hs40xxHumidity(raw uint16) float32    { return float32(raw) / hs40xxFullScale * 100.0 }
func hs40xxTemperature(raw uint16) float32 { return float32(raw)/hs40xxFullScale*165.0 - 40.0 }

// HS40xxMinimal is the Renesas HS40xx (HS4001, HS4003) humidity and temperature
// sensor driver — minimal interface.
//
// HS4001 and HS4003 share one protocol, so HS4001Minimal and HS4003Minimal are
// aliases (HS4002 and the HS401x variants differ only physically and get no
// separate type). Each Read sends the no-hold humidity + temperature command
// (0xF5), waits 3 ms, reads five bytes (retrying while the sensor NACKs the
// read header, for up to 20 ms), verifies the CRC-8 and converts; a CRC
// mismatch is retried once. The constructor does no bus access.
type HS40xxMinimal struct {
	conn connection.Connection
}

// NewHS40xxMinimal creates the driver. conn must be a configured I²C
// connection bound to 0x54.
func NewHS40xxMinimal(conn connection.Connection) *HS40xxMinimal {
	return &HS40xxMinimal{conn: conn}
}

// readFrame reads n bytes, retrying while the sensor NACKs the read header.
func (d *HS40xxMinimal) readFrame(n int) ([]byte, error) {
	var err error
	for i := 0; i < 21; i++ {
		var b []byte
		if b, err = d.conn.Read(n); err == nil {
			return b, nil
		}
		time.Sleep(time.Millisecond)
	}
	return nil, err
}

// measure sends cmd, waits (no-hold) and reads n bytes with a CRC check; the
// whole sequence is retried once on a CRC mismatch.
func (d *HS40xxMinimal) measure(cmd byte, n int, hold bool) ([]byte, error) {
	for attempt := 0; attempt < 2; attempt++ {
		if err := d.conn.Write([]byte{cmd}); err != nil {
			return nil, err
		}
		if !hold {
			time.Sleep(3 * time.Millisecond)
		}
		frame, err := d.readFrame(n)
		if err != nil {
			return nil, err
		}
		if hs40xxCRCOK(frame) {
			return frame, nil
		}
	}
	return nil, ErrHS40xxCRC
}

// Read measures temperature (°C, -40 to 125) and relative humidity (%RH, 0 to 100).
func (d *HS40xxMinimal) Read() (temperature, humidity float32, err error) {
	f, err := d.measure(hs40xxCmdHTNoHold, 5, false)
	if err != nil {
		return 0, 0, err
	}
	return hs40xxTemperature(hs40xxRawTemperature(f, 2)), hs40xxHumidity(hs40xxRawHumidity(f)), nil
}

// HS40xxFull is the HS40xx driver with the complete feature set: single
// values, hold mode, resolution, periodic mode, alerts and ID.
//
// Registers are volatile: they return to their defaults whenever the supply
// is cut or after SoftReset.
type HS40xxFull struct {
	HS40xxMinimal
	mu            sync.Mutex
	alertsEnabled bool
	activeHigh    bool
	periodS       float64
	callback      func(status byte)
	unsubscribe   func()
	pollStop      chan struct{}
}

// NewHS40xxFull creates the full driver; same argument as NewHS40xxMinimal.
func NewHS40xxFull(conn connection.Connection) *HS40xxFull {
	return &HS40xxFull{HS40xxMinimal: *NewHS40xxMinimal(conn), activeHigh: true, periodS: 1}
}

// HS4001Minimal and HS4003Minimal are the same driver as HS40xxMinimal.
type (
	HS4001Minimal = HS40xxMinimal
	HS4003Minimal = HS40xxMinimal
	// HS4001Full and HS4003Full are the same driver as HS40xxFull.
	HS4001Full = HS40xxFull
	HS4003Full = HS40xxFull
)

// NewHS4001Minimal and NewHS4003Minimal alias NewHS40xxMinimal.
var (
	NewHS4001Minimal = NewHS40xxMinimal
	NewHS4003Minimal = NewHS40xxMinimal
	NewHS4001Full    = NewHS40xxFull
	NewHS4003Full    = NewHS40xxFull
)

// ReadTemperature measures temperature only (no-hold 0xF3, three bytes) in °C.
func (d *HS40xxFull) ReadTemperature() (float32, error) {
	f, err := d.measure(hs40xxCmdTNoHold, 3, false)
	if err != nil {
		return 0, err
	}
	return hs40xxTemperature(hs40xxRawTemperature(f, 0)), nil
}

// ReadHumidity measures humidity (uses the 0xF5 command) in %RH.
func (d *HS40xxFull) ReadHumidity() (float32, error) {
	_, rh, err := d.Read()
	return rh, err
}

// ReadHold measures with the hold command 0xE5 (the sensor stretches SCL until
// done; needs a stretching-capable controller and SCL >= 200 kHz).
func (d *HS40xxFull) ReadHold() (temperature, humidity float32, err error) {
	f, err := d.measure(hs40xxCmdHTHold, 5, true)
	if err != nil {
		return 0, 0, err
	}
	return hs40xxTemperature(hs40xxRawTemperature(f, 2)), hs40xxHumidity(hs40xxRawHumidity(f)), nil
}

// ReadRaw measures and returns the raw 14-bit humidity and temperature values (CRC verified).
func (d *HS40xxFull) ReadRaw() (humidity, temperature uint16, err error) {
	f, err := d.measure(hs40xxCmdHTNoHold, 5, false)
	if err != nil {
		return 0, 0, err
	}
	return hs40xxRawHumidity(f), hs40xxRawTemperature(f, 2), nil
}

// ReadRegister reads one configuration register (0xA7 + address, then a one-byte read).
func (d *HS40xxFull) ReadRegister(reg byte) (byte, error) {
	if err := d.conn.Write([]byte{hs40xxCmdReadReg, reg}); err != nil {
		return 0, err
	}
	b, err := d.readFrame(1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// WriteRegister writes one configuration register (0xA6 + address + data).
func (d *HS40xxFull) WriteRegister(reg, value byte) error {
	return d.conn.Write([]byte{hs40xxCmdWriteReg, reg, value})
}

func (d *HS40xxFull) modify(reg, mask, bits byte) error {
	v, err := d.ReadRegister(reg)
	if err != nil {
		return err
	}
	return d.WriteRegister(reg, v&^mask|bits&mask)
}

func hs40xxCode(bits int) (byte, bool) {
	for i, b := range hs40xxBits {
		if b == bits {
			return byte(i), true
		}
	}
	return 0, false
}

// SetResolution sets the measurement resolutions (register 0x00,
// read-modify-write). A value of 0 leaves a channel unchanged; others must be
// 8, 10, 12 or 14 (ErrHS40xxArgument, before any bus access).
func (d *HS40xxFull) SetResolution(humidityBits, temperatureBits int) error {
	var mask, value byte
	if humidityBits != 0 {
		c, ok := hs40xxCode(humidityBits)
		if !ok {
			return ErrHS40xxArgument
		}
		mask |= 0x0C
		value |= c << 2
	}
	if temperatureBits != 0 {
		c, ok := hs40xxCode(temperatureBits)
		if !ok {
			return ErrHS40xxArgument
		}
		mask |= 0x03
		value |= c
	}
	if mask == 0 {
		return nil
	}
	return d.modify(hs40xxRegResolution, mask, value)
}

// GetResolution reads the measurement resolutions (each 8, 10, 12 or 14).
func (d *HS40xxFull) GetResolution() (humidityBits, temperatureBits int, err error) {
	v, err := d.ReadRegister(hs40xxRegResolution)
	if err != nil {
		return 0, 0, err
	}
	return hs40xxBits[(v>>2)&3], hs40xxBits[v&3], nil
}

// StartPeriodic starts periodic measurement mode with a period of 0.5, 1 or 2.5
// seconds (ErrHS40xxArgument otherwise).
func (d *HS40xxFull) StartPeriodic(periodS float64) error {
	var code byte
	switch periodS {
	case 0.5:
		code = 0
	case 1:
		code = 1
	case 2.5:
		code = 2
	default:
		return ErrHS40xxArgument
	}
	en, err := d.ReadRegister(hs40xxRegAlertEn)
	if err != nil {
		return err
	}
	d.mu.Lock()
	d.alertsEnabled = en&0x0F != 0
	d.periodS = periodS
	d.mu.Unlock()
	return d.modify(hs40xxRegPeriodic, 0xB0, 0x80|code<<4)
}

// StopPeriodic leaves periodic mode (command 0x30); the sensor then sleeps.
func (d *HS40xxFull) StopPeriodic() error {
	return d.conn.Write([]byte{hs40xxCmdStopPeriodic})
}

// ReadPeriodic fetches the latest periodic result (a plain read, CRC verified).
// The frame has a leading status byte when any alert source is enabled.
func (d *HS40xxFull) ReadPeriodic() (HS40xxPeriodic, error) {
	d.mu.Lock()
	alerts := d.alertsEnabled
	d.mu.Unlock()
	n := 5
	if alerts {
		n = 6
	}
	b, err := d.readFrame(n)
	if err != nil {
		return HS40xxPeriodic{}, err
	}
	var status byte
	if alerts {
		status = b[0] & 0x0F
		b = b[1:]
	}
	if !hs40xxCRCOK(b) {
		return HS40xxPeriodic{}, ErrHS40xxCRC
	}
	return HS40xxPeriodic{
		TemperatureC: hs40xxTemperature(hs40xxRawTemperature(b, 2)),
		HumidityPct:  hs40xxHumidity(hs40xxRawHumidity(b)),
		Status:       status,
	}, nil
}

func hs40xxWord(v float64, isRH bool) uint16 {
	var raw float64
	if isRH {
		raw = v / 100.0 * hs40xxFullScale
	} else {
		raw = (v + 40.0) / 165.0 * hs40xxFullScale
	}
	return uint16(math.Max(0, math.Min(16383, math.Round(raw))))
}

// SetAlertThresholds programs the alert trigger / reset thresholds; only the
// non-nil ones are written.
func (d *HS40xxFull) SetAlertThresholds(th HS40xxThresholds) error {
	list := []struct {
		v    *float32
		reg  byte
		isRH bool
	}{
		{th.RHHigh, 0x0F, true}, {th.RHHighReset, 0x0D, true}, {th.RHLow, 0x11, true}, {th.RHLowReset, 0x13, true},
		{th.TempHigh, 0x07, false}, {th.TempHighReset, 0x05, false}, {th.TempLow, 0x09, false}, {th.TempLowReset, 0x0B, false},
	}
	for _, t := range list {
		if t.v == nil {
			continue
		}
		w := hs40xxWord(float64(*t.v), t.isRH)
		if err := d.WriteRegister(t.reg, byte(w)); err != nil {
			return err
		}
		if err := d.WriteRegister(t.reg+1, byte(w>>8)); err != nil {
			return err
		}
	}
	return nil
}

// SetAlertPolarity sets the ALERT pin polarity (register 0x02 bit 0); true is active high (default).
func (d *HS40xxFull) SetAlertPolarity(activeHigh bool) error {
	d.mu.Lock()
	d.activeHigh = activeHigh
	d.mu.Unlock()
	var bit byte
	if activeHigh {
		bit = 1
	}
	return d.modify(hs40xxRegPeriodic, 0x01, bit)
}

// EnableInterrupt enables alert source(s) (HS40xxSource* bitmask, register 0x03).
func (d *HS40xxFull) EnableInterrupt(source byte) error {
	v, err := d.ReadRegister(hs40xxRegAlertEn)
	if err != nil {
		return err
	}
	v |= source & 0x0F
	if err := d.WriteRegister(hs40xxRegAlertEn, v); err != nil {
		return err
	}
	d.mu.Lock()
	d.alertsEnabled = v&0x0F != 0
	d.mu.Unlock()
	return nil
}

// DisableInterrupt disables alert source(s) (HS40xxSource* bitmask, register 0x03).
func (d *HS40xxFull) DisableInterrupt(source byte) error {
	v, err := d.ReadRegister(hs40xxRegAlertEn)
	if err != nil {
		return err
	}
	v &^= source & 0x0F
	if err := d.WriteRegister(hs40xxRegAlertEn, v); err != nil {
		return err
	}
	d.mu.Lock()
	d.alertsEnabled = v&0x0F != 0
	d.mu.Unlock()
	return nil
}

// PollInterrupt fetches the latest periodic result and returns the active-alert
// HS40xxSource* bitmask. It needs periodic mode with an alert source enabled
// (ErrHS40xxAlertsDisabled otherwise).
func (d *HS40xxFull) PollInterrupt() (byte, error) {
	d.mu.Lock()
	alerts := d.alertsEnabled
	d.mu.Unlock()
	if !alerts {
		return 0, ErrHS40xxAlertsDisabled
	}
	p, err := d.ReadPeriodic()
	return p.Status, err
}

// OnInterrupt subscribes callback to the ALERT pin: with the connection's IntPin
// wired it fires on the active edge, otherwise a goroutine polls at the
// periodic interval. callback receives the status bitmask.
func (d *HS40xxFull) OnInterrupt(callback func(status byte)) error {
	_ = d.OffInterrupt()
	d.mu.Lock()
	d.callback = callback
	activeHigh := d.activeHigh
	period := time.Duration(d.periodS * float64(time.Second))
	d.mu.Unlock()
	dispatch := func() {
		if status, err := d.PollInterrupt(); err == nil {
			d.mu.Lock()
			cb := d.callback
			d.mu.Unlock()
			if cb != nil {
				cb(status)
			}
		}
	}
	if pin := d.conn.IntPin(); pin != nil {
		trig := connection.Falling
		if activeHigh {
			trig = connection.Rising
		}
		unsub := pin.OnEdge(trig, dispatch)
		d.mu.Lock()
		d.unsubscribe = unsub
		d.mu.Unlock()
		return nil
	}
	stop := make(chan struct{})
	d.mu.Lock()
	d.pollStop = stop
	d.mu.Unlock()
	go func() {
		t := time.NewTicker(period)
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

// OffInterrupt unsubscribes from the ALERT pin (or stops the polling goroutine).
func (d *HS40xxFull) OffInterrupt() error {
	d.mu.Lock()
	unsub, stop := d.unsubscribe, d.pollStop
	d.unsubscribe, d.pollStop, d.callback = nil, nil, nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if stop != nil {
		close(stop)
	}
	return nil
}

// ReadID reads the 32-bit sensor ID (command 0xD7, four bytes MSB first).
func (d *HS40xxFull) ReadID() (uint32, error) {
	if err := d.conn.Write([]byte{hs40xxCmdReadID}); err != nil {
		return 0, err
	}
	b, err := d.readFrame(4)
	if err != nil {
		return 0, err
	}
	return uint32(b[0])<<24 | uint32(b[1])<<16 | uint32(b[2])<<8 | uint32(b[3]), nil
}

// SoftReset stops periodic mode, sends the soft reset (0xFE) and waits 500 ms.
func (d *HS40xxFull) SoftReset() error {
	if err := d.StopPeriodic(); err != nil {
		return err
	}
	if err := d.conn.Write([]byte{hs40xxCmdSoftReset}); err != nil {
		return err
	}
	time.Sleep(500 * time.Millisecond)
	d.mu.Lock()
	d.alertsEnabled = false
	d.activeHigh = true
	d.mu.Unlock()
	return nil
}

// DewPoint measures and returns the dew point in °C (Magnus formula); -273.15 at 0 %RH.
func (d *HS40xxFull) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	if rh <= 0 {
		return -273.15, nil
	}
	gamma := math.Log(float64(rh)/100.0) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * gamma / (17.62 - gamma)), nil
}
