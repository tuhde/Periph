package adcdac

import (
	"errors"
	"math"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// CS43L22 — low-power stereo DAC with headphone and Class-D speaker amplifiers (Cirrus Logic).
//
// Configured entirely through the I²C control port (7-bit address 0x4A with AD0 = DGND, 0x4B
// with AD0 = VL; 100 kHz maximum). Audio data travels over a separate I²S / PCM port that this
// driver does not touch: the application supplies MCLK and the stream, calls Start once the
// clocks run and Stop before they go away. Registers have a one-byte address and are written
// one at a time with WriteReg.
//
// The constructors make no bus access; Init (optionally after pulsing the RESET pin) verifies
// the chip ID, loads the defaults while the chip is powered down and runs the required
// initialization block.

// CS43L22I2CAddress is the 7-bit I²C address with AD0 = DGND (0x4B with AD0 = VL).
const CS43L22I2CAddress uint8 = 0x4A

const (
	cs43l22RegID             byte = 0x01
	cs43l22RegPowerCtl1      byte = 0x02
	cs43l22RegPowerCtl2      byte = 0x04
	cs43l22RegClockingCtl    byte = 0x05
	cs43l22RegInterfaceCtl1  byte = 0x06
	cs43l22RegInterfaceCtl2  byte = 0x07
	cs43l22RegPassthruASel   byte = 0x08
	cs43l22RegPassthruBSel   byte = 0x09
	cs43l22RegAnalogZCSR     byte = 0x0A
	cs43l22RegPlaybackCtl1   byte = 0x0D
	cs43l22RegMiscCtl        byte = 0x0E
	cs43l22RegPlaybackCtl2   byte = 0x0F
	cs43l22RegPassthruAVol   byte = 0x14
	cs43l22RegPassthruBVol   byte = 0x15
	cs43l22RegPCMAVol        byte = 0x1A
	cs43l22RegPCMBVol        byte = 0x1B
	cs43l22RegBeepFreqOn     byte = 0x1C
	cs43l22RegBeepVolOff     byte = 0x1D
	cs43l22RegBeepToneCfg    byte = 0x1E
	cs43l22RegToneCtl        byte = 0x1F
	cs43l22RegMasterAVol     byte = 0x20
	cs43l22RegMasterBVol     byte = 0x21
	cs43l22RegHPAVol         byte = 0x22
	cs43l22RegHPBVol         byte = 0x23
	cs43l22RegSpkAVol        byte = 0x24
	cs43l22RegSpkBVol        byte = 0x25
	cs43l22RegPCMMixerSwap   byte = 0x26
	cs43l22RegLimitCtl1      byte = 0x27
	cs43l22RegLimitCtl2      byte = 0x28
	cs43l22RegLimitAttack    byte = 0x29
	cs43l22RegStatus         byte = 0x2E
	cs43l22RegBatteryComp    byte = 0x2F
	cs43l22RegVPLevel        byte = 0x30
	cs43l22RegSpeakerStatus  byte = 0x31
	cs43l22RegChargePumpFreq byte = 0x34
	cs43l22RegInit00         byte = 0x00
	cs43l22RegInit32         byte = 0x32
	cs43l22RegInit47         byte = 0x47
)

// Output power modes for SetOutputs.
const (
	CS43L22OutFollowLow  uint8 = 0 // on when the SPKR/HP_SW pin is low
	CS43L22OutFollowHigh uint8 = 1 // on when the SPKR/HP_SW pin is high
	CS43L22OutOn         uint8 = 2 // always on
	CS43L22OutOff        uint8 = 3 // always off
)

// Serial port formats for SetInterface.
const (
	CS43L22FmtLJ  uint8 = 0 // left-justified
	CS43L22FmtI2S uint8 = 1 // I²S
	CS43L22FmtRJ  uint8 = 2 // right-justified
)

// Beep modes for SetBeep.
const (
	CS43L22BeepOff        uint8 = 0
	CS43L22BeepSingle     uint8 = 1
	CS43L22BeepMultiple   uint8 = 2
	CS43L22BeepContinuous uint8 = 3
)

// PCM channel sources for SetPCMSwap.
const (
	CS43L22SrcNormal  uint8 = 0 // channel as is
	CS43L22SrcMix     uint8 = 1 // (L+R)/2
	CS43L22SrcSwapped uint8 = 3 // swapped channel
)

// Headphone gain indices for SetHeadphoneGain (G = 0.3959 … 1.143).
const (
	CS43L22Gain03959 uint8 = 0
	CS43L22Gain04571 uint8 = 1
	CS43L22Gain05111 uint8 = 2
	CS43L22Gain06047 uint8 = 3
	CS43L22Gain07099 uint8 = 4
	CS43L22Gain08399 uint8 = 5
	CS43L22Gain1000  uint8 = 6
	CS43L22Gain1143  uint8 = 7
)

var cs43l22HPGains = [8]float32{0.3959, 0.4571, 0.5111, 0.6047, 0.7099, 0.8399, 1.000, 1.1430}
var cs43l22LimitDB = [8]int{0, -3, -6, -9, -12, -18, -24, -30}

// Errors returned by the CS43L22 drivers.
var (
	// ErrCS43L22NotFound reports that the chip ID register did not read 0x1C in bits 7:3.
	ErrCS43L22NotFound = errors.New("cs43l22: chip ID mismatch")
	// ErrCS43L22Argument reports an argument out of range, or a reserved / read-only register.
	ErrCS43L22Argument = errors.New("cs43l22: argument out of range")
	// ErrCS43L22BeepActive reports that a beep is running; stop it before changing its timing.
	ErrCS43L22BeepActive = errors.New("cs43l22: beep active")
	// ErrCS43L22NoResetPin reports that Reset needs a RESET pin.
	ErrCS43L22NoResetPin = errors.New("cs43l22: no RESET pin")
)

// CS43L22Clocking holds CLOCKING_CTL settings (see CS43L22Full.SetClocking).
type CS43L22Clocking struct {
	Auto     bool  // auto-detect the speed mode from MCLK / LRCK (slave mode)
	Speed    uint8 // speed mode 0–3 (double, single, half, quarter)
	Group32k bool  // 8, 16 and 32 kHz sample rates
	VideoClk bool  // MCLK is 27 MHz
	Ratio    uint8 // internal MCLK / LRCK ratio code 0–3 (128, 125, 132, 136)
	MclkDiv2 bool  // divide MCLK by 2
}

// DefaultCS43L22Clocking is the Minimal default: auto-detect, single speed.
func DefaultCS43L22Clocking() CS43L22Clocking { return CS43L22Clocking{Auto: true, Speed: 1} }

// CS43L22Interface is the serial audio port configuration (see CS43L22Full.SetInterface).
type CS43L22Interface struct {
	Master       bool  // master mode (clocks are outputs)
	Fmt          uint8 // CS43L22FmtLJ, CS43L22FmtI2S or CS43L22FmtRJ
	WordLength   uint8 // AWL code 0–3
	DSP          bool  // DSP (data-packed) mode; requires CS43L22FmtLJ
	InvertSclk   bool  // invert the SCLK polarity
	SclkEqMclk   bool  // SCLK = MCLK (master mode)
	InvertSwitch bool  // invert the SPKR/HP_SW pin sense
}

// DefaultCS43L22Interface is I²S slave, 24-bit.
func DefaultCS43L22Interface() CS43L22Interface { return CS43L22Interface{Fmt: CS43L22FmtI2S} }

// CS43L22Limiter is the peak limiter configuration (see CS43L22Full.SetLimiter).
type CS43L22Limiter struct {
	Enabled          bool
	MaxDB            int   // maximum threshold, nearest of 0, −3, −6, −9, −12, −18, −24, −30 dB
	CushionDB        int   // cushion threshold, same steps
	Release          uint8 // release rate 0 (fastest) … 63
	Attack           uint8 // attack rate 0 … 63
	LimitAll         bool  // limit both channels together
	SoftRampDisable  bool  // disable soft ramp while limiting
	ZeroCrossDisable bool  // disable zero-cross while limiting
}

// DefaultCS43L22Limiter is disabled, 0 dB thresholds, slowest release, LimitAll set.
func DefaultCS43L22Limiter() CS43L22Limiter { return CS43L22Limiter{Release: 0x3F, LimitAll: true} }

// CS43L22Status is the decoded STATUS register (reading it clears the flags).
type CS43L22Status struct {
	ClockError   bool // MCLK / LRCK ratio invalid (reads 1 right after the clocks start)
	DSPAOverflow bool
	DSPBOverflow bool
	PCMAOverflow bool
	PCMBOverflow bool
}

// CS43L22SpeakerStatus is the decoded SPEAKER_STATUS register.
type CS43L22SpeakerStatus struct {
	SpeakerAShort bool // speaker A output shorted to ground
	SpeakerBShort bool // speaker B output shorted to ground
	SpkrHpPin     bool // level of the SPKR/HP_SW pin
}

func cs43l22RoundHalfUp(x float32) int { return int(math.Floor(float64(x) + 0.5)) }

func cs43l22Clamp(x, lo, hi float32) float32 {
	if x < lo {
		return lo
	}
	if x > hi {
		return hi
	}
	return x
}

func cs43l22HalfDBCode(db, lo, hi float32) byte {
	return byte(cs43l22RoundHalfUp(cs43l22Clamp(db, lo, hi)*2) & 0xFF)
}

func cs43l22LimitedVolumeCode(db float32) byte {
	if db < -96 {
		return 0x01
	}
	return byte(cs43l22RoundHalfUp(cs43l22Clamp(db, -96, 0)*2) & 0xFF)
}

func cs43l22NearestLimit(db int) byte {
	abs := func(v int) int {
		if v < 0 {
			return -v
		}
		return v
	}
	best := 0
	bestErr := abs(db - cs43l22LimitDB[0])
	for i := 1; i < 8; i++ {
		if e := abs(db - cs43l22LimitDB[i]); e < bestErr {
			best, bestErr = i, e
		}
	}
	return byte(best)
}

func cs43l22Near(v, c float64) bool { return math.Abs(v-c) <= c*1e-4 }

func cs43l22NearAny(v float64, list []float64) bool {
	for _, c := range list {
		if cs43l22Near(v, c) {
			return true
		}
	}
	return false
}

func cs43l22Documented(reg byte) bool {
	switch {
	case reg == 0x00, reg == 0x01, reg == 0x02, reg >= 0x04 && reg <= 0x0A, reg >= 0x0C && reg <= 0x0F,
		reg == 0x14, reg == 0x15, reg >= 0x1A && reg <= 0x29, reg >= 0x2E && reg <= 0x32, reg == 0x34, reg == 0x47:
		return true
	}
	return false
}

// CS43L22ClockingFor returns the datasheet Table 1 clocking settings for an MCLK / sample-rate
// pair: mclkHz is one of 12.288, 11.2896, 18.432, 16.9344, 12.0, 24.0 or 27.0 MHz and fsHz a
// sample rate listed for it. The result has Auto false and is meant for SetClocking. It returns
// ErrCS43L22Argument for combinations that are not in the table.
func CS43L22ClockingFor(mclkHz uint32, fsHz float64) (CS43L22Clocking, error) {
	mclk := float64(mclkHz)
	round := []float64{8000, 12000, 16000, 24000, 32000, 48000, 96000}
	k44 := []float64{11025, 22050, 44100, 88200}
	frac := []float64{12e6 / 1088, 12e6 / 544, 12e6 / 272, 12e6 / 136}
	m27 := []float64{8000, 12000, 16000, 24000, 32000, 48000}
	video, div2 := false, false
	ratio := -1
	switch {
	case cs43l22Near(mclk, 12288000) || cs43l22Near(mclk, 18432000):
		if cs43l22NearAny(fsHz, round) {
			ratio = 0
		}
	case cs43l22Near(mclk, 11289600):
		if cs43l22NearAny(fsHz, k44) {
			ratio = 0
		}
	case cs43l22Near(mclk, 16934400):
		if cs43l22NearAny(fsHz, k44) {
			ratio = 0
		} else if cs43l22Near(fsHz, 16934400.0/2112) {
			ratio = 2
		}
	case cs43l22Near(mclk, 12000000) || cs43l22Near(mclk, 24000000):
		div2 = mclk > 18000000
		if cs43l22NearAny(fsHz, round) {
			ratio = 1
		} else if cs43l22NearAny(fsHz, frac) {
			ratio = 3
		}
	case cs43l22Near(mclk, 27000000):
		video = true
		if cs43l22NearAny(fsHz, m27) {
			ratio = 1
		} else if cs43l22NearAny(fsHz, frac[:3]) {
			ratio = 3
		}
	}
	if ratio < 0 {
		return CS43L22Clocking{}, ErrCS43L22Argument
	}
	speed := uint8(0)
	switch {
	case fsHz <= 12100:
		speed = 3
	case fsHz <= 24100:
		speed = 2
	case fsHz <= 48100:
		speed = 1
	}
	return CS43L22Clocking{
		Speed: speed, Group32k: cs43l22NearAny(fsHz, []float64{8000, 16000, 32000}),
		VideoClk: video, Ratio: uint8(ratio), MclkDiv2: div2,
	}, nil
}

// CS43L22Minimal is the minimal CS43L22 driver: bring the DAC to a playing state and control
// volume and mute of the headphone output.
//
// Defaults: I²S slave, 24-bit, clock speed auto-detected from the MCLK / LRCK ratio, headphone
// amplifiers always on, speaker amplifiers off, headphone gain 0.6047, master volume 0 dB,
// digital soft ramp on. The chip stays powered down until Start.
type CS43L22Minimal struct {
	connection connection.RegisterConnection
	resPin     connection.OutputPin
}

// NewCS43L22Minimal creates the driver; it makes no bus access. conn is an I²C
// RegisterConnection (1-byte register address) at 0x4A / 0x4B; resPin is the optional
// active-low RESET pin (nil if not wired).
func NewCS43L22Minimal(conn connection.RegisterConnection, resPin connection.OutputPin) (*CS43L22Minimal, error) {
	return &CS43L22Minimal{connection: conn, resPin: resPin}, nil
}

func (d *CS43L22Minimal) w(reg, value byte) error {
	return d.connection.WriteReg(uint32(reg), []byte{value})
}

func (d *CS43L22Minimal) r(reg byte) (byte, error) {
	b, err := d.connection.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *CS43L22Minimal) update(reg, mask, value byte) error {
	cur, err := d.r(reg)
	if err != nil {
		return err
	}
	return d.w(reg, (cur&^mask)|(value&mask))
}

func (d *CS43L22Minimal) setBit(reg byte, bit uint, on bool) error {
	v := byte(0)
	if on {
		v = 1 << bit
	}
	return d.update(reg, 1<<bit, v)
}

func cs43l22Bit(on bool, v byte) byte {
	if on {
		return v
	}
	return 0
}

// Init pulses the RESET pin (when given), verifies the chip ID, loads the defaults and runs
// the required initialization. The chip stays powered down. It returns ErrCS43L22NotFound if
// register 0x01 does not read 0x1C in bits 7:3.
func (d *CS43L22Minimal) Init() error {
	if d.resPin != nil {
		if err := d.resPin.Set(false); err != nil {
			return err
		}
		time.Sleep(2 * time.Millisecond)
		if err := d.resPin.Set(true); err != nil {
			return err
		}
		time.Sleep(2 * time.Millisecond)
	}
	id, err := d.r(cs43l22RegID)
	if err != nil {
		return err
	}
	if id&0xF8 != 0xE0 {
		return ErrCS43L22NotFound
	}
	for _, s := range [][2]byte{
		{cs43l22RegPowerCtl1, 0x01}, {cs43l22RegPowerCtl2, 0xAF}, {cs43l22RegClockingCtl, 0xA0},
		{cs43l22RegInterfaceCtl1, 0x04}, {cs43l22RegPlaybackCtl1, 0x60},
		{cs43l22RegMasterAVol, 0x00}, {cs43l22RegMasterBVol, 0x00},
		// Required initialization settings (datasheet §4.11).
		{cs43l22RegInit00, 0x99}, {cs43l22RegInit47, 0x80},
	} {
		if err := d.w(s[0], s[1]); err != nil {
			return err
		}
	}
	v, err := d.r(cs43l22RegInit32)
	if err != nil {
		return err
	}
	if err := d.w(cs43l22RegInit32, v|0x80); err != nil {
		return err
	}
	if err := d.w(cs43l22RegInit32, v&0x7F); err != nil {
		return err
	}
	return d.w(cs43l22RegInit00, 0x00)
}

// Start powers the chip up (POWER_CTL_1 = 0x9E). Call after MCLK, SCLK and LRCK are running.
func (d *CS43L22Minimal) Start() error { return d.w(cs43l22RegPowerCtl1, 0x9E) }

// Stop runs the power-down sequence: mute, disable soft ramp / zero-cross, POWER_CTL_1 = 0x9F,
// wait 100 µs. MCLK may be removed after it returns.
func (d *CS43L22Minimal) Stop() error {
	if err := d.update(cs43l22RegPlaybackCtl2, 0xF0, 0xF0); err != nil {
		return err
	}
	if err := d.update(cs43l22RegMiscCtl, 0x03, 0x00); err != nil {
		return err
	}
	if err := d.update(cs43l22RegAnalogZCSR, 0x0F, 0x00); err != nil {
		return err
	}
	if err := d.w(cs43l22RegPowerCtl1, 0x9F); err != nil {
		return err
	}
	time.Sleep(time.Millisecond)
	return nil
}

// SetVolume sets the master volume of both channels: −102 … +12 dB, nearest 0.5 dB step (clamped).
func (d *CS43L22Minimal) SetVolume(db float32) error {
	code := cs43l22HalfDBCode(db, -102, 12)
	if err := d.w(cs43l22RegMasterAVol, code); err != nil {
		return err
	}
	return d.w(cs43l22RegMasterBVol, code)
}

// GetVolume returns the master volume of channel A in dB.
func (d *CS43L22Minimal) GetVolume() (float32, error) {
	v, err := d.r(cs43l22RegMasterAVol)
	if err != nil {
		return 0, err
	}
	return float32(int8(v)) / 2, nil
}

// Mute mutes (true) or unmutes (false) both headphone channels.
func (d *CS43L22Minimal) Mute(on bool) error {
	return d.update(cs43l22RegPlaybackCtl2, 0xC0, cs43l22Bit(on, 0xC0))
}

// CS43L22Full is the full CS43L22 driver: per-channel volumes, output power, clocking and serial
// interface, tone control, beep generator, peak limiter, soft ramp / zero-cross, analog
// passthrough, battery compensation, status reads and raw register access. It embeds
// CS43L22Minimal.
type CS43L22Full struct {
	*CS43L22Minimal
}

// NewCS43L22Full creates the driver; it makes no bus access. Parameters as NewCS43L22Minimal.
func NewCS43L22Full(conn connection.RegisterConnection, resPin connection.OutputPin) (*CS43L22Full, error) {
	m, err := NewCS43L22Minimal(conn, resPin)
	if err != nil {
		return nil, err
	}
	return &CS43L22Full{m}, nil
}

// ReadID returns the chip ID (0x1C) and the revision.
func (d *CS43L22Full) ReadID() (chipID, revision uint8, err error) {
	v, err := d.r(cs43l22RegID)
	if err != nil {
		return 0, 0, err
	}
	return v >> 3, v & 0x07, nil
}

// Reset pulses RESET and re-runs the Minimal initialization (ErrCS43L22NoResetPin without a pin).
func (d *CS43L22Full) Reset() error {
	if d.resPin == nil {
		return ErrCS43L22NoResetPin
	}
	return d.Init()
}

// SetOutputs sets the power mode (CS43L22OutOn …) of the headphone and speaker amplifiers.
func (d *CS43L22Full) SetOutputs(headphone, speaker uint8) error {
	if headphone > 3 || speaker > 3 {
		return ErrCS43L22Argument
	}
	return d.w(cs43l22RegPowerCtl2, headphone<<6|headphone<<4|speaker<<2|speaker)
}

// SetVolumeChannels sets the master volume of channel A (left) and B (right), −102 … +12 dB.
func (d *CS43L22Full) SetVolumeChannels(leftDB, rightDB float32) error {
	if err := d.w(cs43l22RegMasterAVol, cs43l22HalfDBCode(leftDB, -102, 12)); err != nil {
		return err
	}
	return d.w(cs43l22RegMasterBVol, cs43l22HalfDBCode(rightDB, -102, 12))
}

// SetHeadphoneVolume sets the headphone volume, 0 … −96 dB; below −96 dB the channel is muted.
func (d *CS43L22Full) SetHeadphoneVolume(leftDB, rightDB float32) error {
	if err := d.w(cs43l22RegHPAVol, cs43l22LimitedVolumeCode(leftDB)); err != nil {
		return err
	}
	return d.w(cs43l22RegHPBVol, cs43l22LimitedVolumeCode(rightDB))
}

// SetSpeakerVolume sets the speaker volume, 0 … −96 dB; below −96 dB the channel is muted.
func (d *CS43L22Full) SetSpeakerVolume(leftDB, rightDB float32) error {
	if err := d.w(cs43l22RegSpkAVol, cs43l22LimitedVolumeCode(leftDB)); err != nil {
		return err
	}
	return d.w(cs43l22RegSpkBVol, cs43l22LimitedVolumeCode(rightDB))
}

// SetPCMVolume sets the PCM mixer volume, −51.5 … +12 dB (the mute bits are kept).
func (d *CS43L22Full) SetPCMVolume(leftDB, rightDB float32) error {
	if err := d.update(cs43l22RegPCMAVol, 0x7F, cs43l22HalfDBCode(leftDB, -51.5, 12)); err != nil {
		return err
	}
	return d.update(cs43l22RegPCMBVol, 0x7F, cs43l22HalfDBCode(rightDB, -51.5, 12))
}

// MutePCM mutes or unmutes the PCM channels.
func (d *CS43L22Full) MutePCM(left, right bool) error {
	if err := d.setBit(cs43l22RegPCMAVol, 7, left); err != nil {
		return err
	}
	return d.setBit(cs43l22RegPCMBVol, 7, right)
}

// SetHeadphoneGain selects the headphone / line analog gain, index 0–7 (CS43L22Gain03959 …).
func (d *CS43L22Full) SetHeadphoneGain(index uint8) error {
	if index > 7 {
		return ErrCS43L22Argument
	}
	return d.update(cs43l22RegPlaybackCtl1, 0xE0, index<<5)
}

// HeadphoneGain returns the headphone analog gain multiplier (0.3959 … 1.143).
func (d *CS43L22Full) HeadphoneGain() (float32, error) {
	v, err := d.r(cs43l22RegPlaybackCtl1)
	if err != nil {
		return 0, err
	}
	return cs43l22HPGains[v>>5], nil
}

// MuteOutputs mutes (1) / unmutes (0) / keeps (-1) the headphone and speaker outputs (both channels).
func (d *CS43L22Full) MuteOutputs(headphone, speaker int) error {
	if headphone >= 0 {
		if err := d.update(cs43l22RegPlaybackCtl2, 0xC0, cs43l22Bit(headphone != 0, 0xC0)); err != nil {
			return err
		}
	}
	if speaker >= 0 {
		if err := d.update(cs43l22RegPlaybackCtl2, 0x30, cs43l22Bit(speaker != 0, 0x30)); err != nil {
			return err
		}
	}
	return nil
}

// SetSpeakerMode configures speaker mono, channel swap and exact-50 % mute.
func (d *CS43L22Full) SetSpeakerMode(mono, swap, mute5050 bool) error {
	return d.update(cs43l22RegPlaybackCtl2, 0x07,
		cs43l22Bit(mono, 0x02)|cs43l22Bit(swap, 0x04)|cs43l22Bit(mute5050, 0x01))
}

// SetClocking writes CLOCKING_CTL.
func (d *CS43L22Full) SetClocking(c CS43L22Clocking) error {
	if c.Speed > 3 || c.Ratio > 3 {
		return ErrCS43L22Argument
	}
	return d.w(cs43l22RegClockingCtl, cs43l22Bit(c.Auto, 0x80)|c.Speed<<5|cs43l22Bit(c.Group32k, 0x10)|
		cs43l22Bit(c.VideoClk, 0x08)|c.Ratio<<1|cs43l22Bit(c.MclkDiv2, 0x01))
}

// SetInterface configures the serial audio port (INTERFACE_CTL_1 / INTERFACE_CTL_2).
func (d *CS43L22Full) SetInterface(i CS43L22Interface) error {
	if i.Fmt > 2 || i.WordLength > 3 || (i.DSP && i.Fmt != CS43L22FmtLJ) {
		return ErrCS43L22Argument
	}
	if err := d.w(cs43l22RegInterfaceCtl1, cs43l22Bit(i.Master, 0x80)|cs43l22Bit(i.InvertSclk, 0x40)|
		cs43l22Bit(i.DSP, 0x10)|i.Fmt<<2|i.WordLength); err != nil {
		return err
	}
	return d.w(cs43l22RegInterfaceCtl2, cs43l22Bit(i.SclkEqMclk, 0x40)|cs43l22Bit(i.InvertSwitch, 0x08))
}

// SetTone configures the treble / bass shelves (−10.5 … +12 dB in 1.5 dB steps); corners 0–3.
func (d *CS43L22Full) SetTone(trebleDB, bassDB float32, trebleCorner, bassCorner uint8, enabled bool) error {
	if trebleCorner > 3 || bassCorner > 3 {
		return ErrCS43L22Argument
	}
	clamp := func(v int) byte {
		if v < 0 {
			return 0
		}
		if v > 15 {
			return 15
		}
		return byte(v)
	}
	treb := clamp(8 - cs43l22RoundHalfUp(trebleDB/1.5))
	bass := clamp(8 - cs43l22RoundHalfUp(bassDB/1.5))
	if err := d.w(cs43l22RegToneCtl, treb<<4|bass); err != nil {
		return err
	}
	return d.update(cs43l22RegBeepToneCfg, 0x1F, trebleCorner<<3|bassCorner<<1|cs43l22Bit(enabled, 1))
}

// SetBeep programs the beep generator (timing first, mode last): frequency 0–15, onTime 0–15,
// offTime 0–7, volumeDB +6 … −56 dB in 2 dB steps, mode CS43L22BeepOff … CS43L22BeepContinuous.
// Mode CS43L22BeepOff only stops the beep. It returns ErrCS43L22BeepActive if a beep is running
// and mode is not CS43L22BeepOff.
func (d *CS43L22Full) SetBeep(frequency, onTime, offTime uint8, volumeDB float32, mode uint8, mix bool) error {
	if frequency > 15 || onTime > 15 || offTime > 7 || mode > 3 {
		return ErrCS43L22Argument
	}
	if mode == CS43L22BeepOff {
		return d.StopBeep()
	}
	cfg, err := d.r(cs43l22RegBeepToneCfg)
	if err != nil {
		return err
	}
	if cfg&0xC0 != 0 {
		return ErrCS43L22BeepActive
	}
	n := cs43l22RoundHalfUp((6 - volumeDB) / 2)
	if n < 0 {
		n = 0
	}
	if n > 31 {
		n = 31
	}
	if err := d.w(cs43l22RegBeepFreqOn, frequency<<4|onTime); err != nil {
		return err
	}
	if err := d.w(cs43l22RegBeepVolOff, offTime<<5|byte((6-n)&0x1F)); err != nil {
		return err
	}
	return d.update(cs43l22RegBeepToneCfg, 0xE0, mode<<6|cs43l22Bit(!mix, 0x20))
}

// StopBeep stops the beep generator (BEEP[1:0] = 0).
func (d *CS43L22Full) StopBeep() error { return d.update(cs43l22RegBeepToneCfg, 0xC0, 0) }

// SetLimiter configures the peak limiter.
func (d *CS43L22Full) SetLimiter(l CS43L22Limiter) error {
	if l.Release > 63 || l.Attack > 63 {
		return ErrCS43L22Argument
	}
	if err := d.w(cs43l22RegLimitCtl1, cs43l22NearestLimit(l.MaxDB)<<5|cs43l22NearestLimit(l.CushionDB)<<2|
		cs43l22Bit(l.SoftRampDisable, 0x02)|cs43l22Bit(l.ZeroCrossDisable, 0x01)); err != nil {
		return err
	}
	if err := d.w(cs43l22RegLimitCtl2, cs43l22Bit(l.Enabled, 0x80)|cs43l22Bit(l.LimitAll, 0x40)|l.Release); err != nil {
		return err
	}
	return d.w(cs43l22RegLimitAttack, l.Attack)
}

// SetSoftRamp enables soft-ramp volume transitions (DIGSFT, ANLGSFTA, ANLGSFTB).
func (d *CS43L22Full) SetSoftRamp(digital, analogLeft, analogRight bool) error {
	if err := d.setBit(cs43l22RegMiscCtl, 1, digital); err != nil {
		return err
	}
	return d.update(cs43l22RegAnalogZCSR, 0x0A, cs43l22Bit(analogLeft, 0x02)|cs43l22Bit(analogRight, 0x08))
}

// SetZeroCross enables zero-cross volume transitions (DIGZC, ANLGZCA, ANLGZCB).
func (d *CS43L22Full) SetZeroCross(digital, analogLeft, analogRight bool) error {
	if err := d.setBit(cs43l22RegMiscCtl, 0, digital); err != nil {
		return err
	}
	return d.update(cs43l22RegAnalogZCSR, 0x05, cs43l22Bit(analogLeft, 0x01)|cs43l22Bit(analogRight, 0x04))
}

// SetDeemphasis enables the digital de-emphasis filter.
func (d *CS43L22Full) SetDeemphasis(enabled bool) error {
	return d.setBit(cs43l22RegMiscCtl, 2, enabled)
}

// SetPCMSwap selects the source of each PCM channel (CS43L22SrcNormal, CS43L22SrcMix, CS43L22SrcSwapped).
func (d *CS43L22Full) SetPCMSwap(leftSource, rightSource uint8) error {
	if leftSource > 3 || rightSource > 3 {
		return ErrCS43L22Argument
	}
	return d.w(cs43l22RegPCMMixerSwap, leftSource<<6|rightSource<<4)
}

// InvertPCM inverts the PCM polarity of each channel.
func (d *CS43L22Full) InvertPCM(left, right bool) error {
	return d.update(cs43l22RegPlaybackCtl1, 0x0C, cs43l22Bit(left, 0x04)|cs43l22Bit(right, 0x08))
}

// SetPassthrough routes the analog inputs AIN1 … AIN4 (1–4) to the headphone outputs.
func (d *CS43L22Full) SetPassthrough(enabled bool, leftInput, rightInput uint8) error {
	if leftInput < 1 || leftInput > 4 || rightInput < 1 || rightInput > 4 {
		return ErrCS43L22Argument
	}
	if err := d.w(cs43l22RegPassthruASel, 0x80|1<<(leftInput-1)); err != nil {
		return err
	}
	if err := d.w(cs43l22RegPassthruBSel, 0x80|1<<(rightInput-1)); err != nil {
		return err
	}
	return d.update(cs43l22RegMiscCtl, 0xC0, cs43l22Bit(enabled, 0xC0))
}

// SetPassthroughVolume sets the passthrough volume, −60 … +12 dB.
func (d *CS43L22Full) SetPassthroughVolume(leftDB, rightDB float32) error {
	if err := d.w(cs43l22RegPassthruAVol, cs43l22HalfDBCode(leftDB, -60, 12)); err != nil {
		return err
	}
	return d.w(cs43l22RegPassthruBVol, cs43l22HalfDBCode(rightDB, -60, 12))
}

// MutePassthrough mutes or unmutes the passthrough channels.
func (d *CS43L22Full) MutePassthrough(left, right bool) error {
	if err := d.setBit(cs43l22RegMiscCtl, 4, left); err != nil {
		return err
	}
	return d.setBit(cs43l22RegMiscCtl, 5, right)
}

// Freeze holds (true) or applies (false) register changes (FREEZE).
func (d *CS43L22Full) Freeze(on bool) error { return d.setBit(cs43l22RegMiscCtl, 3, on) }

// SetBatteryCompensation configures battery compensation for the speaker supply VP (vpRefIndex 0–15).
func (d *CS43L22Full) SetBatteryCompensation(enabled bool, vpRefIndex uint8, monitor bool) error {
	if vpRefIndex > 15 {
		return ErrCS43L22Argument
	}
	return d.w(cs43l22RegBatteryComp, cs43l22Bit(enabled, 0x80)|cs43l22Bit(monitor, 0x40)|vpRefIndex)
}

// ReadVPVoltage returns the VP supply voltage in volts: VP_LEVEL × vaVolts / 63.3.
func (d *CS43L22Full) ReadVPVoltage(vaVolts float32) (float32, error) {
	v, err := d.r(cs43l22RegVPLevel)
	if err != nil {
		return 0, err
	}
	return float32(v) * vaVolts / 63.3, nil
}

// ReadStatus reads the STATUS register — reading clears the flags.
func (d *CS43L22Full) ReadStatus() (CS43L22Status, error) {
	v, err := d.r(cs43l22RegStatus)
	if err != nil {
		return CS43L22Status{}, err
	}
	return CS43L22Status{
		ClockError: v&0x40 != 0, DSPAOverflow: v&0x20 != 0, DSPBOverflow: v&0x10 != 0,
		PCMAOverflow: v&0x08 != 0, PCMBOverflow: v&0x04 != 0,
	}, nil
}

// ReadSpeakerStatus reads the SPEAKER_STATUS register.
func (d *CS43L22Full) ReadSpeakerStatus() (CS43L22SpeakerStatus, error) {
	v, err := d.r(cs43l22RegSpeakerStatus)
	if err != nil {
		return CS43L22SpeakerStatus{}, err
	}
	return CS43L22SpeakerStatus{SpeakerAShort: v&0x20 != 0, SpeakerBShort: v&0x10 != 0, SpkrHpPin: v&0x08 != 0}, nil
}

// SetChargePumpFrequency sets the charge-pump divider N (0–15); frequency = 64·Fs / (N + 2).
func (d *CS43L22Full) SetChargePumpFrequency(n uint8) error {
	if n > 15 {
		return ErrCS43L22Argument
	}
	return d.update(cs43l22RegChargePumpFreq, 0xF0, n<<4)
}

// ReadRegister reads any documented register (reading STATUS clears it).
func (d *CS43L22Full) ReadRegister(reg uint8) (uint8, error) {
	if !cs43l22Documented(reg) {
		return 0, ErrCS43L22Argument
	}
	return d.r(reg)
}

// WriteRegister writes any documented writable register.
func (d *CS43L22Full) WriteRegister(reg, value uint8) error {
	if !cs43l22Documented(reg) || reg == 0x01 || reg == 0x2E || reg == 0x30 || reg == 0x31 {
		return ErrCS43L22Argument
	}
	return d.w(reg, value)
}
