package adcdac

import (
	"errors"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// ES8311 register map (documented registers plus the vendor-driver registers
// the user guide leaves out; see specs/adc_dac/es8311.md).
const (
	es8311RegReset       = 0x00
	es8311RegClk1        = 0x01
	es8311RegClk2        = 0x02
	es8311RegClk3        = 0x03
	es8311RegClk4        = 0x04
	es8311RegClk5        = 0x05
	es8311RegClk6        = 0x06
	es8311RegClk7        = 0x07
	es8311RegClk8        = 0x08
	es8311RegSdpIn       = 0x09
	es8311RegSdpOut      = 0x0A
	es8311RegSysPwr      = 0x0D
	es8311RegSysADC      = 0x0E
	es8311RegSysLP       = 0x0F
	es8311RegSysMic      = 0x14
	es8311RegADCRamp     = 0x15
	es8311RegADCScale    = 0x16
	es8311RegADCVolume   = 0x17
	es8311RegADCALC1     = 0x18
	es8311RegADCALC2     = 0x19
	es8311RegADCAutomute = 0x1A
	es8311RegADCHPF1     = 0x1B
	es8311RegADCHPF2EQ   = 0x1C
	es8311RegADCEQ       = 0x1D
	es8311RegDACMute     = 0x31
	es8311RegDACVolume   = 0x32
	es8311RegDACDRC1     = 0x34
	es8311RegDACDRC2     = 0x35
	es8311RegDACRampEQ   = 0x37
	es8311RegDACEQ       = 0x38
	es8311RegGPIO        = 0x44
)

// es8311ResetDelay is the wait between setting and clearing the reset bits
// (the vendor driver value; the user guide only says "several milliseconds").
const es8311ResetDelay = 20 * time.Millisecond

// ES8311 digital audio port formats.
const (
	ES8311FormatI2S           uint8 = 0
	ES8311FormatLeftJustified uint8 = 1
	ES8311FormatDSPA          uint8 = 2
	ES8311FormatDSPB          uint8 = 3
)

// ES8311 microphone input sources.
const (
	ES8311InputAnalog  uint8 = 0
	ES8311InputDigital uint8 = 1
	ES8311InputNone    uint8 = 2
)

// ES8311 ADCDAT output content.
const (
	ES8311AdcdatADCADC  uint8 = 0
	ES8311AdcdatADCZero uint8 = 1
	ES8311AdcdatZeroADC uint8 = 2
	ES8311AdcdatDACADC  uint8 = 4
	ES8311AdcdatADCDAC  uint8 = 5
	ES8311AdcdatDACDAC  uint8 = 6
)

// ES8311ALCLevelsDB are the ALC / DRC target levels in dB, indexed by the 4-bit register code.
var ES8311ALCLevelsDB = [16]float32{-30.1, -24.1, -20.6, -18.1, -16.1, -14.5, -13.2, -12.0,
	-11.0, -10.1, -9.3, -8.5, -7.8, -7.2, -6.6, -6.0}

// ES8311NoiseGateDB are the noise-gate thresholds in dB, indexed by the 4-bit register code.
var ES8311NoiseGateDB = [16]float32{-96, -90, -84, -78, -72, -66, -60, -54, -51, -48, -45, -42, -39, -36, -33, -30}

type es8311Preset struct {
	mclk, fs           uint32
	r02, r03, r04, r05 uint8
}

// Guide clock table (first set for the duplicated 12 MHz rows).
var es8311Presets = []es8311Preset{
	{24576000, 32000, 0x40, 0x10, 0x10, 0x00},
	{24576000, 48000, 0x20, 0x10, 0x10, 0x00},
	{12288000, 32000, 0x48, 0x10, 0x10, 0x00},
	{12288000, 48000, 0x00, 0x10, 0x10, 0x00},
	{18432000, 32000, 0x20, 0x12, 0x12, 0x00},
	{18432000, 48000, 0x48, 0x10, 0x10, 0x00},
	{11289600, 44100, 0x00, 0x10, 0x10, 0x00},
	{12000000, 32000, 0x00, 0x17, 0x17, 0x00},
	{12000000, 44100, 0x00, 0x11, 0x11, 0x00},
	{12000000, 48000, 0x98, 0x19, 0x19, 0x00},
	{26000000, 32000, 0x40, 0x10, 0x10, 0x00},
	{3000000, 32000, 0x18, 0x52, 0x17, 0x41},
	{25000000, 32000, 0x48, 0x55, 0x10, 0x21},
	{25000000, 48000, 0x28, 0x55, 0x10, 0x21},
}

var es8311PreferredRatios = []uint64{256, 384, 512, 768, 1024, 1536}

// DIV_BCLK codes 20..31.
var es8311BclkDividers = []uint16{22, 24, 25, 30, 32, 33, 34, 36, 44, 48, 66, 72}

// errES8311InvalidClock is returned when no divider set fits the MCLK / sample rate.
var errES8311InvalidClock = errors.New("ES8311: no valid clock divider for this MCLK / sample rate")

func es8311Nearest(table *[16]float32, value float32) uint8 {
	best := 0
	dist := func(t float32) float32 {
		if t > value {
			return t - value
		}
		return value - t
	}
	for i := 1; i < 16; i++ {
		if dist(table[i]) < dist(table[best]) {
			best = i
		}
	}
	return uint8(best)
}

// es8311Round rounds half away from zero.
func es8311Round(x float32) int {
	if x >= 0 {
		return int(x + 0.5)
	}
	return int(x - 0.5)
}

func es8311Clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func es8311VolumeToReg(db float32) byte {
	return byte(es8311Clamp(es8311Round(db/0.5)+191, 0, 255))
}

// es8311SolveClock returns the clock registers 0x02–0x05 for an MCLK / fs pair: the
// guide's preset table first (3.3 V only), then a search over pre-divider,
// multiplier and ADC/DAC divider for an allowed MCLK:LRCK ratio.
func es8311SolveClock(mclkHz, fsHz uint32, dvdd float32) ([4]byte, error) {
	if fsHz == 0 {
		return [4]byte{}, errES8311InvalidClock
	}
	low := dvdd < 2.5
	if !low {
		for _, p := range es8311Presets {
			if p.mclk == mclkHz && p.fs == fsHz {
				return [4]byte{p.r02, p.r03, p.r04, p.r05}, nil
			}
		}
	}
	preLimit, clkLimit := uint64(10000000), uint64(35000000)
	if low {
		preLimit, clkLimit = 4500000, 13000000
	}
	mclk, fs := uint64(mclkHz), uint64(fsHz)
	found := false
	var bestKey [4]uint64
	var bestR02, bestOSR, bestDiv byte
	mults := [4]uint64{1, 2, 4, 8}
	for dp := uint64(1); dp <= 8; dp++ {
		if mclk >= preLimit*dp {
			continue
		}
		for mi, mult := range mults {
			for div := uint64(0); div < 16; div++ {
				den := dp * (div + 1)
				if mclk*mult >= clkLimit*den {
					continue
				}
				if (mclk*mult)%(den*fs) != 0 {
					continue
				}
				ratio := mclk * mult / (den * fs)
				if ratio%16 != 0 || ratio < 256 || ratio > 1008 {
					continue
				}
				rank := uint64(99)
				for i, r := range es8311PreferredRatios {
					if r == ratio {
						rank = uint64(i)
					}
				}
				key := [4]uint64{rank, dp, mult, div}
				if !found || es8311KeyLess(key, bestKey) {
					found = true
					bestKey = key
					bestR02 = byte((dp-1)<<5) | byte(mi<<3)
					bestOSR = byte(ratio / 16)
					bestDiv = byte(div)
				}
			}
		}
	}
	if !found {
		return [4]byte{}, errES8311InvalidClock
	}
	return [4]byte{bestR02, bestOSR, bestOSR, bestDiv<<4 | bestDiv}, nil
}

func es8311KeyLess(a, b [4]uint64) bool {
	for i := 0; i < 4; i++ {
		if a[i] != b[i] {
			return a[i] < b[i]
		}
	}
	return false
}

// ES8311Minimal is the ES8311 low-power mono audio codec (I²C control port) — minimal interface.
//
// Brings the codec up for voice playback and recording and lets the application
// set output volume, mute and microphone gain. The audio stream itself runs over
// a separate I²S port that this library does not provide.
type ES8311Minimal struct {
	connection connection.RegisterConnection
	mclkHz     uint32
	fs         uint32
	dvdd       float32
}

// NewES8311Minimal creates the driver and runs the initialization sequence:
// software reset, clock tree for mclkHz / sampleRateHz, I²S 16-bit slave, analog
// path, analog microphone at 24 dB, ADC 0 dB, DAC volume −95.5 dB (silent until
// SetVolume). Uses the 3.3 V clock limits; see NewES8311MinimalDVDD.
//
// connection must be an I²C connection bound to 0x18 (CE low) or 0x19 (CE high).
// The registers 0x04, 0x0B–0x0C and 0x10–0x13 are undocumented in the user guide;
// the vendor reference-driver values are used — verify on hardware.
// An error is returned if the MCLK : sample-rate pair has no valid divider set.
func NewES8311Minimal(conn connection.RegisterConnection, mclkHz, sampleRateHz uint32) (*ES8311Minimal, error) {
	return NewES8311MinimalDVDD(conn, mclkHz, sampleRateHz, 3.3)
}

// NewES8311MinimalDVDD is NewES8311Minimal with the DVDD supply voltage selecting the
// 3.3 V or 1.8 V clock limits.
func NewES8311MinimalDVDD(conn connection.RegisterConnection, mclkHz, sampleRateHz uint32, dvddVolts float32) (*ES8311Minimal, error) {
	clk, err := es8311SolveClock(mclkHz, sampleRateHz, dvddVolts)
	if err != nil {
		return nil, err
	}
	d := &ES8311Minimal{connection: conn, mclkHz: mclkHz, fs: sampleRateHz, dvdd: dvddVolts}
	steps := []func() error{
		d.softReset,
		func() error { return d.w(es8311RegClk1, 0x3F) },
		func() error { return d.writeClock(clk) },
		func() error { return d.w(es8311RegClk6, 0x03) },
		func() error { return d.w(es8311RegClk7, 0x00) },
		func() error { return d.w(es8311RegClk8, 0xFF) },
		func() error { return d.w(es8311RegSdpIn, 0x0C) },
		func() error { return d.w(es8311RegSdpOut, 0x0C) },
		d.powerUpAnalog,
		func() error { return d.w(es8311RegADCScale, 0x04) },
		func() error { return d.w(es8311RegSysMic, 0x18) },
		func() error { return d.w(es8311RegADCVolume, 0xBF) },
		func() error { return d.w(es8311RegDACVolume, 0x00) },
	}
	for _, s := range steps {
		if err := s(); err != nil {
			return nil, err
		}
	}
	return d, nil
}

func (d *ES8311Minimal) w(reg byte, value byte) error {
	return d.connection.WriteReg(uint32(reg), []byte{value})
}

func (d *ES8311Minimal) r(reg byte) (byte, error) {
	b, err := d.connection.ReadReg(uint32(reg), 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *ES8311Minimal) update(reg, mask, value byte) error {
	cur, err := d.r(reg)
	if err != nil {
		return err
	}
	return d.w(reg, (cur&^mask)|(value&mask))
}

func (d *ES8311Minimal) softReset() error {
	if err := d.w(es8311RegReset, 0x1F); err != nil {
		return err
	}
	time.Sleep(es8311ResetDelay)
	if err := d.w(es8311RegReset, 0x00); err != nil {
		return err
	}
	return d.w(es8311RegReset, 0x80)
}

func (d *ES8311Minimal) writeClock(clk [4]byte) error {
	for i, reg := range []byte{es8311RegClk2, es8311RegClk3, es8311RegClk4, es8311RegClk5} {
		if err := d.w(reg, clk[i]); err != nil {
			return err
		}
	}
	return nil
}

func (d *ES8311Minimal) powerUpAnalog() error {
	for _, rv := range [][2]byte{{0x0B, 0x00}, {0x0C, 0x00}, {0x10, 0x1F}, {0x11, 0x7F},
		{es8311RegSysPwr, 0x01}, {es8311RegSysADC, 0x02}, {0x12, 0x00}, {0x13, 0x10},
		{es8311RegADCHPF2EQ, 0x6A}, {es8311RegDACRampEQ, 0x08}} {
		if err := d.w(rv[0], rv[1]); err != nil {
			return err
		}
	}
	return nil
}

// SetVolume sets the DAC digital volume: −95.5 … +32 dB, rounded to the nearest 0.5 dB step (clamped).
func (d *ES8311Minimal) SetVolume(db float32) error {
	return d.w(es8311RegDACVolume, es8311VolumeToReg(db))
}

// SetMute mutes or unmutes the DAC (DSM and DEM mute together, 0x31 bits 6 and 5).
func (d *ES8311Minimal) SetMute(muted bool) error {
	v := byte(0)
	if muted {
		v = 0x60
	}
	return d.update(es8311RegDACMute, 0x60, v)
}

// SetMicGain sets the analog microphone PGA gain: 0 … 30 dB, rounded to the nearest 3 dB step (clamped).
func (d *ES8311Minimal) SetMicGain(db float32) error {
	return d.update(es8311RegSysMic, 0x0F, byte(es8311Clamp(es8311Round(db/3), 0, 10)))
}

// ES8311Full extends ES8311Minimal with the clock tree, serial port, microphone / ADC /
// DAC dynamics, equalizers, routing, power control and raw register access.
type ES8311Full struct {
	*ES8311Minimal
}

// NewES8311Full creates the driver with the same initialization as NewES8311Minimal.
func NewES8311Full(conn connection.RegisterConnection, mclkHz, sampleRateHz uint32) (*ES8311Full, error) {
	m, err := NewES8311Minimal(conn, mclkHz, sampleRateHz)
	if err != nil {
		return nil, err
	}
	return &ES8311Full{ES8311Minimal: m}, nil
}

// NewES8311FullDVDD is NewES8311Full with the DVDD supply voltage selecting the clock limits.
func NewES8311FullDVDD(conn connection.RegisterConnection, mclkHz, sampleRateHz uint32, dvddVolts float32) (*ES8311Full, error) {
	m, err := NewES8311MinimalDVDD(conn, mclkHz, sampleRateHz, dvddVolts)
	if err != nil {
		return nil, err
	}
	return &ES8311Full{ES8311Minimal: m}, nil
}

// GetVolume reads back the DAC volume in dB (−95.5 … +32).
func (d *ES8311Full) GetVolume() (float32, error) {
	v, err := d.r(es8311RegDACVolume)
	if err != nil {
		return 0, err
	}
	return float32(int(v)-191) * 0.5, nil
}

// GetMicGain reads back the analog microphone PGA gain in dB (0 … 30).
func (d *ES8311Full) GetMicGain() (float32, error) {
	v, err := d.r(es8311RegSysMic)
	if err != nil {
		return 0, err
	}
	return float32(v&0x0F) * 3, nil
}

// Reset performs a software reset; registers return to their reset values (configure again afterwards).
func (d *ES8311Full) Reset() error {
	return d.softReset()
}

// PowerUp re-enables the analog, reference and clock blocks (vendor values) and sets CSM_ON.
func (d *ES8311Full) PowerUp() error {
	if err := d.update(es8311RegReset, 0x9F, 0x00); err != nil {
		return err
	}
	if err := d.update(es8311RegReset, 0x80, 0x80); err != nil {
		return err
	}
	if err := d.w(es8311RegClk1, 0x3F); err != nil {
		return err
	}
	return d.powerUpAnalog()
}

// PowerDown enters standby: mutes the DAC, powers down the analog blocks, resets all and clears CSM_ON.
func (d *ES8311Full) PowerDown() error {
	if err := d.SetMute(true); err != nil {
		return err
	}
	if err := d.update(es8311RegSysPwr, 0xFC, 0xFC); err != nil {
		return err
	}
	if err := d.update(es8311RegSysADC, 0x70, 0x70); err != nil {
		return err
	}
	if err := d.update(es8311RegSysPwr, 0x03, 0x00); err != nil {
		return err
	}
	return d.w(es8311RegReset, 0x1F)
}

// ES8311LowPower holds the low-power bits of register 0x0F.
type ES8311LowPower struct {
	DAC, PGA, PGAOut, ADC, ADCRef, DACRef, Flash, Integrator bool
}

// SetLowPower sets the low-power bits of register 0x0F (slightly lower THD+N / SNR).
func (d *ES8311Full) SetLowPower(lp ES8311LowPower) error {
	flags := []bool{lp.DAC, lp.PGA, lp.PGAOut, lp.ADC, lp.ADCRef, lp.DACRef, lp.Flash, lp.Integrator}
	var v byte
	for i, f := range flags {
		if f {
			v |= 0x80 >> uint(i)
		}
	}
	return d.w(es8311RegSysLP, v)
}

// ConfigureClock programs the clock tree (registers 0x01–0x05) from a preset or computed dividers.
//
// mclkFromBclk takes the master clock from the SCLK (BCLK) pin; mclkInvert inverts it.
// An error is returned (and nothing is written) if no valid divider set exists.
func (d *ES8311Full) ConfigureClock(mclkHz, sampleRateHz uint32, mclkFromBclk, mclkInvert bool) error {
	clk, err := es8311SolveClock(mclkHz, sampleRateHz, d.dvdd)
	if err != nil {
		return err
	}
	d.mclkHz = mclkHz
	d.fs = sampleRateHz
	flags := byte(0x3F)
	if mclkFromBclk {
		flags |= 0x80
	}
	if mclkInvert {
		flags |= 0x40
	}
	if err := d.w(es8311RegClk1, flags); err != nil {
		return err
	}
	return d.writeClock(clk)
}

// ES8311SerialConfig configures the digital audio port.
type ES8311SerialConfig struct {
	Format      uint8  // ES8311FormatI2S (default), ES8311FormatLeftJustified, ES8311FormatDSPA, ES8311FormatDSPB
	WordLength  uint8  // 16, 18, 20, 24 or 32 bit (0 = 16)
	Master      bool   // chip drives BCLK / LRCK
	BclkInvert  bool   // invert BCLK
	LrckInvert  bool   // invert the LRCK polarity
	DACRight    bool   // send the right channel to the DAC
	BclkDiv     uint16 // master BCLK divider (0 = computed)
	LrckDiv     uint16 // master LRCK divider (0 = computed)
	StoppedBclk bool   // true = BCLK stops when idle (default: continuous)
}

func (d *ES8311Full) internalClock() (uint32, error) {
	r02, err := d.r(es8311RegClk2)
	if err != nil {
		return 0, err
	}
	mults := [4]uint64{1, 2, 4, 8}
	return uint32(uint64(d.mclkHz) * mults[(r02>>3)&3] / (uint64((r02>>5)&7) + 1)), nil
}

// ConfigureSerial configures the digital audio port (0x09, 0x0A, RESET.MSC, 0x06–0x08).
//
// An error is returned for an unknown format, word length or BCLK divider.
func (d *ES8311Full) ConfigureSerial(cfg ES8311SerialConfig) error {
	if cfg.WordLength == 0 {
		cfg.WordLength = 16
	}
	if cfg.Format > ES8311FormatDSPB {
		return errors.New("ES8311: unknown serial format")
	}
	var wl byte
	switch cfg.WordLength {
	case 24:
		wl = 0
	case 20:
		wl = 1
	case 18:
		wl = 2
	case 16:
		wl = 3
	case 32:
		wl = 4
	default:
		return fmt.Errorf("ES8311: unsupported word length %d", cfg.WordLength)
	}
	fmtCode := byte(3)
	switch cfg.Format {
	case ES8311FormatI2S:
		fmtCode = 0
	case ES8311FormatLeftJustified:
		fmtCode = 1
	}
	var lrp byte
	if cfg.LrckInvert {
		lrp = 1
	}
	if cfg.Format == ES8311FormatDSPB {
		lrp ^= 1
	}
	base := wl<<2 | fmtCode | lrp<<5
	var code byte
	lrckDiv := cfg.LrckDiv
	if cfg.Master {
		imclk, err := d.internalClock()
		if err != nil {
			return err
		}
		bclkDiv := cfg.BclkDiv
		if bclkDiv == 0 {
			bclkDiv = uint16(imclk / (d.fs * 2 * uint32(cfg.WordLength)))
		}
		if lrckDiv == 0 {
			lrckDiv = uint16(imclk / d.fs)
		}
		if bclkDiv == 0 || lrckDiv == 0 || lrckDiv > 4096 {
			return errors.New("ES8311: invalid master clock divider")
		}
		if bclkDiv <= 20 {
			code = byte(bclkDiv - 1)
		} else {
			ok := false
			for i, v := range es8311BclkDividers {
				if v == bclkDiv {
					code = byte(20 + i)
					ok = true
				}
			}
			if !ok {
				return errors.New("ES8311: unsupported BCLK divider")
			}
		}
	}
	inMute, err := d.r(es8311RegSdpIn)
	if err != nil {
		return err
	}
	in := base | (inMute & 0x40)
	if cfg.DACRight {
		in |= 0x80
	}
	if err := d.w(es8311RegSdpIn, in); err != nil {
		return err
	}
	outMute, err := d.r(es8311RegSdpOut)
	if err != nil {
		return err
	}
	if err := d.w(es8311RegSdpOut, base|(outMute&0x40)); err != nil {
		return err
	}
	if cfg.Master {
		c6 := code
		if cfg.StoppedBclk {
			c6 |= 0x40
		}
		if cfg.BclkInvert {
			c6 |= 0x20
		}
		if err := d.w(es8311RegClk6, c6); err != nil {
			return err
		}
		if err := d.update(es8311RegClk7, 0x0F, byte(((lrckDiv-1)>>8)&0x0F)); err != nil {
			return err
		}
		if err := d.w(es8311RegClk8, byte((lrckDiv-1)&0xFF)); err != nil {
			return err
		}
		return d.update(es8311RegReset, 0x44, 0x40)
	}
	inv := byte(0)
	if cfg.BclkInvert {
		inv = 0x20
	}
	if err := d.update(es8311RegClk6, 0x20, inv); err != nil {
		return err
	}
	return d.update(es8311RegReset, 0x44, 0x04)
}

// SetTristate tri-states the BCLK / LRCK pins and / or ADCDAT (0x07 bits 5:4).
func (d *ES8311Full) SetTristate(bclkLrck, adcdat bool) error {
	var v byte
	if bclkLrck {
		v |= 0x20
	}
	if adcdat {
		v |= 0x10
	}
	return d.update(es8311RegClk7, 0x30, v)
}

// SetInput selects the microphone input: ES8311InputAnalog (MIC1P/MIC1N),
// ES8311InputDigital (PDM on MIC1P) or ES8311InputNone.
func (d *ES8311Full) SetInput(source uint8) error {
	var sel, pga byte
	switch source {
	case ES8311InputAnalog:
		sel, pga = 0x10, 0x00
	case ES8311InputDigital:
		sel, pga = 0x40, 0x40
	case ES8311InputNone:
		sel, pga = 0x00, 0x40
	default:
		return errors.New("ES8311: unknown input source")
	}
	if err := d.update(es8311RegSysMic, 0x70, sel); err != nil {
		return err
	}
	return d.update(es8311RegSysADC, 0x40, pga)
}

// ConfigureDMIC selects the PDM data latch edge (0x15 bit 0).
func (d *ES8311Full) ConfigureDMIC(latchFallingEdge bool) error {
	v := byte(0)
	if latchFallingEdge {
		v = 1
	}
	return d.update(es8311RegADCRamp, 0x01, v)
}

// SetADCVolume sets the ADC digital volume, −95.5 … +32 dB in 0.5 dB steps (ALC maximum gain when the ALC is on).
func (d *ES8311Full) SetADCVolume(db float32) error {
	return d.w(es8311RegADCVolume, es8311VolumeToReg(db))
}

// ES8311ADCConfig configures the ADC digital path.
type ES8311ADCConfig struct {
	ScaleDB     uint8  // digital gain scale-up 0 … 42 dB in 6 dB steps
	OSR         uint16 // oversampling ratio in × fs (single speed 60–252, double speed 32–126)
	DoubleSpeed bool   // select the double-speed ADC
	HPFOff      bool   // true freezes the DC offset (default: dynamic high-pass filter)
	HPFCoeff1   uint8  // stage-1 high-pass coefficient 0–31
	HPFCoeff2   uint8  // stage-2 high-pass coefficient 0–31
}

// DefaultES8311ADCConfig returns the power-on defaults: 24 dB, OSR 64, single speed, HPF on, coefficients 0x0C.
func DefaultES8311ADCConfig() ES8311ADCConfig {
	return ES8311ADCConfig{ScaleDB: 24, OSR: 64, HPFCoeff1: 0x0C, HPFCoeff2: 0x0C}
}

// ConfigureADC configures the ADC digital path. An error is returned if OSR is out of range.
func (d *ES8311Full) ConfigureADC(cfg ES8311ADCConfig) error {
	code := int(cfg.OSR / 4)
	if cfg.DoubleSpeed {
		code = int(cfg.OSR / 2)
	}
	if code < 15 || code > 63 || (cfg.DoubleSpeed && code < 16) {
		return errors.New("ES8311: OSR out of range")
	}
	if err := d.update(es8311RegADCScale, 0x07, byte(es8311Clamp(es8311Round(float32(cfg.ScaleDB)/6), 0, 7))); err != nil {
		return err
	}
	c3 := byte(code)
	if cfg.DoubleSpeed {
		c3 |= 0x40
	}
	if err := d.w(es8311RegClk3, c3); err != nil {
		return err
	}
	hpf := byte(0x20)
	if cfg.HPFOff {
		hpf = 0
	}
	if err := d.update(es8311RegADCHPF2EQ, 0x3F, hpf|(cfg.HPFCoeff2&0x1F)); err != nil {
		return err
	}
	return d.update(es8311RegADCHPF1, 0x1F, cfg.HPFCoeff1&0x1F)
}

// SetADCRamp sets the ADC volume soft-ramp rate (0x15 bits 7:4): 0 = off, 1–15 = 0.25 dB per 2^(rate+1) LRCK.
func (d *ES8311Full) SetADCRamp(rate uint8) error {
	return d.update(es8311RegADCRamp, 0xF0, byte(es8311Clamp(int(rate), 0, 15))<<4)
}

// ES8311ALCConfig configures the automatic level control, noise gate and automute.
type ES8311ALCConfig struct {
	Enabled        bool
	MaxLevelDB     float32 // upper target level (nearest of 16 levels)
	MinLevelDB     float32 // lower target level
	Window         uint8   // ALC window size 0–15
	Automute       bool
	AutomuteWindow uint8   // 0–15 (2048 × (n+1) samples)
	NoiseGateDB    float32 // noise gate threshold (nearest of 16)
}

// DefaultES8311ALCConfig returns the datasheet defaults with the ALC disabled.
func DefaultES8311ALCConfig() ES8311ALCConfig {
	return ES8311ALCConfig{MaxLevelDB: -6.0, MinLevelDB: -30.1, NoiseGateDB: -96}
}

// ConfigureALC configures the automatic level control, noise gate and automute (0x18–0x1A).
func (d *ES8311Full) ConfigureALC(cfg ES8311ALCConfig) error {
	var a1 byte = cfg.Window & 0x0F
	if cfg.Enabled {
		a1 |= 0x80
	}
	if cfg.Automute {
		a1 |= 0x40
	}
	if err := d.w(es8311RegADCALC1, a1); err != nil {
		return err
	}
	if err := d.w(es8311RegADCALC2, es8311Nearest(&ES8311ALCLevelsDB, cfg.MaxLevelDB)<<4|es8311Nearest(&ES8311ALCLevelsDB, cfg.MinLevelDB)); err != nil {
		return err
	}
	return d.w(es8311RegADCAutomute, (cfg.AutomuteWindow&0x0F)<<4|es8311Nearest(&ES8311NoiseGateDB, cfg.NoiseGateDB))
}

func (d *ES8311Full) writeEQ(base byte, coefficients []uint32) error {
	data := make([]byte, 0, 4*len(coefficients))
	for _, c := range coefficients {
		if c >= 1<<30 {
			return errors.New("ES8311: equalizer coefficient must be a 30-bit value")
		}
		data = append(data, byte((c>>24)&0x3F), byte(c>>16), byte(c>>8), byte(c))
	}
	return d.connection.WriteReg(uint32(base), data)
}

// BypassADCEq bypasses the ADC equalizer (0x1C bit 6).
func (d *ES8311Full) BypassADCEq() error {
	return d.update(es8311RegADCHPF2EQ, 0x40, 0x40)
}

// SetADCEq loads the ADC equalizer coefficients (b0, a1, a2, b1, b2 as 30-bit values) and enables it.
func (d *ES8311Full) SetADCEq(coefficients [5]uint32) error {
	if err := d.writeEQ(es8311RegADCEQ, coefficients[:]); err != nil {
		return err
	}
	return d.update(es8311RegADCHPF2EQ, 0x40, 0x00)
}

// SetDACRamp sets the DAC volume / DRC soft-ramp rate (0x37 bits 7:4).
func (d *ES8311Full) SetDACRamp(rate uint8) error {
	return d.update(es8311RegDACRampEQ, 0xF0, byte(es8311Clamp(int(rate), 0, 15))<<4)
}

// ES8311DRCConfig configures the DAC dynamic range control.
type ES8311DRCConfig struct {
	Enabled    bool
	MaxLevelDB float32 // upper target level
	MinLevelDB float32 // lower target level
	Window     uint8   // window size 0–15
}

// ConfigureDRC configures the DAC dynamic range control (0x34, 0x35); the DAC volume becomes its maximum gain.
func (d *ES8311Full) ConfigureDRC(cfg ES8311DRCConfig) error {
	v := cfg.Window & 0x0F
	if cfg.Enabled {
		v |= 0x80
	}
	if err := d.w(es8311RegDACDRC1, v); err != nil {
		return err
	}
	return d.w(es8311RegDACDRC2, es8311Nearest(&ES8311ALCLevelsDB, cfg.MaxLevelDB)<<4|es8311Nearest(&ES8311ALCLevelsDB, cfg.MinLevelDB))
}

// BypassDACEq bypasses the DAC equalizer (0x37 bit 3).
func (d *ES8311Full) BypassDACEq() error {
	return d.update(es8311RegDACRampEQ, 0x08, 0x08)
}

// SetDACEq loads the DAC equalizer coefficients (b0, b1, a1 as 30-bit values) and enables it.
func (d *ES8311Full) SetDACEq(coefficients [3]uint32) error {
	if err := d.writeEQ(es8311RegDACEQ, coefficients[:]); err != nil {
		return err
	}
	return d.update(es8311RegDACRampEQ, 0x08, 0x00)
}

// SetDACMuteOptions sets the three DAC mute bits individually (0x31 bits 7:5).
func (d *ES8311Full) SetDACMuteOptions(dsm, dem, dsmTarget bool) error {
	var v byte
	if dsmTarget {
		v |= 0x80
	}
	if dsm {
		v |= 0x40
	}
	if dem {
		v |= 0x20
	}
	return d.update(es8311RegDACMute, 0xE0, v)
}

// SetLoopback feeds the ADC output to the DAC (0x44 bit 7).
func (d *ES8311Full) SetLoopback(enabled bool) error {
	v := byte(0)
	if enabled {
		v = 0x80
	}
	return d.update(es8311RegGPIO, 0x80, v)
}

// SetAdcdatMode selects the ADCDAT content (0x44 bits 6:4) with one of the ES8311Adcdat* constants.
func (d *ES8311Full) SetAdcdatMode(mode uint8) error {
	if mode > 6 || mode == 3 {
		return errors.New("ES8311: unknown ADCDAT mode")
	}
	return d.update(es8311RegGPIO, 0x70, mode<<4)
}

// SetSDPMute mutes the digital audio input and / or output (0x09 / 0x0A bit 6).
func (d *ES8311Full) SetSDPMute(input, output bool) error {
	var in, out byte
	if input {
		in = 0x40
	}
	if output {
		out = 0x40
	}
	if err := d.update(es8311RegSdpIn, 0x40, in); err != nil {
		return err
	}
	return d.update(es8311RegSdpOut, 0x40, out)
}

// ReadRegister performs a raw register read.
func (d *ES8311Full) ReadRegister(reg uint8) (uint8, error) {
	return d.r(reg)
}

// WriteRegister performs a raw register write.
func (d *ES8311Full) WriteRegister(reg, value uint8) error {
	return d.w(reg, value)
}
