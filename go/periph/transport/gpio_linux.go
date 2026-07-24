//go:build linux && !tinygo

package transport

import (
	"fmt"
	"syscall"
	"unsafe"
)

// GPIO chardev ioctl numbers and constants from <linux/gpio.h>.
const (
	gpioGetLineHandleIoctl  = 0xc04cb2a4
	gpioHandleSetLineValues  = 0xc040cb15
	gpioHandleGetLineValues  = 0xc040cb16
	gpioMaxLines             = 64
)

// gpioHandleRequest mirrors struct gpiohandle_request.
type gpioHandleRequest struct {
	lineoffsets   [gpioMaxLines]uint32
	flags         uint32
	defaultVals   [gpioMaxLines]uint8
	consumerLabel [32]byte
	lines         uint32
	fd            int32
}

// gpioHandleData mirrors struct gpiohandle_data.
type gpioHandleData struct {
	values [gpioMaxLines]uint8
}

// gpioLineSet is a small per-transport cache of the gpiochip fd and
// line handle for a set of GPIO lines. Each transport (HX711, SIPO,
// etc.) holds one of these for the lines it requested.
type gpioLineSet struct {
	chipFd     int
	lineHandle int
	lineIndex  map[int]int // logical line offset -> slot in values[]
}

var gpioChips = map[string]*gpioLineSet{} // cache by chip+lines key

// requestGpioLines opens /dev/gpiochip0 and reserves the given line
// offsets as outputs. Pass -1 for lines that should not be reserved.
func requestGpioLines(lines ...int) error {
	key := "chip0"
	var wantLines []int
	for _, l := range lines {
		if l >= 0 {
			wantLines = append(wantLines, l)
			key += fmt.Sprintf(":%d", l)
		}
	}
	if len(wantLines) == 0 {
		return nil
	}
	if _, ok := gpioChips[key]; ok {
		return nil // already reserved
	}
	chipFd, err := syscall.Open("/dev/gpiochip0", syscall.O_RDWR, 0)
	if err != nil {
		return fmt.Errorf("open /dev/gpiochip0: %w", err)
	}
	req := gpioHandleRequest{lines: uint32(len(wantLines))}
	for i, l := range wantLines {
		req.lineoffsets[i] = uint32(l)
	}
	copy(req.consumerLabel[:], "periph")
	if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(chipFd), gpioGetLineHandleIoctl, uintptr(unsafe.Pointer(&req))); errno != 0 {
		_ = syscall.Close(chipFd)
		return fmt.Errorf("ioctl(GPIO_GET_LINEHANDLE_IOCTL): %v", errno)
	}
	idx := map[int]int{}
	for i, l := range wantLines {
		idx[l] = i
	}
	gpioChips[key] = &gpioLineSet{
		chipFd:     chipFd,
		lineHandle: int(req.fd),
		lineIndex:  idx,
	}
	return nil
}

// setGpioLine drives the given line offset high (true) or low (false).
// The line must have been reserved via requestGpioLines.
func setGpioLine(line int, high bool) error {
	if line < 0 {
		return nil
	}
	// Find any cache entry that contains this line.
	for _, set := range gpioChips {
		if slot, ok := set.lineIndex[line]; ok {
			data := gpioHandleData{}
			if high {
				data.values[slot] = 1
			}
			if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(set.lineHandle), gpioHandleSetLineValues, uintptr(unsafe.Pointer(&data))); errno != 0 {
				return fmt.Errorf("ioctl(GPIOHANDLE_SET_LINE_VALUES): %v", errno)
			}
			return nil
		}
	}
	return fmt.Errorf("gpio: line %d not reserved", line)
}

// readGpioLine reads the given line offset. The line must have been
// reserved via requestGpioLines.
func readGpioLine(line int) (bool, error) {
	if line < 0 {
		return false, fmt.Errorf("gpio: invalid line %d", line)
	}
	for _, set := range gpioChips {
		if slot, ok := set.lineIndex[line]; ok {
			data := gpioHandleData{}
			if _, _, errno := syscall.Syscall(syscall.SYS_IOCTL, uintptr(set.lineHandle), gpioHandleGetLineValues, uintptr(unsafe.Pointer(&data))); errno != 0 {
				return false, fmt.Errorf("ioctl(GPIOHANDLE_GET_LINE_VALUES): %v", errno)
			}
			return data.values[slot] == 1, nil
		}
	}
	return false, fmt.Errorf("gpio: line %d not reserved", line)
}

// releaseGpioLines releases the GPIO line reservation. Currently
// a no-op because the per-transport cache only releases when the
// process exits.
func releaseGpioLines(lines ...int) error {
	_ = lines
	return nil
}
