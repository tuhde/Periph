// Package comms contains drivers for Wireless and wired communication modules (FM tuner, etc.).
package comms

import (
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// Band select constants.
const (
	BandUSEurope     uint8 = 0
	BandJapan        uint8 = 1
	BandWorld        uint8 = 2
	BandEastEurope   uint8 = 3
)

// Channel spacing constants.
const (
	Space100K uint8 = 0
	Space200K uint8 = 1
	Space50K  uint8 = 2
	Space25K  uint8 = 3
)

var bandBaseKHz = [4]uint32{87000, 76000, 76000, 65000}
var spaceKHz = [4]uint32{100, 200, 50, 25}

// CTRL register bit masks.
const (
	rdaDHIZ       uint16 = 0x8000
	rdaDMUTE      uint16 = 0x4000
	rdaMONO       uint16 = 0x2000
	rdaBASS       uint16 = 0x1000
	rdaSEEKUP     uint16 = 0x0200
	rdaSEEK       uint16 = 0x0100
	rdaSKMODE     uint16 = 0x0080
	rdaRDS_EN     uint16 = 0x0008
	rdaNEW_METHOD uint16 = 0x0004
	rdaSOFT_RESET uint16 = 0x0002
	rdaENABLE     uint16 = 0x0001
)

// CHAN register bits.
const rdaTUNE uint16 = 0x0010

// R4 register bits.
const (
	rdaDE         uint16 = 0x0800
	rdaSOFTMUTE   uint16 = 0x0200
	rdaAFCD       uint16 = 0x0100
)

// R5 register bits.
const rdaINT_MODE uint16 = 0x8000

// R7 register bits.
const rdaBAND_65M_50M uint16 = 0x0200

// STATUSA bit masks.
const (
	rdaRDSR uint16 = 0x8000
	rdaSTC  uint16 = 0x4000
	rdaSF   uint16 = 0x2000
	rdaST   uint16 = 0x0400
)

// STATUSB bit masks.
const (
	rdaFM_TRUE  uint16 = 0x0100
	rdaFM_READY uint16 = 0x0080
)

// RDA5807M fixed 7-bit I²C address.
const Rda5807mAddr uint8 = 0x10

// Undocumented, measured on real hardware: after standby wake-up or a soft
// reset, the chip needs this long before it will lock onto a subsequent TUNE
// (FM_READY otherwise never asserts, even after minutes). Same requirement as
// the datasheet's power-up sequencing, just not called out for these two cases.
const rdaResetRecoveryMs = 250

// Undocumented, measured on real hardware: FM_READY lags STC by up to ~20 ms
// after any register write.
const rdaReadySettleMs = 30

// rdaSTC timeout in milliseconds.
const rdaStcTimeoutMs = 500

// Rda5807mMinimal is the single-chip FM stereo radio tuner driver — minimal
// interface.
//
// Tunes to a station, adjusts volume, mutes, and seeks the next station. No
// configuration required beyond the connection.
//
// Unlike most chips in this project, the RDA5807M has no register-pointer
// byte: writes always start at the fixed register 0x02 and reads always
// start at the fixed register 0x0A. The driver keeps an in-memory shadow of
// registers 0x02-0x07 (6 big-endian 16-bit words) and rewrites all of them
// on every change, since the chip cannot be told to start a write anywhere
// else.
//
// Fixed I²C address: 0x10.
type Rda5807mMinimal struct {
	connection       connection.Connection
	regs            [6]uint16
	band            uint8
	space           uint8
	eastEurope50m   bool
	currentFreqMhz  float64
}

// NewRda5807mMinimal creates a new Rda5807mMinimal and tunes to the initial
// frequency.
//
// connection must be a configured I²C connection bound to address 0x10.
// frequencyMhz is the initial frequency in MHz.
// volume is the initial volume, 0 (mute) to 15 (max).
func NewRda5807mMinimal(t connection.Connection, frequencyMhz float64, volume uint8) (*Rda5807mMinimal, error) {
	d := &Rda5807mMinimal{
		connection:     t,
		band:          BandWorld,
		space:         Space100K,
		eastEurope50m: false,
		currentFreqMhz: frequencyMhz,
	}

	ctrl := rdaDHIZ | rdaDMUTE | rdaSKMODE | rdaNEW_METHOD | rdaENABLE
	channel := freqToChan(d.band, d.space, d.eastEurope50m, frequencyMhz)
	chanReg := (channel << 6) | rdaTUNE | (uint16(d.band) << 2) | uint16(d.space)
	r4 := rdaSOFTMUTE | rdaDE
	r5 := rdaINT_MODE | (8 << 8) | uint16(volume&0x0F)
	r6 := uint16(0x0000)
	r7 := (16 << 10) | rdaBAND_65M_50M | 0x0002

	d.regs = [6]uint16{ctrl, chanReg, r4, r5, r6, r7}

	if err := d.writeRegs(); err != nil {
		return nil, err
	}
	if _, err := d.waitStc(); err != nil {
		return nil, err
	}
	d.regs[1] &^= rdaTUNE
	return d, nil
}

// freqToChan converts a frequency in MHz to a 10-bit channel number relative
// to the band's base frequency and channel spacing.
func freqToChan(band, space uint8, eastEurope50m bool, frequencyMhz float64) uint16 {
	base := bandBaseKHz[band]
	if band == BandEastEurope && eastEurope50m {
		base = 50000
	}
	freqKhz := int64(frequencyMhz*1000.0 + 0.5)
	channel := int64((freqKhz - int64(base)) / int64(spaceKHz[space]))
	if channel < 0 {
		channel = 0
	}
	if channel > 1023 {
		channel = 1023
	}
	return uint16(channel)
}

// chanToFreq converts a 10-bit channel number back to a frequency in MHz.
func chanToFreq(band, space uint8, eastEurope50m bool, channel uint16) float64 {
	base := bandBaseKHz[band]
	if band == BandEastEurope && eastEurope50m {
		base = 50000
	}
	return (float64(base) + float64(channel)*float64(spaceKHz[space])) / 1000.0
}

// writeRegs writes all six 16-bit registers 0x02-0x07 (12 bytes, big-endian).
func (d *Rda5807mMinimal) writeRegs() error {
	buf := make([]byte, 12)
	for i := 0; i < 6; i++ {
		buf[i*2] = byte(d.regs[i] >> 8)
		buf[i*2+1] = byte(d.regs[i] & 0xFF)
	}
	return d.connection.Write(buf)
}

// readStatus reads n/2 16-bit words (n bytes) from the chip's read-only
// register block starting at 0x0A.
func (d *Rda5807mMinimal) readStatus(n int) ([]uint16, error) {
	buf, err := d.connection.Read(n)
	if err != nil {
		return nil, err
	}
	words := make([]uint16, n/2)
	for i := range words {
		words[i] = (uint16(buf[i*2]) << 8) | uint16(buf[i*2+1])
	}
	return words, nil
}

// waitStc polls STATUSA until STC is set or the timeout elapses. Returns the
// last STATUSA word (zero on timeout).
func (d *Rda5807mMinimal) waitStc() (uint16, error) {
	deadline := time.Now().Add(time.Duration(rdaStcTimeoutMs) * time.Millisecond)
	for {
		words, err := d.readStatus(2)
		if err != nil {
			return 0, err
		}
		if words[0]&rdaSTC != 0 {
			return words[0], nil
		}
		if time.Now().After(deadline) {
			return 0, nil
		}
		time.Sleep(time.Millisecond)
	}
}

// SetFrequency tunes to a frequency, blocking until the tune completes.
func (d *Rda5807mMinimal) SetFrequency(frequencyMhz float64) error {
	channel := freqToChan(d.band, d.space, d.eastEurope50m, frequencyMhz)
	d.regs[1] = (channel << 6) | rdaTUNE | (uint16(d.band) << 2) | uint16(d.space)
	d.currentFreqMhz = frequencyMhz
	if err := d.writeRegs(); err != nil {
		return err
	}
	if _, err := d.waitStc(); err != nil {
		return err
	}
	d.regs[1] &^= rdaTUNE
	return nil
}

// Frequency reads the currently tuned frequency in MHz, derived from READCHAN.
func (d *Rda5807mMinimal) Frequency() (float64, error) {
	words, err := d.readStatus(2)
	if err != nil {
		return 0, err
	}
	readchan := words[0] & 0x03FF
	return chanToFreq(d.band, d.space, d.eastEurope50m, readchan), nil
}

// SetVolume sets the output volume (0 = mute to 15 = max, logarithmic).
func (d *Rda5807mMinimal) SetVolume(level uint8) error {
	d.regs[3] = (d.regs[3] &^ 0x000F) | uint16(level&0x0F)
	return d.writeRegs()
}

// Mute mutes (enable=true) or unmutes (enable=false) the audio output.
func (d *Rda5807mMinimal) Mute(enable bool) error {
	if enable {
		d.regs[0] &^= rdaDMUTE
	} else {
		d.regs[0] |= rdaDMUTE
	}
	return d.writeRegs()
}

// Seek seeks to the next station, blocking until the seek completes.
//
// up=true to seek upward, false to seek downward. Returns the new frequency
// in MHz, or nil if the seek failed (SF flag set).
func (d *Rda5807mMinimal) Seek(up bool) (*float64, error) {
	if up {
		d.regs[0] |= rdaSEEKUP
	} else {
		d.regs[0] &^= rdaSEEKUP
	}
	d.regs[0] |= rdaSEEK
	if err := d.writeRegs(); err != nil {
		return nil, err
	}
	statusA, err := d.waitStc()
	if err != nil {
		return nil, err
	}
	d.regs[0] &^= rdaSEEK
	if err := d.writeRegs(); err != nil {
		return nil, err
	}

	if statusA&rdaSF != 0 {
		return nil, nil
	}
	readchan := statusA & 0x03FF
	freq := chanToFreq(d.band, d.space, d.eastEurope50m, readchan)
	d.currentFreqMhz = freq
	return &freq, nil
}

// Rda5807mFull is the single-chip FM stereo radio tuner driver — full
// interface. Extends Rda5807mMinimal with band/spacing configuration, RDS,
// status, and power management.
//
// Embeds Rda5807mMinimal to inherit SetFrequency, Frequency, SetVolume,
// Mute, Seek, and the constructor.
type Rda5807mFull struct {
	*Rda5807mMinimal
}

// NewRda5807mFull creates a new Rda5807mFull and tunes to the initial frequency.
func NewRda5807mFull(t connection.Connection, frequencyMhz float64, volume uint8) (*Rda5807mFull, error) {
	m, err := NewRda5807mMinimal(t, frequencyMhz, volume)
	if err != nil {
		return nil, err
	}
	return &Rda5807mFull{Rda5807mMinimal: m}, nil
}

// Configure re-tunes the chip. Pass nil for any parameter to leave it
// unchanged. Changing band, space, or eastEurope50m re-tunes to the current
// frequency, since CHAN's meaning depends on all three.
func (d *Rda5807mFull) Configure(
	band, space *uint8,
	deEmphasis, seekMode, afcDisable, eastEurope50m *bool,
	seekThreshold, clkMode *uint8,
) error {
	retune := false
	currentFreq, err := d.Frequency()
	if err != nil {
		return err
	}

	if band != nil && *band != d.band {
		d.band = *band
		retune = true
	}
	if space != nil && *space != d.space {
		d.space = *space
		retune = true
	}
	if eastEurope50m != nil && *eastEurope50m != d.eastEurope50m {
		d.eastEurope50m = *eastEurope50m
		retune = true
	}

	d.regs[1] = (d.regs[1] &^ 0x000F) | (uint16(d.band) << 2) | uint16(d.space)

	if deEmphasis != nil {
		if *deEmphasis {
			d.regs[2] |= rdaDE
		} else {
			d.regs[2] &^= rdaDE
		}
	}
	if afcDisable != nil {
		if *afcDisable {
			d.regs[2] |= rdaAFCD
		} else {
			d.regs[2] &^= rdaAFCD
		}
	}
	if seekThreshold != nil {
		d.regs[3] = (d.regs[3] &^ 0x0F00) | (uint16(*seekThreshold&0x0F) << 8)
	}
	if seekMode != nil {
		if *seekMode {
			d.regs[0] |= rdaSKMODE
		} else {
			d.regs[0] &^= rdaSKMODE
		}
	}
	if clkMode != nil {
		d.regs[0] = (d.regs[0] &^ 0x0070) | (uint16(*clkMode&0x07) << 4)
	}
	if eastEurope50m != nil {
		if *eastEurope50m {
			d.regs[5] &^= rdaBAND_65M_50M
		} else {
			d.regs[5] |= rdaBAND_65M_50M
		}
	}

	if retune {
		return d.SetFrequency(currentFreq)
	}
	return d.writeRegs()
}

// SetBassBoost enables or disables bass boost.
func (d *Rda5807mFull) SetBassBoost(enable bool) error {
	if enable {
		d.regs[0] |= rdaBASS
	} else {
		d.regs[0] &^= rdaBASS
	}
	return d.writeRegs()
}

// SetMono forces mono (enable=true) or allows stereo.
func (d *Rda5807mFull) SetMono(enable bool) error {
	if enable {
		d.regs[0] |= rdaMONO
	} else {
		d.regs[0] &^= rdaMONO
	}
	return d.writeRegs()
}

// SetSoftmute enables or disables soft mute (chip default: enabled).
func (d *Rda5807mFull) SetSoftmute(enable bool) error {
	if enable {
		d.regs[2] |= rdaSOFTMUTE
	} else {
		d.regs[2] &^= rdaSOFTMUTE
	}
	return d.writeRegs()
}

// EnableRds enables or disables the RDS/RBDS decoder.
func (d *Rda5807mFull) EnableRds(enable bool) error {
	if enable {
		d.regs[0] |= rdaRDS_EN
	} else {
		d.regs[0] &^= rdaRDS_EN
	}
	return d.writeRegs()
}

// RdsReady returns true if a new RDS/RBDS group is available (RDSR flag).
func (d *Rda5807mFull) RdsReady() (bool, error) {
	words, err := d.readStatus(2)
	if err != nil {
		return false, err
	}
	return words[0]&rdaRDSR != 0, nil
}

// ReadRdsGroup reads the four raw RDS/RBDS blocks (A–D), if a new group is
// ready. Returns nil if no new group is ready. Does not decode group content.
func (d *Rda5807mFull) ReadRdsGroup() (*[4]uint16, error) {
	words, err := d.readStatus(12)
	if err != nil {
		return nil, err
	}
	if words[0]&rdaRDSR == 0 {
		return nil, nil
	}
	return &[4]uint16{words[2], words[3], words[4], words[5]}, nil
}

// IsStereo returns true if the current station is being received in stereo.
func (d *Rda5807mFull) IsStereo() (bool, error) {
	words, err := d.readStatus(2)
	if err != nil {
		return false, err
	}
	return words[0]&rdaST != 0, nil
}

// IsStation returns true if the current channel is a real station (FM_TRUE).
func (d *Rda5807mFull) IsStation() (bool, error) {
	words, err := d.readStatus(4)
	if err != nil {
		return false, err
	}
	return words[1]&rdaFM_TRUE != 0, nil
}

// IsReady returns true if the tuner is ready (FM_READY).
func (d *Rda5807mFull) IsReady() (bool, error) {
	words, err := d.readStatus(4)
	if err != nil {
		return false, err
	}
	return words[1]&rdaFM_READY != 0, nil
}

// SignalStrength returns the raw RSSI, 0 (weakest) to 127 (strongest),
// logarithmic. No absolute dBuV mapping is published.
func (d *Rda5807mFull) SignalStrength() (uint8, error) {
	words, err := d.readStatus(4)
	if err != nil {
		return 0, err
	}
	return uint8((words[1] >> 9) & 0x7F), nil
}

// Standby powers the chip down (enable=true) or up (enable=false).
//
// Powering back up clears the tuner's PLL lock, so waking from standby
// blocks briefly for the chip to recover, then re-tunes to the last known
// frequency.
func (d *Rda5807mFull) Standby(enable bool) error {
	if enable {
		d.regs[0] &^= rdaENABLE
	} else {
		d.regs[0] |= rdaENABLE
	}
	if err := d.writeRegs(); err != nil {
		return err
	}
	if !enable {
		time.Sleep(time.Duration(rdaResetRecoveryMs) * time.Millisecond)
		if err := d.SetFrequency(d.currentFreqMhz); err != nil {
			return err
		}
		time.Sleep(time.Duration(rdaReadySettleMs) * time.Millisecond)
	}
	return nil
}

// SoftReset pulses the soft-reset bit, then re-applies the current configuration.
//
// A soft reset restores the chip's power-on register defaults and clears the
// tuner's PLL lock, so this blocks briefly for the chip to recover, then
// re-tunes to the last known frequency.
func (d *Rda5807mFull) SoftReset() error {
	d.regs[0] |= rdaSOFT_RESET
	if err := d.writeRegs(); err != nil {
		return err
	}
	d.regs[0] &^= rdaSOFT_RESET
	if err := d.writeRegs(); err != nil {
		return err
	}
	time.Sleep(time.Duration(rdaResetRecoveryMs) * time.Millisecond)
	if err := d.SetFrequency(d.currentFreqMhz); err != nil {
		return err
	}
	time.Sleep(time.Duration(rdaReadySettleMs) * time.Millisecond)
	return nil
}

