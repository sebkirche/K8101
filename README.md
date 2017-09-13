# K8101
Communication with the Velleman's USB display board kit K8101

## Introduction ##

The kit is shipped with several example programs with source code, but the communication with the display is only avaliable as a precompiled .Net library. Reverse-engineering was necessary to retrieve the dedicated binary protocol understood by the on-board PIC 18F controller.

Once the display plugged to the computer, we can see that the display is seen as a Communication Device Class (like a modem) and a corresponding tty is created (automatically with GNU/Linux and MacOS X, and after use of a .inf to associate the display with the usbser.sys device driver on Windows).

## Technical info ##

**Vendor ID**: 0x10cf / **Device ID**: 0x8101

### Structure of a command ###

Each command is made of several bytes:
  * starting delimiter 0xAA (170)
  * lowest significant byte of the command size[^1]
  * highest significant by of the command size
  * command ID
  * (possibly empty) list of additional bytes depending on the command
  * checksum
  * stoping delimiter 0x55 (85)

[^1]: This applies to all but 4 commands. Seems to be a bug in the PIC size

Computation of the checksum: 
  * perform Σ( command bytes, not including start & stop) modulo 256
  * when the sum is incorrect, the display shows "CHKSM"

| Function         | LSB(size) | MSB(size) | ID     | Additional data             |
|------------------|:---------:|:---------:|--------|-----------------------------|
| Backlight        | 7         | 0         | 20     | 0-254=seconds 255=permanent |
| Beep             | 7         | 0         | 6      | count                       |
| Clear all        | 6         | 0         | 2      | n/a                         |
| Clear foreground | 6         | 0         | 3      | n/a                         |
| Contrast         | 7         | 0         | 17     | 0-63                        |
| Draw Line        | 16 [^2]   | 0         | 18     | x1 y1 x2 y2                 |
| Draw pixel       | 8         | 0         | 9      | x1 y1                       |
| Draw rectangle   | 16 [^2]   | 0         | 7      | x1 y1 width height          |
| Erase Line       | 16 [^2]   | 0         | 19     | x1 y1 x2 y2                 |
| Erase pixel      | 8         | 0         | 16     | x1 y1                       |
| Erase rectangle  | 16 [^2]   | 0         | 8      | x1 y1 width height          |
| Invert display   | 7         | 0         | 21     | 0=normal 1=inverted         |
| Draw bitmap[^3]  | 6         | 4         | 1      | 1024 bytes - cf bitmap layout |
| Draw text        | LSB size  | MSB size  | 4=big 5=small      | x1 y1 max_width strZ |

[^2]: these 4 commands show an incorrect message size. This might be a bug in the PIC program.

[^3]: the bitmap is stored locally in the display memory and can be redisplayed without transfer with the "Clear foreground" command. It can be used as a background picture.

### Character set ###

  * Big size: 6x8 pixels (glyphs = 5x7)
  * Small size: 4x8 pixels (glyphs = 3x6)
  
### Bitmap layout ###

    (0,0)  -->  (127,0)
      |
      V
    (0,63) -->  (127,63)

128 x 64 monochrome pixels result in a buffer of 8192 bits = 1KB.

The 1024 bytes buffer to send a bitmap has a weird layout, maybe due to the (unidentified) LCD display:
  * the screen is made of 8 horizontal bands of 8 pixels, first on top
  * for each band, one byte describe a column of 8 pixels, the most significant bit being the bottom of the column

        Byte 0               Byte 127
        LSB = (0,0)          LSB = (127,0)
        .                    .
        .                    .
        .                    .
        .                    .
        .                    .
        .                    .
        MSB = (0,7)          MSB = (127,7)
        ----------------------------------
        Byte 128             Byte 255
        LSB = (0,8)          LSB = (127,8)
        .                    .
        .                    .
        .                    .
        .                    .
        .                    .
        .                    .
        MSB = (0,7)          MSB = (127,7)
        ----------------------------------
        bands 3 to 7
        ----------------------------------
        Byte 896             Byte 1023
        LSB = (0,55)         LSB = (127,55)
        .                    .
        .                    .
        .                    .
        .                    .
        .                    .
        .                    .
        MSB = (0,63)         MSB = (127,63)
        
