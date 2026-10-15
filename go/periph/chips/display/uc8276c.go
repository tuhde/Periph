package display

import (
	"errors"
	"fmt"
	"time"

	"github.com/tuhde/Periph/go/periph/connection"
)

// UC8276C colours.
const (
	UC8276CBlack uint8 = 0
	UC8276CWhite uint8 = 1
	UC8276CRed   uint8 = 2
)

// UC8276C pixel modes (PSR.KW/R).
const (
	UC8276CModeKWR uint8 = 0
	UC8276CModeKW  uint8 = 1
)

// UC8276C maximum panel size.
const (
	UC8276CMaxWidth  = 400
	UC8276CMaxHeight = 300
)

const (
	uc8276cCmdPSR   uint8 = 0x00
	uc8276cCmdPWR   uint8 = 0x01
	uc8276cCmdPOF   uint8 = 0x02
	uc8276cCmdPON   uint8 = 0x04
	uc8276cCmdBTST  uint8 = 0x06
	uc8276cCmdDSLP  uint8 = 0x07
	uc8276cCmdDTM1  uint8 = 0x10
	uc8276cCmdDRF   uint8 = 0x12
	uc8276cCmdDTM2  uint8 = 0x13
	uc8276cCmdAUTO  uint8 = 0x17
	uc8276cCmdLUTC  uint8 = 0x20
	uc8276cCmdLUTWW uint8 = 0x21
	uc8276cCmdLUTKW uint8 = 0x22
	uc8276cCmdLUTWK uint8 = 0x23
	uc8276cCmdLUTKK uint8 = 0x24
	uc8276cCmdPLL   uint8 = 0x30
	uc8276cCmdTSC   uint8 = 0x40
	uc8276cCmdTSE   uint8 = 0x41
	uc8276cCmdCDI   uint8 = 0x50
	uc8276cCmdLPD   uint8 = 0x51
	uc8276cCmdTRES  uint8 = 0x61
	uc8276cCmdREV   uint8 = 0x70
	uc8276cCmdFLG   uint8 = 0x71
	uc8276cCmdVV    uint8 = 0x81
	uc8276cCmdVDCS  uint8 = 0x82
	uc8276cCmdPTL   uint8 = 0x90
	uc8276cCmdPTIN  uint8 = 0x91
	uc8276cCmdPTOUT uint8 = 0x92
	uc8276cCmdROTP  uint8 = 0xA2
)

// ErrBusyTimeout is returned when BUSY_N does not go high within the timeout.
var ErrBusyTimeout = errors.New("uc8276c: BUSY_N did not go high within the timeout")

// ErrInvalidWindow is returned by ShowRegion for an empty or off-panel window.
var ErrInvalidWindow = errors.New("uc8276c: window is empty or outside the panel")

// uc8276cPLLHz is the frame rate in Hz for PLL M (1..7) and N (1..7), panel datasheet p. 24.
var uc8276cPLLHz = [7][7]int{
	{29, 14, 10, 7, 6, 5, 4},
	{57, 29, 19, 14, 11, 10, 8},
	{86, 43, 29, 21, 17, 14, 12},
	{114, 57, 38, 29, 23, 19, 16},
	{150, 72, 48, 36, 29, 24, 20},
	{171, 86, 57, 43, 34, 29, 24},
	{200, 100, 67, 50, 40, 33, 29},
}

func uc8276cCode(value, base, step float64, maxCode int) uint8 {
	c := int((value-base)/step + 0.5)
	if c < 0 {
		c = 0
	}
	if c > maxCode {
		c = maxCode
	}
	return uint8(c)
}

// UC8276CMinimal drives a UC8276C black/white/red e-paper controller
// (400 x 300 panels such as the Waveshare 4.2inch e-Paper (B)).
//
// The driver keeps the picture in framebuffers and sends it on Show(). Every
// update is the datasheet sequence DTM1 + DTM2 -> PON -> DRF -> POF, waiting
// on BUSY_N (active low) after each power/refresh step; Show blocks for the
// whole refresh (about 17 s on the reference panel).
//
// The connection is a connection.DisplayConn, which owns CS#, D/C#, RES# and
// BUSY_N (see specs/transport_display.md).
//
// Pixel polarity: the framebuffer mapping (B/W bit 1 = white, red bit 1 =
// red) holds for CDI.DDX = 01, which the driver writes explicitly at
// initialisation instead of relying on the OTP default.
type UC8276CMinimal struct {
	conn     connection.DisplayConn
	width    int
	height   int
	rowBytes int
	size     int
	timeout  time.Duration
	bw       []byte
	red      []byte // nil until the first red pixel
	psr      uint8
	asleep   bool
}

// NewUC8276CMinimal runs the initialisation sequence: hardware reset, wait for
// BUSY_N, PSR 0x0F (KWR, LUT from OTP, scan up, shift right, booster on),
// TRES when the panel is not 400 x 300, and CDI 0xD7. The panel picture is
// left unchanged until Show().
//
// width is a multiple of 8 in 8..400, height in 1..300; busyTimeout limits
// every BUSY_N wait (<= 0 waits forever; the full refresh was measured at
// 16.8 s, 30 s is a good default).
func NewUC8276CMinimal(conn connection.DisplayConn, width, height int, busyTimeout time.Duration) (*UC8276CMinimal, error) {
	if width < 8 || width > UC8276CMaxWidth || width%8 != 0 {
		return nil, fmt.Errorf("uc8276c: width %d must be a multiple of 8 in 8..%d", width, UC8276CMaxWidth)
	}
	if height < 1 || height > UC8276CMaxHeight {
		return nil, fmt.Errorf("uc8276c: height %d must be in 1..%d", height, UC8276CMaxHeight)
	}
	d := &UC8276CMinimal{
		conn:     conn,
		width:    width,
		height:   height,
		rowBytes: width / 8,
		size:     width / 8 * height,
		timeout:  busyTimeout,
		psr:      0x0F,
	}
	d.bw = make([]byte, d.size)
	for i := range d.bw {
		d.bw[i] = 0xFF
	}
	if err := d.initSequence(); err != nil {
		return nil, err
	}
	return d, nil
}

// Width returns the panel width in pixels.
func (d *UC8276CMinimal) Width() int { return d.width }

// Height returns the panel height in pixels.
func (d *UC8276CMinimal) Height() int { return d.height }

// Clear fills the framebuffers with one colour (UC8276CWhite, UC8276CBlack or
// UC8276CRed). The panel is unchanged until Show().
func (d *UC8276CMinimal) Clear(color uint8) {
	fill := byte(0x00)
	if color == UC8276CWhite {
		fill = 0xFF
	}
	for i := range d.bw {
		d.bw[i] = fill
	}
	if color == UC8276CRed {
		if d.red == nil {
			d.red = make([]byte, d.size)
		}
		for i := range d.red {
			d.red[i] = 0xFF
		}
	} else if d.red != nil {
		for i := range d.red {
			d.red[i] = 0
		}
	}
}

// SetPixel sets one framebuffer pixel; out-of-range coordinates are ignored.
// The red plane is allocated on the first UC8276CRed pixel.
func (d *UC8276CMinimal) SetPixel(x, y int, color uint8) {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return
	}
	index := y*d.rowBytes + (x >> 3)
	mask := byte(0x80 >> uint(x&7))
	if color == UC8276CWhite {
		d.bw[index] |= mask
	} else {
		d.bw[index] &^= mask
	}
	if color == UC8276CRed {
		if d.red == nil {
			d.red = make([]byte, d.size)
		}
		d.red[index] |= mask
	} else if d.red != nil {
		d.red[index] &^= mask
	}
}

// Show writes both planes to the controller and runs a full display update:
// DTM1, DTM2, PON, wait BUSY_N, DRF, wait BUSY_N, POF, wait BUSY_N. A sleeping
// controller is woken (hardware reset + initialisation) first. It returns
// ErrBusyTimeout when a BUSY_N wait exceeds the timeout.
func (d *UC8276CMinimal) Show() error {
	if d.asleep {
		if err := d.wake(); err != nil {
			return err
		}
	}
	if err := d.writePlanes(); err != nil {
		return err
	}
	return d.powerCycleRefresh()
}

// Sleep puts the controller into deep sleep (DSLP with check code 0xA5). Only
// a hardware reset restores communication; Show() and Wake() do that. The
// panel keeps its image.
func (d *UC8276CMinimal) Sleep() error {
	if err := d.conn.SendCommand(uc8276cCmdDSLP, []byte{0xA5}); err != nil {
		return err
	}
	d.asleep = true
	return nil
}

func (d *UC8276CMinimal) waitReady() error {
	ok, err := d.conn.WaitBusy(false, d.timeout)
	if err != nil {
		return err
	}
	if !ok {
		return ErrBusyTimeout
	}
	return nil
}

func (d *UC8276CMinimal) initSequence() error {
	if err := d.conn.Reset(); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	if err := d.conn.SendCommand(uc8276cCmdPSR, []byte{d.psr}); err != nil {
		return err
	}
	if d.width != UC8276CMaxWidth || d.height != UC8276CMaxHeight {
		tres := []byte{
			byte((d.width >> 8) & 1), byte(d.width & 0xF8),
			byte((d.height >> 8) & 1), byte(d.height & 0xFF),
		}
		if err := d.conn.SendCommand(uc8276cCmdTRES, tres); err != nil {
			return err
		}
	}
	// VBD = 11, DDX = 01, CDI = 0111: the framebuffer polarity holds
	return d.conn.SendCommand(uc8276cCmdCDI, []byte{0xD7})
}

func (d *UC8276CMinimal) wake() error {
	d.asleep = false
	return d.initSequence()
}

func (d *UC8276CMinimal) sendZeros(total int) error {
	var zeros [512]byte
	for total > 0 {
		n := total
		if n > len(zeros) {
			n = len(zeros)
		}
		if err := d.conn.SendData(zeros[:n]); err != nil {
			return err
		}
		total -= n
	}
	return nil
}

func (d *UC8276CMinimal) sendPlane(cmd uint8, plane []byte) error {
	if err := d.conn.SendCommand(cmd, nil); err != nil {
		return err
	}
	if plane == nil {
		return d.sendZeros(d.size)
	}
	return d.conn.SendData(plane)
}

func (d *UC8276CMinimal) writePlanes() error {
	if err := d.sendPlane(uc8276cCmdDTM1, d.bw); err != nil {
		return err
	}
	return d.sendPlane(uc8276cCmdDTM2, d.red)
}

func (d *UC8276CMinimal) powerCycleRefresh() error {
	for _, cmd := range []uint8{uc8276cCmdPON, uc8276cCmdDRF, uc8276cCmdPOF} {
		if err := d.conn.SendCommand(cmd, nil); err != nil {
			return err
		}
		if err := d.waitReady(); err != nil {
			return err
		}
	}
	return nil
}

// UC8276CFull extends UC8276CMinimal with drawing helpers, partial-window
// updates, the auto sequence, pixel mode / scan direction, power and VCOM
// settings, frame rate, user LUTs and the read commands (status, temperature,
// revision, VCOM, low power, OTP).
//
// Register layouts follow the panel datasheet where it disagrees with the
// chip datasheet (PWR, PLL, VCOM_DC); see specs/display/uc8276c.md. The Read*
// methods need a half-duplex wire mode; with a simplex connection they
// return connection.ErrReadUnavailable.
type UC8276CFull struct {
	*UC8276CMinimal
}

// NewUC8276CFull is NewUC8276CMinimal returning the full interface.
func NewUC8276CFull(conn connection.DisplayConn, width, height int, busyTimeout time.Duration) (*UC8276CFull, error) {
	m, err := NewUC8276CMinimal(conn, width, height, busyTimeout)
	if err != nil {
		return nil, err
	}
	return &UC8276CFull{UC8276CMinimal: m}, nil
}

// GetPixel returns the colour of one framebuffer pixel (UC8276CBlack when out of range).
func (d *UC8276CFull) GetPixel(x, y int) uint8 {
	if x < 0 || y < 0 || x >= d.width || y >= d.height {
		return UC8276CBlack
	}
	index := y*d.rowBytes + (x >> 3)
	mask := byte(0x80 >> uint(x&7))
	if d.red != nil && d.red[index]&mask != 0 {
		return UC8276CRed
	}
	if d.bw[index]&mask != 0 {
		return UC8276CWhite
	}
	return UC8276CBlack
}

func iabs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

// DrawLine draws a Bresenham line into the framebuffer.
func (d *UC8276CFull) DrawLine(x0, y0, x1, y1 int, color uint8) {
	dx := iabs(x1 - x0)
	dy := -iabs(y1 - y0)
	sx, sy := 1, 1
	if x0 >= x1 {
		sx = -1
	}
	if y0 >= y1 {
		sy = -1
	}
	err := dx + dy
	for {
		d.SetPixel(x0, y0, color)
		if x0 == x1 && y0 == y1 {
			break
		}
		e2 := 2 * err
		if e2 >= dy {
			err += dy
			x0 += sx
		}
		if e2 <= dx {
			err += dx
			y0 += sy
		}
	}
}

// DrawRect draws a rectangle outline, or a filled rectangle when fill is true.
func (d *UC8276CFull) DrawRect(x, y, w, h int, color uint8, fill bool) {
	if w <= 0 || h <= 0 {
		return
	}
	if fill {
		for j := 0; j < h; j++ {
			for i := 0; i < w; i++ {
				d.SetPixel(x+i, y+j, color)
			}
		}
		return
	}
	d.DrawLine(x, y, x+w-1, y, color)
	d.DrawLine(x, y+h-1, x+w-1, y+h-1, color)
	d.DrawLine(x, y, x, y+h-1, color)
	d.DrawLine(x+w-1, y, x+w-1, y+h-1, color)
}

// Blit copies a 1-bit-per-pixel bitmap (row-major, MSB-first, rows padded to
// whole bytes) into the framebuffer. Set bits are drawn in color, clear bits
// leave the framebuffer unchanged.
func (d *UC8276CFull) Blit(x, y int, data []byte, w, h int, color uint8) {
	if w <= 0 || h <= 0 {
		return
	}
	rowBytes := (w + 7) / 8
	for j := 0; j < h; j++ {
		for i := 0; i < w; i++ {
			if data[j*rowBytes+(i>>3)]&(0x80>>uint(i&7)) != 0 {
				d.SetPixel(x+i, y+j, color)
			}
		}
	}
}

// ShowRegion updates only a window: PTIN, PTL (window widened to whole 8-pixel
// columns), the window's bytes of both planes, PON, DRF, POF, PTOUT. It saves
// SPI time; the waveform is the OTP full waveform, so the panel still flashes.
// It returns ErrInvalidWindow for an empty or off-panel window.
func (d *UC8276CFull) ShowRegion(x, y, w, h int) error {
	if w <= 0 || h <= 0 {
		return ErrInvalidWindow
	}
	if x < 0 {
		w += x
		x = 0
	}
	if y < 0 {
		h += y
		y = 0
	}
	if x >= d.width || y >= d.height || w <= 0 || h <= 0 {
		return ErrInvalidWindow
	}
	x0 := x &^ 7
	x1 := (x + w - 1) | 7
	if x1 >= d.width {
		x1 = d.width - 1
	}
	y0 := y
	y1 := y + h - 1
	if y1 >= d.height {
		y1 = d.height - 1
	}
	if d.asleep {
		if err := d.wake(); err != nil {
			return err
		}
	}
	if err := d.conn.SendCommand(uc8276cCmdPTIN, nil); err != nil {
		return err
	}
	ptl := []byte{
		byte((x0 >> 8) & 1), byte(x0 & 0xF8),
		byte((x1 >> 8) & 1), byte((x1 & 0xF8) | 0x07),
		byte((y0 >> 8) & 1), byte(y0 & 0xFF),
		byte((y1 >> 8) & 1), byte(y1 & 0xFF),
		0x00, // PT_SCAN = 0: scan only inside the window
	}
	if err := d.conn.SendCommand(uc8276cCmdPTL, ptl); err != nil {
		return err
	}
	winBytes := (x1 - x0 + 1) / 8
	planes := []struct {
		cmd   uint8
		plane []byte
	}{{uc8276cCmdDTM1, d.bw}, {uc8276cCmdDTM2, d.red}}
	for _, p := range planes {
		if err := d.conn.SendCommand(p.cmd, nil); err != nil {
			return err
		}
		for row := y0; row <= y1; row++ {
			var err error
			if p.plane == nil {
				err = d.sendZeros(winBytes)
			} else {
				start := row*d.rowBytes + (x0 >> 3)
				err = d.conn.SendData(p.plane[start : start+winBytes])
			}
			if err != nil {
				return err
			}
		}
	}
	refreshErr := d.powerCycleRefresh()
	if err := d.conn.SendCommand(uc8276cCmdPTOUT, nil); err != nil && refreshErr == nil {
		return err
	}
	return refreshErr
}

// RefreshAuto writes the planes, then runs the AUTO sequence (0xA5, or 0xA7
// with deepSleep, which also enters deep sleep) instead of PON/DRF/POF.
//
// AUTO is listed only in the chip datasheet. On the reference panel
// (Waveshare 4.2inch e-Paper (B)) it starts no refresh: the call returns
// after the plane transfer and the panel keeps its picture. Use Show()
// unless your panel is known to accept it.
func (d *UC8276CFull) RefreshAuto(deepSleep bool) error {
	if d.asleep {
		if err := d.wake(); err != nil {
			return err
		}
	}
	if err := d.writePlanes(); err != nil {
		return err
	}
	code := byte(0xA5)
	if deepSleep {
		code = 0xA7
	}
	if err := d.conn.SendCommand(uc8276cCmdAUTO, []byte{code}); err != nil {
		return err
	}
	if err := d.waitReady(); err != nil {
		return err
	}
	if deepSleep {
		d.asleep = true
	}
	return nil
}

// Wake runs a hardware reset and the initialisation sequence (exit from deep
// sleep). The framebuffers are kept.
func (d *UC8276CFull) Wake() error { return d.wake() }

// WaitBusy waits for BUSY_N to go high; the second result is false on
// timeout. timeout <= 0 waits forever.
func (d *UC8276CFull) WaitBusy(timeout time.Duration) (bool, error) {
	return d.conn.WaitBusy(false, timeout)
}

func (d *UC8276CFull) writePSR() error {
	return d.conn.SendCommand(uc8276cCmdPSR, []byte{d.psr})
}

// SetMode selects the pixel mode: UC8276CModeKWR (black/white/red, default)
// or UC8276CModeKW (black/white with NEW/OLD; needs KW LUTs in OTP).
func (d *UC8276CFull) SetMode(mode uint8) error {
	if mode == UC8276CModeKW {
		d.psr |= 0x10
	} else {
		d.psr &^= 0x10
	}
	return d.writePSR()
}

// SetFlip mirrors the picture: horizontal clears SHL, vertical clears UD. The
// framebuffer is unchanged.
func (d *UC8276CFull) SetFlip(horizontal, vertical bool) error {
	if horizontal {
		d.psr &^= 0x04
	} else {
		d.psr |= 0x04
	}
	if vertical {
		d.psr &^= 0x08
	} else {
		d.psr |= 0x08
	}
	return d.writePSR()
}

// SetPower writes PWR (panel-datasheet layout, internal DC-DC).
//
// Advanced: the values must match the panel vendor's; a wrong set can drive
// the panel outside its rating. The driver does not write PWR by default.
// vdh, vdl (magnitude) and vdhr are 2.4..11.0 V in 0.2 V steps; vgh is 16, 15,
// 14 or 13 V (VGL = -VGH).
func (d *UC8276CFull) SetPower(vdh, vdl, vdhr, vgh float64) error {
	if vdl < 0 {
		vdl = -vdl
	}
	lv := int(16.0 - vgh + 0.5)
	if lv < 0 {
		lv = 0
	}
	if lv > 3 {
		lv = 3
	}
	return d.conn.SendCommand(uc8276cCmdPWR, []byte{
		0x03, // VDS_EN | VDG_EN
		byte(lv),
		uc8276cCode(vdh, 2.4, 0.2, 0x2B),
		uc8276cCode(vdl, 2.4, 0.2, 0x2B),
		uc8276cCode(vdhr, 2.4, 0.2, 0x2B),
	})
}

// SetVcomDc writes VDCS: -0.10 .. -3.25 V in 0.05 V steps (panel layout).
func (d *UC8276CFull) SetVcomDc(volts float64) error {
	return d.conn.SendCommand(uc8276cCmdVDCS, []byte{uc8276cCode(-0.10-volts, 0.0, 0.05, 0x3F)})
}

// SetFrameRate writes PLL with the nearest rate of the panel's M/N table (4..200 Hz).
func (d *UC8276CFull) SetFrameRate(hz int) error {
	best := byte(0x3C) // M = 7, N = 4 (50 Hz)
	bestDiff := 1 << 30
	for m := 1; m <= 7; m++ {
		for n := 1; n <= 7; n++ {
			diff := iabs(uc8276cPLLHz[m-1][n-1] - hz)
			if diff < bestDiff {
				bestDiff = diff
				best = byte(m<<3 | n)
			}
		}
	}
	return d.conn.SendCommand(uc8276cCmdPLL, []byte{best})
}

// SetSoftStart writes BTST (booster soft start phases A, B, C).
func (d *UC8276CFull) SetSoftStart(a, b, c uint8) error {
	return d.conn.SendCommand(uc8276cCmdBTST, []byte{a, b, c})
}

// SetInterval writes CDI: interval cdi 0..15 (0b0111 default), border VBD
// 0..3 (3 = floating in KWR) and data polarity DDX 0..3. The framebuffer
// mapping assumes polarity 1; changing it is the caller's responsibility.
func (d *UC8276CFull) SetInterval(cdi, border, polarity uint8) error {
	return d.conn.SendCommand(uc8276cCmdCDI, []byte{(border&3)<<6 | (polarity&3)<<4 | cdi&0x0F})
}

// SetTemperatureSensor writes TSE: internal (default) or external LM75 sensor,
// offset -8..+7 degrees Celsius.
func (d *UC8276CFull) SetTemperatureSensor(external bool, offset int8) error {
	v := byte(offset) & 0x0F
	if external {
		v |= 0x80
	}
	return d.conn.SendCommand(uc8276cCmdTSE, []byte{v})
}

// WriteLUT loads user waveform tables and selects them (PSR.REG = 1). The LUT
// commands exist only in the chip datasheet; verify the panel accepts them.
// Lengths: lutC 56, lutWW 42, lutKW 56, lutWK 56, lutKK 56 bytes. Any table may
// be nil to leave it alone.
func (d *UC8276CFull) WriteLUT(lutC, lutWW, lutKW, lutWK, lutKK []byte) error {
	tables := []struct {
		cmd   uint8
		table []byte
	}{{uc8276cCmdLUTC, lutC}, {uc8276cCmdLUTWW, lutWW}, {uc8276cCmdLUTKW, lutKW},
		{uc8276cCmdLUTWK, lutWK}, {uc8276cCmdLUTKK, lutKK}}
	for _, t := range tables {
		if t.table == nil {
			continue
		}
		if err := d.conn.SendCommand(t.cmd, t.table); err != nil {
			return err
		}
	}
	d.psr |= 0x20
	return d.writePSR()
}

// SendCommand sends a raw command and parameters (escape hatch).
func (d *UC8276CFull) SendCommand(command uint8, data []byte) error {
	return d.conn.SendCommand(command, data)
}

func (d *UC8276CFull) read(cmd uint8, n int) ([]byte, error) {
	if err := d.conn.SendCommand(cmd, nil); err != nil {
		return nil, err
	}
	return d.conn.ReadData(n)
}

// ReadStatus reads FLG: bit 6 PTL_FLAG, 5 I2C_ERR, 4 I2C_BUSYN, 3 data_flag,
// 2 PON, 1 POF, 0 BUSY_N.
func (d *UC8276CFull) ReadStatus() (uint8, error) {
	b, err := d.read(uc8276cCmdFLG, 1)
	if err != nil {
		return 0, err
	}
	return b[0], nil
}

// ReadTemperature reads the internal temperature sensor (TSC) in signed
// degrees Celsius.
func (d *UC8276CFull) ReadTemperature() (int, error) {
	b, err := d.read(uc8276cCmdTSC, 2)
	if err != nil {
		return 0, err
	}
	return int(int8(b[0])), nil
}

// ReadRevision reads REV. longForm false reads the panel datasheet's 1-byte
// form and returns hasChip = false; longForm true reads the chip datasheet's
// 5 bytes (reserved, CHIP_REV, LUT_REV[7:0], [15:8], [23:16]).
func (d *UC8276CFull) ReadRevision(longForm bool) (chipRev uint8, hasChip bool, lutRev uint32, err error) {
	if !longForm {
		b, e := d.read(uc8276cCmdREV, 1)
		if e != nil {
			return 0, false, 0, e
		}
		return 0, false, uint32(b[0]), nil
	}
	b, e := d.read(uc8276cCmdREV, 5)
	if e != nil {
		return 0, false, 0, e
	}
	return b[1], true, uint32(b[2]) | uint32(b[3])<<8 | uint32(b[4])<<16, nil
}

// ReadVcom reads the measured VCOM (VV, panel layout) in volts.
func (d *UC8276CFull) ReadVcom() (float64, error) {
	b, err := d.read(uc8276cCmdVV, 1)
	if err != nil {
		return 0, err
	}
	return -0.10 - 0.05*float64(b[0]&0x3F), nil
}

// ReadLowPower reads low-power detection (LPD, with LPMD = 0); true when the
// supply is low.
func (d *UC8276CFull) ReadLowPower() (bool, error) {
	b, err := d.read(uc8276cCmdLPD, 1)
	if err != nil {
		return false, err
	}
	return b[0]&1 == 0, nil
}

// ReadOTP reads length OTP bytes from address 0 (ROTP; the dummy byte is dropped).
func (d *UC8276CFull) ReadOTP(length int) ([]byte, error) {
	if err := d.conn.SendCommand(uc8276cCmdROTP, nil); err != nil {
		return nil, err
	}
	if _, err := d.conn.ReadData(1); err != nil {
		return nil, err
	}
	return d.conn.ReadData(length)
}
