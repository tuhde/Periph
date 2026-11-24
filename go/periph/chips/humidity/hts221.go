package humidity

import (
	"errors"
	"math"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// HTS221 register addresses and bit masks.
const (
	hts221RegWhoAmI      = 0x0F
	hts221RegAvConf      = 0x10
	hts221RegCtrlReg1    = 0x20
	hts221RegCtrlReg2    = 0x21
	hts221RegCtrlReg3    = 0x22
	hts221RegStatus      = 0x27
	hts221RegHumidityOut = 0x28
	hts221RegCalib       = 0x30

	hts221AutoInc         = 0x80
	hts221CtrlReg1Default = 0x84 // PD=1, BDU=1, ODR=00 (one-shot)
	hts221Ctrl1PD         = 0x80
	hts221Ctrl1BDU        = 0x04
	hts221Ctrl1ODRMask    = 0x03
	hts221Ctrl2Boot       = 0x80
	hts221Ctrl2Heater     = 0x02
	hts221Ctrl2OneShot    = 0x01
	hts221StatusBoth      = 0x03
	hts221TimeoutMs       = 100
)

// HTS221 STATUS_REG flags.
const (
	// HTS221StatusHDA is the humidity-data-available flag.
	HTS221StatusHDA uint8 = 0x02
	// HTS221StatusTDA is the temperature-data-available flag.
	HTS221StatusTDA uint8 = 0x01
)

// ErrHTS221Timeout is returned when a conversion or reboot does not finish within 100 ms.
var ErrHTS221Timeout = errors.New("hts221: operation did not complete within 100 ms")

// ErrHTS221InvalidArgument is returned for an unsupported output data rate or averaging value.
var ErrHTS221InvalidArgument = errors.New("hts221: invalid argument")

// HTS221Calibration holds the decoded factory calibration coefficients.
type HTS221Calibration struct {
	// H0rH and H1rH are the humidity calibration points in %rH.
	H0rH, H1rH float32
	// T0degC and T1degC are the temperature calibration points in °C.
	T0degC, T1degC float32
	// H0T0Out and H1T0Out are the humidity ADC outputs at the two points.
	H0T0Out, H1T0Out int16
	// T0Out and T1Out are the temperature ADC outputs at the two points.
	T0Out, T1Out int16
}

func (c HTS221Calibration) humidity(raw int16) float32 {
	span := float32(c.H1T0Out) - float32(c.H0T0Out)
	if span == 0 {
		return 0
	}
	v := (float32(raw)-float32(c.H0T0Out))*(c.H1rH-c.H0rH)/span + c.H0rH
	if v < 0 {
		return 0
	}
	if v > 100 {
		return 100
	}
	return v
}

func (c HTS221Calibration) temperature(raw int16) float32 {
	span := float32(c.T1Out) - float32(c.T0Out)
	if span == 0 {
		return 0
	}
	return (float32(raw)-float32(c.T0Out))*(c.T1degC-c.T0degC)/span + c.T0degC
}

// HTS221Minimal is the HTS221 humidity and temperature sensor driver
// (STMicroelectronics) — minimal interface.
//
// Init reads the factory calibration (registers 0x30–0x3F) and writes
// CTRL_REG1 = 0x84 (active, block data update, one-shot). Read starts a one-shot
// conversion, waits for it (timeout 100 ms) and converts the outputs by linear
// interpolation. The I²C address is fixed at 0x5F. The constructor does no bus
// access.
type HTS221Minimal struct {
	conn connection.RegisterConnection
	cal  HTS221Calibration
}

// NewHTS221Minimal creates the driver. conn must be a configured register
// connection bound to 0x5F.
func NewHTS221Minimal(conn connection.RegisterConnection) *HTS221Minimal {
	return &HTS221Minimal{conn: conn}
}

// Init reads the calibration block and starts one-shot mode (CTRL_REG1 = 0x84).
func (d *HTS221Minimal) Init() error {
	if err := d.readCalibration(); err != nil {
		return err
	}
	return d.writeReg(hts221RegCtrlReg1, hts221CtrlReg1Default)
}

func (d *HTS221Minimal) writeReg(reg, value byte) error {
	return d.conn.WriteReg(uint32(reg), []byte{value})
}

func (d *HTS221Minimal) readReg(reg byte) (byte, error) {
	b, err := d.conn.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// readBurst reads length bytes with register auto-increment (sub-address MSb set).
func (d *HTS221Minimal) readBurst(reg byte, length int) ([]byte, error) {
	return d.conn.ReadReg(uint32(reg|hts221AutoInc), length)
}

func (d *HTS221Minimal) readCalibration() error {
	c, err := d.readBurst(hts221RegCalib, 16)
	if err != nil {
		return err
	}
	t0 := (uint16(c[5]&0x03) << 8) | uint16(c[2])
	t1 := (uint16(c[5]&0x0C) << 6) | uint16(c[3])
	d.cal = HTS221Calibration{
		H0rH:    float32(c[0]) / 2,
		H1rH:    float32(c[1]) / 2,
		T0degC:  float32(t0) / 8,
		T1degC:  float32(t1) / 8,
		H0T0Out: int16(uint16(c[7])<<8 | uint16(c[6])),
		H1T0Out: int16(uint16(c[11])<<8 | uint16(c[10])),
		T0Out:   int16(uint16(c[13])<<8 | uint16(c[12])),
		T1Out:   int16(uint16(c[15])<<8 | uint16(c[14])),
	}
	return nil
}

func (d *HTS221Minimal) readRawPair() (h, t int16, err error) {
	b, err := d.readBurst(hts221RegHumidityOut, 4)
	if err != nil {
		return 0, 0, err
	}
	return int16(uint16(b[1])<<8 | uint16(b[0])), int16(uint16(b[3])<<8 | uint16(b[2])), nil
}

func (d *HTS221Minimal) oneShotRaw() (h, t int16, err error) {
	ctrl2, err := d.readReg(hts221RegCtrlReg2)
	if err != nil {
		return 0, 0, err
	}
	if err := d.writeReg(hts221RegCtrlReg2, ctrl2|hts221Ctrl2OneShot); err != nil {
		return 0, 0, err
	}
	for i := 0; i < hts221TimeoutMs; i++ {
		status, err := d.readReg(hts221RegStatus)
		if err != nil {
			return 0, 0, err
		}
		if status&hts221StatusBoth == hts221StatusBoth {
			return d.readRawPair()
		}
		time.Sleep(time.Millisecond)
	}
	return 0, 0, ErrHTS221Timeout
}

// Read measures temperature and relative humidity (one-shot).
//
// It returns the temperature in °C and the humidity in %rH (clamped to 0–100);
// ErrHTS221Timeout if the conversion does not finish within 100 ms.
func (d *HTS221Minimal) Read() (temperatureC, humidityPct float32, err error) {
	h, t, err := d.oneShotRaw()
	if err != nil {
		return 0, 0, err
	}
	return d.cal.temperature(t), d.cal.humidity(h), nil
}

// HTS221Full is the full HTS221 driver — single values, raw outputs, output
// data rate, averaging, power modes, heater, DRDY pin, calibration access, dew
// point and data-ready interrupt support. It embeds HTS221Minimal.
type HTS221Full struct {
	*HTS221Minimal

	mu          sync.Mutex
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
}

// NewHTS221Full creates the full driver; same argument as NewHTS221Minimal.
func NewHTS221Full(conn connection.RegisterConnection) *HTS221Full {
	return &HTS221Full{HTS221Minimal: NewHTS221Minimal(conn)}
}

// ReadHumidity measures the relative humidity only (one-shot) in %rH, clamped to 0–100.
func (d *HTS221Full) ReadHumidity() (float32, error) {
	h, _, err := d.oneShotRaw()
	if err != nil {
		return 0, err
	}
	return d.cal.humidity(h), nil
}

// ReadTemperature measures the temperature only (one-shot) in °C.
func (d *HTS221Full) ReadTemperature() (float32, error) {
	_, t, err := d.oneShotRaw()
	if err != nil {
		return 0, err
	}
	return d.cal.temperature(t), nil
}

// ReadRawHumidity measures and returns the signed 16-bit raw humidity output (one-shot).
func (d *HTS221Full) ReadRawHumidity() (int16, error) {
	h, _, err := d.oneShotRaw()
	return h, err
}

// ReadRawTemperature measures and returns the signed 16-bit raw temperature output (one-shot).
func (d *HTS221Full) ReadRawTemperature() (int16, error) {
	_, t, err := d.oneShotRaw()
	return t, err
}

// ReadContinuous reads the latest sample without starting a conversion
// (continuous output data rate); it returns °C and %rH.
func (d *HTS221Full) ReadContinuous() (temperatureC, humidityPct float32, err error) {
	h, t, err := d.readRawPair()
	if err != nil {
		return 0, 0, err
	}
	return d.cal.temperature(t), d.cal.humidity(h), nil
}

// DewPoint measures once and returns the dew point in °C (Magnus formula).
func (d *HTS221Full) DewPoint() (float32, error) {
	t, rh, err := d.Read()
	if err != nil {
		return 0, err
	}
	if rh < 0.01 {
		rh = 0.01
	}
	g := math.Log(float64(rh)/100) + 17.62*float64(t)/(243.12+float64(t))
	return float32(243.12 * g / (17.62 - g)), nil
}

// SetOutputDataRate sets the output data rate: 0 (one-shot), 1, 7 or 12.5 Hz.
// Any other value returns ErrHTS221InvalidArgument.
func (d *HTS221Full) SetOutputDataRate(hz float32) error {
	var code byte
	switch hz {
	case 0:
		code = 0
	case 1:
		code = 1
	case 7:
		code = 2
	case 12.5:
		code = 3
	default:
		return ErrHTS221InvalidArgument
	}
	ctrl1, err := d.readReg(hts221RegCtrlReg1)
	if err != nil {
		return err
	}
	if err := d.writeReg(hts221RegCtrlReg1, (ctrl1&^hts221Ctrl1ODRMask)|code); err != nil {
		return err
	}
	// Clear flags left over from continuous sampling so the next one-shot wait does not return early.
	_, _, err = d.readRawPair()
	return err
}

// GetOutputDataRate returns the output data rate in Hz (0, 1, 7 or 12.5).
func (d *HTS221Full) GetOutputDataRate() (float32, error) {
	ctrl1, err := d.readReg(hts221RegCtrlReg1)
	if err != nil {
		return 0, err
	}
	return [4]float32{0, 1, 7, 12.5}[ctrl1&hts221Ctrl1ODRMask], nil
}

func hts221Log2Index(value, min, max int) (byte, bool) {
	if value < min || value > max || value&(value-1) != 0 {
		return 0, false
	}
	idx := byte(0)
	for v := min; v < value; v <<= 1 {
		idx++
	}
	return idx, true
}

// SetAveraging sets the internal averaging: temperature 2…256 and humidity
// 4…512 samples (powers of two). Any other value returns ErrHTS221InvalidArgument.
func (d *HTS221Full) SetAveraging(temperatureSamples, humiditySamples int) error {
	t, ok := hts221Log2Index(temperatureSamples, 2, 256)
	if !ok {
		return ErrHTS221InvalidArgument
	}
	h, ok := hts221Log2Index(humiditySamples, 4, 512)
	if !ok {
		return ErrHTS221InvalidArgument
	}
	return d.writeReg(hts221RegAvConf, t<<3|h)
}

// GetAveraging returns the internal averaging as (temperature samples, humidity samples).
func (d *HTS221Full) GetAveraging() (temperatureSamples, humiditySamples int, err error) {
	v, err := d.readReg(hts221RegAvConf)
	if err != nil {
		return 0, 0, err
	}
	return 2 << ((v >> 3) & 0x07), 4 << (v & 0x07), nil
}

// SetBlockDataUpdate enables or disables block data update (CTRL_REG1.BDU).
func (d *HTS221Full) SetBlockDataUpdate(enabled bool) error {
	ctrl1, err := d.readReg(hts221RegCtrlReg1)
	if err != nil {
		return err
	}
	if enabled {
		ctrl1 |= hts221Ctrl1BDU
	} else {
		ctrl1 &^= hts221Ctrl1BDU
	}
	return d.writeReg(hts221RegCtrlReg1, ctrl1)
}

// PowerDown enters power-down mode (CTRL_REG1.PD = 0).
func (d *HTS221Full) PowerDown() error {
	ctrl1, err := d.readReg(hts221RegCtrlReg1)
	if err != nil {
		return err
	}
	return d.writeReg(hts221RegCtrlReg1, ctrl1&^hts221Ctrl1PD)
}

// PowerUp leaves power-down mode (CTRL_REG1.PD = 1).
func (d *HTS221Full) PowerUp() error {
	ctrl1, err := d.readReg(hts221RegCtrlReg1)
	if err != nil {
		return err
	}
	return d.writeReg(hts221RegCtrlReg1, ctrl1|hts221Ctrl1PD)
}

// SetHeater switches the internal heater on or off. Do not measure while it is on.
func (d *HTS221Full) SetHeater(on bool) error {
	ctrl2, err := d.readReg(hts221RegCtrlReg2)
	if err != nil {
		return err
	}
	if on {
		ctrl2 |= hts221Ctrl2Heater
	} else {
		ctrl2 &^= hts221Ctrl2Heater
	}
	return d.writeReg(hts221RegCtrlReg2, ctrl2)
}

// IsHeaterOn reports whether the heater is on.
func (d *HTS221Full) IsHeaterOn() (bool, error) {
	ctrl2, err := d.readReg(hts221RegCtrlReg2)
	if err != nil {
		return false, err
	}
	return ctrl2&hts221Ctrl2Heater != 0, nil
}

// Reboot reloads the trimming registers (CTRL_REG2.BOOT), waits for it to
// clear (timeout 100 ms) and re-reads the calibration.
func (d *HTS221Full) Reboot() error {
	ctrl2, err := d.readReg(hts221RegCtrlReg2)
	if err != nil {
		return err
	}
	if err := d.writeReg(hts221RegCtrlReg2, ctrl2|hts221Ctrl2Boot); err != nil {
		return err
	}
	for i := 0; i < hts221TimeoutMs; i++ {
		v, err := d.readReg(hts221RegCtrlReg2)
		if err != nil {
			return err
		}
		if v&hts221Ctrl2Boot == 0 {
			return d.readCalibration()
		}
		time.Sleep(time.Millisecond)
	}
	return ErrHTS221Timeout
}

// WhoAmI reads WHO_AM_I (0xBC for the HTS221).
func (d *HTS221Full) WhoAmI() (uint8, error) { return d.readReg(hts221RegWhoAmI) }

// Status reads STATUS_REG (HTS221StatusHDA / HTS221StatusTDA).
func (d *HTS221Full) Status() (uint8, error) { return d.readReg(hts221RegStatus) }

// DataReady reports whether both humidity and temperature data are available.
func (d *HTS221Full) DataReady() (bool, error) {
	s, err := d.Status()
	if err != nil {
		return false, err
	}
	return s&hts221StatusBoth == hts221StatusBoth, nil
}

// ReadCalibration returns the decoded calibration coefficients read by Init / Reboot.
func (d *HTS221Full) ReadCalibration() HTS221Calibration { return d.cal }

// ConfigureDRDY configures the DRDY output pin (CTRL_REG3): enabled routes
// data-ready to the pin, activeHigh=false selects active-low, openDrain=true
// selects an open-drain output (default push-pull).
func (d *HTS221Full) ConfigureDRDY(enabled, activeHigh, openDrain bool) error {
	var v byte
	if !activeHigh {
		v |= 0x80
	}
	if openDrain {
		v |= 0x40
	}
	if enabled {
		v |= 0x04
	}
	return d.writeReg(hts221RegCtrlReg3, v)
}

// OnInterrupt subscribes callback to data-ready events.
//
// It enables DRDY (active high, push-pull) and uses the connection's IntPin
// (rising edge); without a pin a polling goroutine calls PollInterrupt every 5 ms
// and delivers only when new data is available. The callback receives STATUS_REG.
func (d *HTS221Full) OnInterrupt(callback func(uint8)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	if err := d.ConfigureDRDY(true, true, false); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	pin := d.conn.IntPin()
	polling := pin == nil
	if polling {
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	}
	unsub := pin.OnEdge(connection.Rising, func() { d.handleEdge(polling) })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes from data-ready events and disables the DRDY output.
func (d *HTS221Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	d.unsubscribe = nil
	had := d.callback != nil
	d.callback = nil
	poll := d.pollPin
	d.pollPin = nil
	d.mu.Unlock()

	if unsub != nil {
		unsub()
	}
	if poll != nil {
		_ = poll.Close()
	}
	if had {
		return d.ConfigureDRDY(false, true, false)
	}
	return nil
}

// PollInterrupt reads STATUS_REG; the flags clear when the output registers are read.
func (d *HTS221Full) PollInterrupt() (uint8, error) { return d.Status() }

func (d *HTS221Full) handleEdge(polling bool) {
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb == nil {
		return
	}
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	if polling && status&hts221StatusBoth == 0 {
		return
	}
	cb(status)
}
