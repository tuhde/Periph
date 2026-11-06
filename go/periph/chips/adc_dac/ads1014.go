package adcdac

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ADS1014 register pointers.
const (
	ads1014RegConversion uint8 = 0x00
	ads1014RegConfig     uint8 = 0x01
	ads1014RegLoThresh   uint8 = 0x02
	ads1014RegHiThresh   uint8 = 0x03
)

// ADS1014 Config / threshold reset values and bit masks.
const (
	ads1014ConfigReset uint16 = 0x8583
	ads1014LoReset     uint16 = 0x8000
	ads1014HiReset     uint16 = 0x7FFF

	ads1014CfgOS         uint16 = 0x8000
	ads1014CfgModeSingle uint16 = 0x0100
	ads1014QueueDisable  uint8  = 3
)

// ADS1014I2CAddress is the default 7-bit I²C address (ADDR = GND). The ADDR
// strap selects any address in 0x48-0x4B.
const ADS1014I2CAddress uint8 = 0x48

// ADS1014 programmable-gain amplifier settings (PGA[2:0]).
const (
	ADS1014PGA6_144 uint8 = 0 // ±6.144 V
	ADS1014PGA4_096 uint8 = 1 // ±4.096 V
	ADS1014PGA2_048 uint8 = 2 // ±2.048 V (default)
	ADS1014PGA1_024 uint8 = 3 // ±1.024 V
	ADS1014PGA0_512 uint8 = 4 // ±0.512 V
	ADS1014PGA0_256 uint8 = 5 // ±0.256 V
)

// ADS1014 data-rate codes (DR[2:0]).
const (
	ADS1014DR128  uint8 = 0
	ADS1014DR250  uint8 = 1
	ADS1014DR490  uint8 = 2
	ADS1014DR920  uint8 = 3
	ADS1014DR1600 uint8 = 4 // default
	ADS1014DR2400 uint8 = 5
	ADS1014DR3300 uint8 = 6
)

// ADS1014 comparator mode, polarity and queue settings.
const (
	ADS1014CompTraditional uint8 = 0
	ADS1014CompWindow      uint8 = 1
	ADS1014ActiveLow       uint8 = 0
	ADS1014ActiveHigh      uint8 = 1
	ADS1014Queue1          uint8 = 0 // assert after 1 out-of-range conversion
	ADS1014Queue2          uint8 = 1 // after 2
	ADS1014Queue4          uint8 = 2 // after 4
)

// ADS1014 interrupt sources — only one can drive ALERT/RDY at a time.
const (
	ADS1014SourceConversionReady uint8 = 0x01
	ADS1014SourceComparator      uint8 = 0x02
)

// ErrADS1014InvalidArgument is returned for an argument outside its
// documented range, or for comparator thresholds with high ≤ low. No bus
// transaction is made.
var ErrADS1014InvalidArgument = errors.New("ADS1014: invalid argument")

var (
	ads1014FullScale = [6]float32{6.144, 4.096, 2.048, 1.024, 0.512, 0.256}
	ads1014SPS       = [7]uint16{128, 250, 490, 920, 1600, 2400, 3300}
)

// ADS1014Minimal is the minimal ADS1014 interface: single-shot reads of
// AIN0 − AIN1.
//
// ADS1014 12-bit I²C ΔΣ ADC with PGA and programmable comparator (Texas
// Instruments). Registers are 16-bit, big-endian, addressed through a
// persistent Address Pointer. Defaults: ±2.048 V range (1 mV/LSB), 1600 SPS,
// single-shot, comparator disabled. The whole Config word is written for
// every conversion.
type ADS1014Minimal struct {
	conn       connection.RegisterConnection
	pga        uint8
	dr         uint8
	continuous bool
	compMode   uint8
	compPol    uint8
	compLat    bool
	queue      uint8
	source     uint8
}

// NewADS1014Minimal creates an ADS1014Minimal. The chip has no ID register,
// so no bus transaction is made.
func NewADS1014Minimal(conn connection.RegisterConnection) (*ADS1014Minimal, error) {
	return &ADS1014Minimal{conn: conn, pga: ADS1014PGA2_048, dr: ADS1014DR1600}, nil
}

func (d *ADS1014Minimal) readReg(reg uint8) (uint16, error) {
	b, err := d.conn.ReadReg(uint32(reg), 2)
	if err != nil {
		return 0, err
	}
	return uint16(b[0])<<8 | uint16(b[1]), nil
}

func (d *ADS1014Minimal) writeReg(reg uint8, value uint16) error {
	return d.conn.WriteReg(uint32(reg), []byte{byte(value >> 8), byte(value)})
}

func (d *ADS1014Minimal) compQue() uint8 {
	switch d.source {
	case ADS1014SourceConversionReady:
		return 0
	case ADS1014SourceComparator:
		return d.queue
	}
	return ads1014QueueDisable
}

func (d *ADS1014Minimal) configWord(os, continuous bool) uint16 {
	var word uint16
	if os {
		word |= ads1014CfgOS
	}
	word |= uint16(d.pga) << 9
	if !continuous {
		word |= ads1014CfgModeSingle
	}
	word |= uint16(d.dr) << 5
	word |= uint16(d.compMode) << 4
	word |= uint16(d.compPol) << 3
	if d.compLat {
		word |= 1 << 2
	}
	return word | uint16(d.compQue())
}

func (d *ADS1014Minimal) readConversion() (int16, error) {
	raw, err := d.readReg(ads1014RegConversion)
	if err != nil {
		return 0, err
	}
	return int16(raw) >> 4, nil
}

func (d *ADS1014Minimal) lsb() float32 { return ads1014FullScale[d.pga] / 2048 }

// ReadRaw runs one single-shot conversion of AIN0 − AIN1 and returns the
// 12-bit two's-complement code (-2048…2047). It writes the whole Config word
// with OS = 1, polls OS until the chip is idle again, then reads CONVERSION.
func (d *ADS1014Minimal) ReadRaw() (int16, error) {
	if err := d.writeReg(ads1014RegConfig, d.configWord(true, false)); err != nil {
		return 0, err
	}
	d.continuous = false
	for i := 0; i < 100; i++ {
		cfg, err := d.readReg(ads1014RegConfig)
		if err != nil {
			return 0, err
		}
		if cfg&ads1014CfgOS != 0 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	return d.readConversion()
}

// ReadVoltage runs one single-shot conversion and returns AIN0 − AIN1 in
// volts (raw × full-scale / 2048).
func (d *ADS1014Minimal) ReadVoltage() (float32, error) {
	raw, err := d.ReadRaw()
	if err != nil {
		return 0, err
	}
	return float32(raw) * d.lsb(), nil
}

// ADS1014Full extends ADS1014Minimal with PGA gain, data rate, continuous
// conversion, the programmable comparator and the Level-2 ALERT/RDY
// interrupt API.
type ADS1014Full struct {
	*ADS1014Minimal

	mu          sync.Mutex
	loRaw       int16
	hiRaw       int16
	callback    func(uint8)
	unsubscribe func()
	pollPin     *connection.PollingInputPin
	lastStatus  uint8
}

// NewADS1014Full creates an ADS1014Full; no bus transaction is made.
func NewADS1014Full(conn connection.RegisterConnection) (*ADS1014Full, error) {
	m, err := NewADS1014Minimal(conn)
	if err != nil {
		return nil, err
	}
	return &ADS1014Full{ADS1014Minimal: m, loRaw: -2048, hiRaw: 2047}, nil
}

func (d *ADS1014Full) writeConfig() error {
	return d.writeReg(ads1014RegConfig, d.configWord(false, d.continuous))
}

// SetGain selects the full-scale range used by all following reads
// (ADS1014PGA*). Thresholds must be rewritten after a gain change. Values
// above 5 (which alias ±0.256 V) return ErrADS1014InvalidArgument.
func (d *ADS1014Full) SetGain(pga uint8) error {
	if pga > ADS1014PGA0_256 {
		return ErrADS1014InvalidArgument
	}
	d.pga = pga
	if d.continuous {
		return d.writeConfig()
	}
	return nil
}

// GetGain returns the configured PGA code (0-5).
func (d *ADS1014Full) GetGain() uint8 { return d.pga }

// FullScale returns ±FSR in volts for the current gain: 6.144, 4.096, 2.048,
// 1.024, 0.512 or 0.256.
func (d *ADS1014Full) FullScale() float32 { return ads1014FullScale[d.pga] }

// LSB returns the size of one conversion code in volts: FullScale() / 2048.
func (d *ADS1014Full) LSB() float32 { return d.lsb() }

// SetDataRate selects the conversion data rate (ADS1014DR*). A value above 6
// (7 aliases 3300 SPS) returns ErrADS1014InvalidArgument.
func (d *ADS1014Full) SetDataRate(dr uint8) error {
	if dr > ADS1014DR3300 {
		return ErrADS1014InvalidArgument
	}
	d.dr = dr
	if d.continuous {
		return d.writeConfig()
	}
	return nil
}

// GetDataRate returns the configured DR code (0-6).
func (d *ADS1014Full) GetDataRate() uint8 { return d.dr }

// DataRateSPS returns the samples per second of the current setting.
func (d *ADS1014Full) DataRateSPS() uint16 { return ads1014SPS[d.dr] }

// StartContinuous starts continuous conversion (MODE = 0) at the current
// data rate.
func (d *ADS1014Full) StartContinuous() error {
	d.continuous = true
	return d.writeConfig()
}

// ReadContinuousRaw reads the most recent completed result without touching
// Config (valid at least 1/DR after StartContinuous).
func (d *ADS1014Full) ReadContinuousRaw() (int16, error) { return d.readConversion() }

// ReadContinuous reads the most recent completed result in volts: raw × LSB().
func (d *ADS1014Full) ReadContinuous() (float32, error) {
	raw, err := d.readConversion()
	if err != nil {
		return 0, err
	}
	return float32(raw) * d.lsb(), nil
}

// StopContinuous stops continuous conversion (MODE = 1, single-shot /
// power-down).
func (d *ADS1014Full) StopContinuous() error {
	d.continuous = false
	return d.writeConfig()
}

// IsConverting reports whether a single-shot conversion is running (OS = 0;
// only meaningful in single-shot mode).
func (d *ADS1014Full) IsConverting() (bool, error) {
	cfg, err := d.readReg(ads1014RegConfig)
	return cfg&ads1014CfgOS == 0, err
}

// ReadConfig reads the raw 16-bit CONFIG register.
func (d *ADS1014Full) ReadConfig() (uint16, error) { return d.readReg(ads1014RegConfig) }

// Reset writes the reset values back (CONFIG 0x8583, LO_THRESH 0x8000,
// HI_THRESH 0x7FFF) and clears the cached settings. It is not an I²C
// general-call reset.
func (d *ADS1014Full) Reset() error {
	if err := d.writeReg(ads1014RegConfig, ads1014ConfigReset); err != nil {
		return err
	}
	if err := d.writeReg(ads1014RegLoThresh, ads1014LoReset); err != nil {
		return err
	}
	if err := d.writeReg(ads1014RegHiThresh, ads1014HiReset); err != nil {
		return err
	}
	d.pga, d.dr, d.continuous = ADS1014PGA2_048, ADS1014DR1600, false
	d.compMode, d.compPol, d.compLat, d.queue, d.source = 0, 0, false, 0, 0
	d.loRaw, d.hiRaw = -2048, 2047
	return nil
}

// ConfigureComparator configures the comparator and enables it as the
// ALERT/RDY source. mode is ADS1014CompTraditional or ADS1014CompWindow;
// polarity ADS1014ActiveLow or ADS1014ActiveHigh; latch holds the alert until
// CONVERSION is read; queue is ADS1014Queue1, Queue2 or Queue4. An
// out-of-range argument returns ErrADS1014InvalidArgument.
func (d *ADS1014Full) ConfigureComparator(mode, polarity uint8, latch bool, queue uint8) error {
	if mode > ADS1014CompWindow || polarity > ADS1014ActiveHigh || queue > ADS1014Queue4 {
		return ErrADS1014InvalidArgument
	}
	d.compMode, d.compPol, d.compLat, d.queue = mode, polarity, latch, queue
	d.source = ADS1014SourceComparator
	return d.writeConfig()
}

// DisableComparator disables the comparator (COMP_QUE = 11); ALERT/RDY goes
// high-impedance.
func (d *ADS1014Full) DisableComparator() error {
	d.source = 0
	return d.writeConfig()
}

func (d *ADS1014Full) writeThreshold(reg uint8, raw int16) error {
	if raw < -2048 || raw > 2047 {
		return ErrADS1014InvalidArgument
	}
	return d.writeReg(reg, (uint16(raw)&0x0FFF)<<4)
}

// SetLowThresholdRaw writes the comparator low threshold (LO_THRESH =
// raw << 4); raw is a 12-bit two's-complement code, -2048…2047.
func (d *ADS1014Full) SetLowThresholdRaw(raw int16) error {
	if err := d.writeThreshold(ads1014RegLoThresh, raw); err != nil {
		return err
	}
	d.loRaw = raw
	return nil
}

// SetHighThresholdRaw writes the comparator high threshold (HI_THRESH =
// raw << 4); raw is a 12-bit two's-complement code, -2048…2047.
func (d *ADS1014Full) SetHighThresholdRaw(raw int16) error {
	if err := d.writeThreshold(ads1014RegHiThresh, raw); err != nil {
		return err
	}
	d.hiRaw = raw
	return nil
}

// SetThresholds writes both thresholds from volts, converted with LSB() and
// clamped to the code range. high ≤ low returns ErrADS1014InvalidArgument.
func (d *ADS1014Full) SetThresholds(low, high float32) error {
	if high <= low {
		return ErrADS1014InvalidArgument
	}
	step := d.lsb()
	toCode := func(v float32) int16 {
		c := v / step
		var r int32
		if c >= 0 {
			r = int32(c + 0.5)
		} else {
			r = -int32(-c + 0.5)
		}
		if r < -2048 {
			r = -2048
		}
		if r > 2047 {
			r = 2047
		}
		return int16(r)
	}
	if err := d.SetLowThresholdRaw(toCode(low)); err != nil {
		return err
	}
	return d.SetHighThresholdRaw(toCode(high))
}

// GetThresholdsRaw reads both thresholds back as sign-extended 12-bit codes.
func (d *ADS1014Full) GetThresholdsRaw() (low, high int16, err error) {
	lo, err := d.readReg(ads1014RegLoThresh)
	if err != nil {
		return 0, 0, err
	}
	hi, err := d.readReg(ads1014RegHiThresh)
	if err != nil {
		return 0, 0, err
	}
	return int16(lo) >> 4, int16(hi) >> 4, nil
}

// ClearAlert releases a latched comparator output by reading CONVERSION.
func (d *ADS1014Full) ClearAlert() error {
	_, err := d.readReg(ads1014RegConversion)
	return err
}

// EnableInterrupt routes one source to ALERT/RDY; the other is disabled.
// ADS1014SourceConversionReady writes HI_THRESH = 0x8000 and LO_THRESH =
// 0x0000 and sets COMP_QUE = 00. ADS1014SourceComparator restores the
// thresholds last set through the driver and the configured queue; it
// returns ErrADS1014InvalidArgument when those have high ≤ low.
func (d *ADS1014Full) EnableInterrupt(source uint8) error {
	switch source {
	case ADS1014SourceConversionReady:
		if err := d.writeReg(ads1014RegHiThresh, 0x8000); err != nil {
			return err
		}
		if err := d.writeReg(ads1014RegLoThresh, 0x0000); err != nil {
			return err
		}
	case ADS1014SourceComparator:
		if d.hiRaw <= d.loRaw {
			return ErrADS1014InvalidArgument
		}
		if err := d.writeReg(ads1014RegLoThresh, (uint16(d.loRaw)&0x0FFF)<<4); err != nil {
			return err
		}
		if err := d.writeReg(ads1014RegHiThresh, (uint16(d.hiRaw)&0x0FFF)<<4); err != nil {
			return err
		}
	default:
		return ErrADS1014InvalidArgument
	}
	d.source = source
	return d.writeConfig()
}

// DisableInterrupt releases ALERT/RDY (COMP_QUE = 11). The two sources share
// the pin, so it is released whichever source is named.
func (d *ADS1014Full) DisableInterrupt(source uint8) error {
	d.source = 0
	return d.writeConfig()
}

// PollInterrupt reports whether the enabled source is asserted. The chip has
// no interrupt-status register: with an IntPin on the connection the
// ALERT/RDY level is sampled against COMP_POL (and a latched comparator
// alert is cleared by reading CONVERSION); without a pin,
// ADS1014SourceConversionReady falls back to CONFIG.OS (single-shot only)
// and ADS1014SourceComparator returns 0. It returns the enabled
// ADS1014Source* constant if asserted, else 0.
func (d *ADS1014Full) PollInterrupt() (uint8, error) {
	if d.source == 0 {
		return 0, nil
	}
	if pin := d.conn.IntPin(); pin != nil {
		high, err := pin.IsHigh()
		if err != nil {
			return 0, err
		}
		if high != (d.compPol == ADS1014ActiveHigh) {
			return 0, nil
		}
		if d.source == ADS1014SourceComparator && d.compLat {
			if err := d.ClearAlert(); err != nil {
				return 0, err
			}
		}
		return d.source, nil
	}
	if d.source == ADS1014SourceConversionReady && !d.continuous {
		cfg, err := d.readReg(ads1014RegConfig)
		if err != nil {
			return 0, err
		}
		if cfg&ads1014CfgOS != 0 {
			return ADS1014SourceConversionReady, nil
		}
	}
	return 0, nil
}

// OnInterrupt subscribes callback to ALERT/RDY events; it receives the
// PollInterrupt value. With the connection's IntPin wired it fires on every
// asserting edge — falling for ADS1014ActiveLow, rising for ADS1014ActiveHigh
// (call EnableInterrupt first). Otherwise a polling goroutine calls back
// whenever the status changes.
func (d *ADS1014Full) OnInterrupt(callback func(status uint8)) error {
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
	d.mu.Unlock()

	trigger := connection.Falling
	pin := d.conn.IntPin()
	if pin == nil {
		status, err := d.PollInterrupt()
		if err != nil {
			return err
		}
		poll := connection.NewDefaultPollingInputPin()
		d.mu.Lock()
		d.lastStatus = status
		d.pollPin = poll
		d.mu.Unlock()
		pin = poll
	} else if d.compPol == ADS1014ActiveHigh {
		trigger = connection.Rising
	}
	unsub := pin.OnEdge(trigger, func() { d.handleEdge() })
	d.mu.Lock()
	d.unsubscribe = unsub
	d.mu.Unlock()
	return nil
}

// OffInterrupt unsubscribes and stops delivery.
func (d *ADS1014Full) OffInterrupt() error {
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

func (d *ADS1014Full) handleEdge() {
	status, err := d.PollInterrupt()
	if err != nil {
		return
	}
	d.mu.Lock()
	cb := d.callback
	// A real ALERT/RDY edge is an event only while the source is asserted;
	// the polling fallback ticks continuously and reports changes only.
	fire := status != 0
	if d.pollPin != nil {
		fire = status != d.lastStatus
	}
	d.lastStatus = status
	d.mu.Unlock()
	if fire && cb != nil {
		cb(status)
	}
}
