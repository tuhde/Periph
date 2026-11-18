package led

import (
	"errors"
	"sync"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// IS31FL3731 register map constants.
const (
	is31fl3731RegCommand  uint32 = 0xFD
	is31fl3731PageFunc    uint8  = 0x0B
	is31fl3731RegCtrl     uint32 = 0x00
	is31fl3731RegBlink    uint32 = 0x12
	is31fl3731RegPWM      uint32 = 0x24
	is31fl3731FnConfig    uint32 = 0x00
	is31fl3731FnAutoplay1 uint32 = 0x02
	is31fl3731FnDisplay   uint32 = 0x05
	is31fl3731FnAudioSync uint32 = 0x06
	is31fl3731FnState     uint32 = 0x07
	is31fl3731FnBreath1   uint32 = 0x08
	is31fl3731FnShutdown  uint32 = 0x0A
	is31fl3731FnAGC       uint32 = 0x0B
	is31fl3731FnAudioRate uint32 = 0x0C
)

// Geometry of the matrix.
const (
	// Is31fl3731Rows is the number of common lines (rows).
	Is31fl3731Rows = 9
	// Is31fl3731Cols is the number of sink lines (columns).
	Is31fl3731Cols = 16
	// Is31fl3731IntFlag is the movie-finished bit of FRAME_STATE.
	Is31fl3731IntFlag uint8 = 0x10
)

// Is31fl3731Gamma32 is the 32-step gamma table for 256 PWM steps (datasheet Table 17).
var Is31fl3731Gamma32 = [32]uint8{
	0, 1, 2, 4, 6, 10, 13, 18, 22, 28, 33, 39, 46, 53, 61, 69,
	78, 86, 96, 106, 116, 126, 138, 149, 161, 173, 186, 199, 212, 226, 240, 255,
}

// Is31fl3731Gamma64 is the 64-step gamma table for 256 PWM steps (datasheet Table 18, recommended).
var Is31fl3731Gamma64 = [64]uint8{
	0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22,
	24, 26, 29, 32, 35, 38, 41, 44, 47, 50, 53, 57, 61, 65, 69, 73,
	77, 81, 85, 89, 94, 99, 104, 109, 114, 119, 124, 129, 134, 140, 146, 152,
	158, 164, 170, 176, 182, 188, 195, 202, 209, 216, 223, 230, 237, 244, 251, 255,
}

// ErrIs31fl3731Range is returned when an argument is outside its documented range.
var ErrIs31fl3731Range = errors.New("is31fl3731: argument out of range")

// Is31fl3731Minimal is the 144-LED matrix driver (CharliePlex) — minimal interface.
//
// Lights individual LEDs of the 9 x 16 matrix with 8-bit PWM in Picture mode,
// drawing into frame 1. The I²C address is 0x74–0x77 (AD pin).
//
// Defaults baked in at construction: software shutdown while configuring,
// Picture mode showing frame 1, frame 1 initialised (all LED-control bits on,
// PWM and blink cleared), then normal operation; LED current is set by R_EXT
// (3.2 mA at 20 kΩ). The driver keeps a shadow copy of every frame because the
// chip only allows frame register reads in software shutdown. Bus access is
// serialised by an internal mutex.
type Is31fl3731Minimal struct {
	conn  connection.RegisterConnection
	busMu sync.Mutex
	page  int
	frame uint8
	ic    uint8
	be    uint8
	bpt   uint8
	pwm   [8][144]byte
	ctrl  [8][18]byte
	blink [8][18]byte
}

// NewIs31fl3731Minimal creates the driver and runs the initialization sequence.
//
// c must be a configured I²C register connection bound to the chip's 7-bit address.
func NewIs31fl3731Minimal(c connection.RegisterConnection) (*Is31fl3731Minimal, error) {
	d := &Is31fl3731Minimal{conn: c, page: -1}
	for f := range d.ctrl {
		for i := range d.ctrl[f] {
			d.ctrl[f][i] = 0xFF
		}
	}
	steps := []func() error{
		func() error { return d.funcWrite(is31fl3731FnShutdown, 0x00) },
		func() error { return d.funcWrite(is31fl3731FnConfig, 0x00, 0x00) },
		func() error { return d.funcWrite(is31fl3731FnDisplay, 0x00) },
		func() error { return d.initFrame(0) },
		func() error { return d.funcWrite(is31fl3731FnShutdown, 0x01) },
	}
	for _, s := range steps {
		if err := s(); err != nil {
			return nil, err
		}
	}
	return d, nil
}

func (d *Is31fl3731Minimal) selectPage(page uint8) error {
	if d.page != int(page) {
		if err := d.conn.WriteReg(is31fl3731RegCommand, []byte{page}); err != nil {
			return err
		}
		d.page = int(page)
	}
	return nil
}

func (d *Is31fl3731Minimal) funcWrite(reg uint32, data ...byte) error {
	if err := d.selectPage(is31fl3731PageFunc); err != nil {
		return err
	}
	return d.conn.WriteReg(reg, data)
}

func (d *Is31fl3731Minimal) funcRead(reg uint32) (uint8, error) {
	if err := d.selectPage(is31fl3731PageFunc); err != nil {
		return 0, err
	}
	b, err := d.conn.ReadReg(reg, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

func (d *Is31fl3731Minimal) initFrame(frame uint8) error {
	d.ctrl[frame] = [18]byte{}
	for i := range d.ctrl[frame] {
		d.ctrl[frame][i] = 0xFF
	}
	d.blink[frame] = [18]byte{}
	d.pwm[frame] = [144]byte{}
	if err := d.selectPage(frame); err != nil {
		return err
	}
	if err := d.conn.WriteReg(is31fl3731RegCtrl, d.ctrl[frame][:]); err != nil {
		return err
	}
	if err := d.conn.WriteReg(is31fl3731RegBlink, d.blink[frame][:]); err != nil {
		return err
	}
	return d.conn.WriteReg(is31fl3731RegPWM, d.pwm[frame][:])
}

// SetPixel sets the PWM brightness (0–255) of one LED in the frame being drawn.
//
// row is 0–8 and col 0–15; otherwise ErrIs31fl3731Range is returned.
func (d *Is31fl3731Minimal) SetPixel(row, col, brightness uint8) error {
	if row > 8 || col > 15 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	idx := 16*int(row) + int(col)
	d.pwm[d.frame][idx] = brightness
	if err := d.selectPage(d.frame); err != nil {
		return err
	}
	return d.conn.WriteReg(is31fl3731RegPWM+uint32(idx), []byte{brightness})
}

// Clear sets all 144 PWM values of the frame being drawn to 0 (one block write).
func (d *Is31fl3731Minimal) Clear() error { return d.Fill(0) }

// Fill sets all 144 PWM values of the frame being drawn (one block write).
func (d *Is31fl3731Minimal) Fill(brightness uint8) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	for i := range d.pwm[d.frame] {
		d.pwm[d.frame][i] = brightness
	}
	if err := d.selectPage(d.frame); err != nil {
		return err
	}
	return d.conn.WriteReg(is31fl3731RegPWM, d.pwm[d.frame][:])
}

// Is31fl3731Full is the full interface — frames, auto-play, blink, breath,
// audio, shutdown and the INTB interrupt. It embeds Is31fl3731Minimal, so
// SetPixel, Clear and Fill are promoted.
type Is31fl3731Full struct {
	Is31fl3731Minimal

	mu          sync.Mutex
	callback    func(status uint8)
	unsubscribe func()
	stopPoll    chan struct{}
}

// NewIs31fl3731Full creates the full driver and runs the initialization sequence.
func NewIs31fl3731Full(c connection.RegisterConnection) (*Is31fl3731Full, error) {
	m, err := NewIs31fl3731Minimal(c)
	if err != nil {
		return nil, err
	}
	// The embedded Minimal is copied by value; it carries a mutex that has not been locked yet.
	f := &Is31fl3731Full{}
	f.Is31fl3731Minimal.conn = m.conn
	f.Is31fl3731Minimal.page = m.page
	f.Is31fl3731Minimal.frame = m.frame
	f.Is31fl3731Minimal.pwm = m.pwm
	f.Is31fl3731Minimal.ctrl = m.ctrl
	f.Is31fl3731Minimal.blink = m.blink
	return f, nil
}

// GetPixel reads the PWM value of one LED from the shadow copy of the frame being drawn.
func (d *Is31fl3731Full) GetPixel(row, col uint8) (uint8, error) {
	if row > 8 || col > 15 {
		return 0, ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.pwm[d.frame][16*int(row)+int(col)], nil
}

// SetPixels writes a whole frame of 144 PWM values, row-major (one block write).
func (d *Is31fl3731Full) SetPixels(data []byte) error {
	if len(data) != 144 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	copy(d.pwm[d.frame][:], data)
	if err := d.selectPage(d.frame); err != nil {
		return err
	}
	return d.conn.WriteReg(is31fl3731RegPWM, data)
}

func (d *Is31fl3731Full) setBit(cache *[8][18]byte, base uint32, row, col uint8, on bool) error {
	if row > 8 || col > 15 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	idx := 2*int(row) + int(col>>3)
	mask := byte(1) << (col & 7)
	if on {
		cache[d.frame][idx] |= mask
	} else {
		cache[d.frame][idx] &^= mask
	}
	if err := d.selectPage(d.frame); err != nil {
		return err
	}
	return d.conn.WriteReg(base+uint32(idx), []byte{cache[d.frame][idx]})
}

// SetLEDEnabled sets the LED-control (on/off) bit of one LED in the frame being drawn.
func (d *Is31fl3731Full) SetLEDEnabled(row, col uint8, on bool) error {
	return d.setBit(&d.ctrl, is31fl3731RegCtrl, row, col, on)
}

// SetBlink sets the blink-enable bit of one LED in the frame being drawn (needs EnableBlink).
func (d *Is31fl3731Full) SetBlink(row, col uint8, on bool) error {
	return d.setBit(&d.blink, is31fl3731RegBlink, row, col, on)
}

// SetBlinkAll sets all 18 blink-control bytes of the frame being drawn.
func (d *Is31fl3731Full) SetBlinkAll(on bool) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	v := byte(0)
	if on {
		v = 0xFF
	}
	for i := range d.blink[d.frame] {
		d.blink[d.frame][i] = v
	}
	if err := d.selectPage(d.frame); err != nil {
		return err
	}
	return d.conn.WriteReg(is31fl3731RegBlink, d.blink[d.frame][:])
}

func (d *Is31fl3731Full) writeDisplay() error {
	return d.funcWrite(is31fl3731FnDisplay, d.ic<<5|d.be<<3|d.bpt)
}

// EnableBlink enables or disables blinking (50 % duty); period is 0–7 in units of 0.27 s.
func (d *Is31fl3731Full) EnableBlink(enabled bool, period uint8) error {
	if period > 7 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	d.be = 0
	if enabled {
		d.be = 1
	}
	d.bpt = period
	return d.writeDisplay()
}

// SelectFrame selects the frame (0–7) subsequent drawing calls write to; the display is unchanged.
func (d *Is31fl3731Full) SelectFrame(frame uint8) error {
	if frame > 7 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	d.frame = frame
	return d.selectPage(frame)
}

// ShowFrame shows a frame (0–7) in Picture mode.
func (d *Is31fl3731Full) ShowFrame(frame uint8) error {
	if frame > 7 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.funcWrite(is31fl3731FnConfig, 0x00, frame)
}

// ClearFrame initialises a frame: LED-control 0xFF, PWM and blink 0.
func (d *Is31fl3731Full) ClearFrame(frame uint8) error {
	if frame > 7 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.initFrame(frame)
}

// ClearAllFrames initialises all eight frames.
func (d *Is31fl3731Full) ClearAllFrames() error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	for f := uint8(0); f < 8; f++ {
		if err := d.initFrame(f); err != nil {
			return err
		}
	}
	return nil
}

// CopyFrame copies the PWM values of frame src to frame dst.
func (d *Is31fl3731Full) CopyFrame(src, dst uint8) error {
	if src > 7 || dst > 7 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	d.pwm[dst] = d.pwm[src]
	if err := d.selectPage(dst); err != nil {
		return err
	}
	return d.conn.WriteReg(is31fl3731RegPWM, d.pwm[dst][:])
}

// SetPictureMode switches to Picture mode (shows the frame selected by ShowFrame).
func (d *Is31fl3731Full) SetPictureMode() error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.funcWrite(is31fl3731FnConfig, 0x00)
}

// StartAutoPlay starts Auto Frame Play.
//
// frames is 1–7 (0 = all eight), loops 1–7 (0 = endless), delayMs is rounded to
// a multiple of 11 ms (11–704) and startFrame is 0–7.
func (d *Is31fl3731Full) StartAutoPlay(frames, loops uint8, delayMs uint16, startFrame uint8) error {
	a := (delayMs + 5) / 11
	if frames > 7 || loops > 7 || startFrame > 7 || a < 1 || a > 64 {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	if err := d.funcWrite(is31fl3731FnAutoplay1, loops<<4|frames, byte(a&0x3F)); err != nil {
		return err
	}
	return d.funcWrite(is31fl3731FnConfig, 0x08|startFrame)
}

// StopAutoPlay stops Auto Frame Play and keeps showing the current frame (Picture
// mode). Reading the current frame clears the movie-finished flag.
func (d *Is31fl3731Full) StopAutoPlay() error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	st, err := d.funcRead(is31fl3731FnState)
	if err != nil {
		return err
	}
	return d.funcWrite(is31fl3731FnConfig, 0x00, st&0x07)
}

// SetAudioMode switches Audio Frame Play on, or back to Picture mode.
func (d *Is31fl3731Full) SetAudioMode(enabled bool) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	if enabled {
		return d.funcWrite(is31fl3731FnConfig, 0x10)
	}
	return d.funcWrite(is31fl3731FnConfig, 0x00)
}

// SetAudioSync lets the audio input modulate the matrix intensity.
func (d *Is31fl3731Full) SetAudioSync(enabled bool) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	if enabled {
		return d.funcWrite(is31fl3731FnAudioSync, 1)
	}
	return d.funcWrite(is31fl3731FnAudioSync, 0)
}

// SetAudioGain configures the audio gain: gainDB 0, 3, … 21, optional AGC (slow or fast).
func (d *Is31fl3731Full) SetAudioGain(gainDB uint8, agc, fast bool) error {
	if gainDB > 21 || gainDB%3 != 0 {
		return ErrIs31fl3731Range
	}
	v := gainDB / 3
	if agc {
		v |= 1 << 3
	}
	if fast {
		v |= 1 << 4
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.funcWrite(is31fl3731FnAGC, v)
}

// SetAudioRate sets the audio ADC sample interval (46 µs × a, 0 = 46 µs × 256).
func (d *Is31fl3731Full) SetAudioRate(a uint8) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.funcWrite(is31fl3731FnAudioRate, a)
}

// SetBreath configures the breath function; exponents 0–7 (fade 26 ms × 2ⁿ, extinguish 3.5 ms × 2ⁿ).
func (d *Is31fl3731Full) SetBreath(enabled bool, fadeIn, fadeOut, extinguish uint8) error {
	if fadeIn > 7 || fadeOut > 7 || extinguish > 7 {
		return ErrIs31fl3731Range
	}
	b2 := extinguish
	if enabled {
		b2 |= 1 << 4
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.funcWrite(is31fl3731FnBreath1, fadeOut<<4|fadeIn, b2)
}

// SetIntensityControl chooses whether frame 1's PWM values apply to all frames.
func (d *Is31fl3731Full) SetIntensityControl(useFrame1 bool) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	d.ic = 0
	if useFrame1 {
		d.ic = 1
	}
	return d.writeDisplay()
}

// Shutdown enters (true) or leaves (false) software shutdown; frame registers are kept.
func (d *Is31fl3731Full) Shutdown(on bool) error {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	if on {
		return d.funcWrite(is31fl3731FnShutdown, 0)
	}
	return d.funcWrite(is31fl3731FnShutdown, 1)
}

// FrameState reads FRAME_STATE (bit 4 = movie finished, bits 2:0 = current frame); clears the flag.
func (d *Is31fl3731Full) FrameState() (uint8, error) {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	return d.funcRead(is31fl3731FnState)
}

// CurrentFrame returns the frame being displayed, 0–7.
func (d *Is31fl3731Full) CurrentFrame() (uint8, error) {
	st, err := d.FrameState()
	return st & 0x07, err
}

// MovieFinished reports whether an Auto Frame Play movie has finished (clears the flag).
func (d *Is31fl3731Full) MovieFinished() (bool, error) {
	st, err := d.FrameState()
	return st&Is31fl3731IntFlag != 0, err
}

// restore re-selects the page that was active before a function-page access.
func (d *Is31fl3731Full) restore(prev int) error {
	if prev >= 0 && prev != d.page {
		return d.selectPage(uint8(prev))
	}
	return nil
}

// ReadFunctionRegister reads a function-page register (0–0x0C) and restores the selected page.
func (d *Is31fl3731Full) ReadFunctionRegister(reg uint8) (uint8, error) {
	if reg > 0x0C {
		return 0, ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	prev := d.page
	v, err := d.funcRead(uint32(reg))
	if err != nil {
		return 0, err
	}
	return v, d.restore(prev)
}

// WriteFunctionRegister writes a function-page register (0–0x0C) and restores the selected page.
func (d *Is31fl3731Full) WriteFunctionRegister(reg, value uint8) error {
	if reg > 0x0C {
		return ErrIs31fl3731Range
	}
	d.busMu.Lock()
	defer d.busMu.Unlock()
	prev := d.page
	if err := d.funcWrite(uint32(reg), value); err != nil {
		return err
	}
	return d.restore(prev)
}

// PollInterrupt reads FRAME_STATE (clears INT) and restores the selected page.
func (d *Is31fl3731Full) PollInterrupt() (uint8, error) {
	d.busMu.Lock()
	defer d.busMu.Unlock()
	prev := d.page
	v, err := d.funcRead(is31fl3731FnState)
	if err != nil {
		return 0, err
	}
	return v, d.restore(prev)
}

// OnInterrupt subscribes callback to the end of an Auto Frame Play movie. The
// connection's InputPin is used if wired (falling edge of INTB), otherwise a
// 50 ms polling goroutine. The callback receives FRAME_STATE.
func (d *Is31fl3731Full) OnInterrupt(callback func(status uint8)) error {
	if err := d.OffInterrupt(); err != nil {
		return err
	}
	d.mu.Lock()
	d.callback = callback
	d.mu.Unlock()

	if pin := d.conn.IntPin(); pin != nil {
		unsub := pin.OnEdge(connection.Falling, func() { d.service(true) })
		d.mu.Lock()
		d.unsubscribe = unsub
		d.mu.Unlock()
		return nil
	}
	stop := make(chan struct{})
	d.mu.Lock()
	d.stopPoll = stop
	d.mu.Unlock()
	go func() {
		for {
			select {
			case <-stop:
				return
			default:
			}
			d.service(false)
			time.Sleep(50 * time.Millisecond)
		}
	}()
	return nil
}

// OffInterrupt unsubscribes interrupt delivery.
func (d *Is31fl3731Full) OffInterrupt() error {
	d.mu.Lock()
	unsub := d.unsubscribe
	stop := d.stopPoll
	d.unsubscribe = nil
	d.stopPoll = nil
	d.callback = nil
	d.mu.Unlock()
	if unsub != nil {
		unsub()
	}
	if stop != nil {
		close(stop)
	}
	return nil
}

func (d *Is31fl3731Full) service(always bool) {
	status, err := d.PollInterrupt()
	if err != nil || (!always && status&Is31fl3731IntFlag == 0) {
		return
	}
	d.mu.Lock()
	cb := d.callback
	d.mu.Unlock()
	if cb != nil {
		cb(status)
	}
}
