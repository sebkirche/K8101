﻿# K8101 #

Communication with the Velleman's USB display board kit K8101

![K8101](/docs/k8101.jpg?raw=true "K8101 Message Board")

## Introduction ##

The kit is shipped with several example programs with source code, but the communication with the display is only available as a precompiled .Net library. Reverse-engineering was necessary to retrieve the dedicated binary protocol understood by the on-board PIC 18F27J53-ISP controller.

Once the board is plugged to the computer, we can see that it is seen as a Communication Device (CDC - like a serial port) and a corresponding tty is created (automatically with GNU/Linux and MacOS X, and after use of a .inf to associate the display with the usbser.sys device driver on Windows through a COM port). You can then send data via USB protocol (with limitations, depending on the platform), or simply via the tty/COM.

On some platforms, you can also access the CDC device via LibUSB.

## Linux ##

On Linux, the device appears as `/dev/ttyACM0`.

Serial communication: 9600 bauds, 8 bits data, 1 stop, no parity e.g. `stty -f /dev/tty.xxx 9600 cs8 -cstopb -parenb`

### Serial test ###

* the following test command should clear the screen: `echo 'clear all' && echo -ne '\xaa\06\00\02\x0a\x55' >/dev/ttyACM01`
* the following test command should clear the screen: `echo 'beep 2' && echo -ne '\xaa\07\00\06\x02\x0f\x55' >/dev/ttyACM0`

### USB ###

* Access via LibUSB is supported, so the possibility to detach kernel drivers. You may have to call the script with root access, or play with udev to ensure write access to the corresponding `/dev/bus/usb/<bus_number>/<device_address>` device.

## Mac OSX ##

On my 10.13 the device appears as `/dev/tty.usbmodemFD121`.

### Serial test ###

* the following test command should clear the screen: `echo 'clear all' && echo -ne '\xaa\06\00\02\x0a\x55' >/dev/tty.usbmodemFD121`
* the following test command should clear the screen: `echo 'beep 2' && echo -ne '\xaa\07\00\06\x02\x0f\x55' >/dev/tty.usbmodemFD121`

### USB ###

* Access via (Homebrew) LibUSB is supported, so the possibility to detach kernel drivers.

## Windows ##

On Windows it is managed by the driver usbser and it results in a new COM port. 

My Windows 10 is configuring at 19200 bauds / 8 / 1 / No parity.

### USB ###

On windows, the CDC handling is provided via `usbser.sys` / `usbser.dll`. There should exist a way to make ignore the K8101 but I did not found it yet.

## Code ##

A reference implementation is made in Perl, in 2 versions:
   * `send.pl` sends data via serial port
   * `send_usb.pl` is an attempt to implement an USB driver using libusb binding. Some issues may happen due to a lack of authorization to access the device.

## Technical info ##

USB: **Vendor ID** `0x10cf` (Microchip) / **Device ID** `0x8101`

### Structure of a command ###

Each command is made of several bytes: `[AA][size.LSB][size.MSB][CMD][...][CHK][55]`
  * starting delimiter 0xAA (170)
  * lowest significant byte of the command total size
  * highest significant byte of the command total size
  * command ID
  * (possibly empty) list of additional bytes depending on the command
  * checksum
  * stopping delimiter 0x55 (85)

Computation of the checksum: 
  * perform Σ( size + command + params bytes, not including start & stop) modulo 256
  * when the sum is incorrect, the display shows `CHKSM` (Note: if you notice that some of the commands are failing with a `CHKSM`, it may due to the tty output conversion settings or communication speed - seen with Linux)
    
### List of commands ###

| Function         | LSB(size) | MSB(size) | CMD ID | Additional data             |
|------------------|:---------:|:---------:|--------|-----------------------------|
| Backlight timeout| 7         | 0         | 20 (14h)     | `<seconds>` 0-254=seconds 255=permanent |
| Beep             | 7         | 0         | 6      | `<number_of_beeps>`                   |
| Clear all        | 6         | 0         | 2      | n/a                         |
| Clear foreground <br>(restore last loaded bitmap)| 6         | 0         | 3      | n/a                         |
| Contrast         | 7         | 0         | 17 (11h)    | `<level>` 0-63              |
| Draw line        | 16 [^1]   | 0         | 18 (12h)    | `<x1>` `<y1>` `<x2>` `<y2>`                 |
| Draw pixel       | 8         | 0         | 9      | `<x1>` `<y1>`                       |
| Draw plain rectangle| 16 [^1]| 0         | 7      | `<x1>` `<y1>` `<width>` `<height>`          |
| Erase line       | 16 [^1]   | 0         | 19 (13h)    | `<x1>` `<y1>` `<x2>` `<y2>`                 |
| Erase pixel      | 8         | 0         | 16 (10h)    | `<x1>` `<y1>`                       |
| Erase rectangle  | 16 [^1]   | 0         | 8      | `<x1>` `<y1>` `<width>` `<height>`          |
| Invert display   | 7         | 0         | 21 (15h)    | `<invert>` 0=normal 1=inverted         |
| Draw bitmap[^2]  | 6         | 4         | 1      | 1024 bytes + 1[^3] - cf bitmap layout |
| Draw text        | LSB size  | MSB size  | 4=big / 5=small | `<x1>` `<y1>` `<max_width>`[^4] `<string_bytes + 1>`[^3] |

Note: we can see a "hole" in the commands list: 10, 11, 12, 13, 14, 15 seem unused.

[^1]: these 4 commands need an incorrect message size. This might be a bug in the PIC program.

[^2]: the bitmap is stored locally in the display memory and can be redisplayed without transfer with the "Clear foreground" command. It can be used as a background picture.

[^3]: for these 2 commands the actual data (text or 1024 bytes of bitmap) must be followed by an extra 0 - that is *not* counted in the size (!). Code smell in PIC side...

[^4]: the max_width parameter is used to wrap text and has no effect if the text does not have spaces. I have noticed some weird rendering if the text begins with spaces, y is increased unexpectedly for each space.

### Character set ###

  * Big size: 6x8 pixels (glyphs = 5x7)
  * Small size: 4x8 pixels (glyphs = 3x6)
  
### Bitmap layout ###

    (0,0)  -->  (127,0)
      |
      V
    (0,63) -->  (127,63)

128 x 64 monochrome pixels result in a buffer of 8192 bits = 1KiB.

The 1024 bytes buffer to send a bitmap has a weird layout, LCD display is not identified:
  * the screen is divided in 8 horizontal bands of 8 pixels high each, first band on top
  * for each band, there is 128 bytes, one byte describe a column of 8 pixels, starting from the left, the most significant bit being the bottom of the column
  
Someone suggested that the LCD matrix is simply text-oriented.

        Byte 0               Byte 127
        LSB = (0,0)          LSB = (127,0)
        .                    .
        .                    .
        .            Band 1  .
        .                    .
        .                    .
        .                    .
        MSB = (0,7)          MSB = (127,7)
        ----------------------------------
        Byte 128             Byte 255
        LSB = (0,8)          LSB = (127,8)
        .                    .
        .                    .
        .            Band 2  .
        .                    .
        .                    .
        .                    .
        MSB = (0,15)          MSB = (127,15)
        ----------------------------------
                  Bands 3 to 7
        ----------------------------------
        Byte 896             Byte 1023
        LSB = (0,55)         LSB = (127,55)
        .                    .
        .                    .
        .            Band 8  .
        .                    .
        .                    .
        .                    .
        MSB = (0,63)         MSB = (127,63)
        
### Notifications of buttons ###

The display is able to notify the press of the button to the computer.
  * Short press will send `FF 05 FF 04 00`
  * Long press will send `FF 05 AA AF 00`
