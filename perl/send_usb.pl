#!/usr/bin/env perl

use strict;
use warnings;
use feature 'say';
use Image::BMP;
use USB::LibUSB;

$|++;

my $iface_number = 1;
my $vendor = 0x10cf;
my $product = 0x8101;
my $endpoint = 2;
my $BOC = 0xAA;
my $EOC = 0x55;

# Definition of the commands and expected parameters
my %cmds = (
    backlight  => { c => 20, opts => [ 'seconds' ] },
    beep	   => { c => 6,  opts => [ 'beeps' ] },
    clearall   => { c => 2,  opts => undef },
    clearfg    => { c => 3,  opts => undef },
    contrast   => { c => 17, opts => [ 'contrast' ] },
    drawline   => { c => 18, opts => [ qw( x1 y1 x2 y2 ) ] },
    drawpixel  => { c => 9,  opts => [ qw( x1 y1 ) ] },
    drawrect   => { c => 7,  opts => [ qw( x1 y1 width height ) ] },
    eraseline  => { c => 19, opts => [ qw( x1 y1 x2 y2 ) ] },
    erasepixel => { c => 16, opts => [ qw( x1 y1 ) ] },
    eraserect  => { c => 8,  opts => [ qw( x1 y1 width height ) ] },
    invert     => { c => 21, opts => [ 'inverted' ] },
    bitmap     => { c => 1,  opts => [ 'bmp_filename' ] },
    smalltext  => { c => 5,  opts => [ qw( txt x1 y1 width ) ] },
    bigtext    => { c => 4,  opts => [ qw( txt x1 y1 width ) ] },
    read       => {},
    clock      => {}
);

my @args = @ARGV;# or die "Usage: $0 <command> [opts]\n";

unless ($args[0] && $args[0] ne 'help'){
	print "Missing command name. Supported are\n";
    map { my $c=$_;
          my $a=$cmds{$_}->{opts};
          say sprintf("%-10s %s",
                      $c,
                      join(',', map { "<$_>" } @$a))
    } sort keys %cmds;
    exit 1;
}

my $tty;                        # dummy var
# open my $tty, ">$ttypath" or die $!;
# binmode $tty; #??
# # select $tty;
# # $|++;
# select STDOUT;

# additional experiment commands
if($args[0] eq 'testbmp'){
    # send an arbitrary bitmap
    my @b = (0) x 1024;
    @b[0..31] = (0xf0, 0x78, 0x3c, 0x1e, 0xf, 0x7, 3, 1, 0xf, 0x7, 0x3, 0x1, 0x1, 0x2, 4, 8, 16, 32, 64, 128);
    @b[129, 256, 385, 512, 641, 768, 897] = ( 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff);

    my ($lsb, $msb) = get_data_size(\@b);
    my $c = make_raw_cmd($lsb, $msb, 1, @b, 0);
    send_cmd($tty, $c);
    # close $tty;
    exit 0;
} elsif($args[0] eq 'testbmpall'){
    # send all the bmp available in the working directory
    my $cnt = 0;
    foreach (<*.bmp>){
        $cnt++;
        my $c = make_cmd('bitmap', $_);
        send_cmd($tty, $c);
        # flush $tty;
    }
    printf "%d bitmap(s) sent to the display.\n", $cnt;
    # close $tty;
    exit 0;
} elsif($args[0] eq 'testtxt'){
    # send a dummy text in 2 sizes
    my $t1 = '4x8: Lorem Ipsum is simply dummy text of the printing and type- setting industry.';
    my $t2 = '6x8: Lorem Ipsum has been the industry\'s standard dummy text ever since the 1500s. !@#$%^&*?';
    send_cmd($tty, make_cmd('smalltext', $t1, 0, 0, 127));
    # flush $tty;
    send_cmd($tty, make_cmd('bigtext', $t2, 0, 24, 127));
    # close $tty;
    exit 0;
} elsif($args[0] eq 'testunk'){
    # send a unkown command not implemented by the dll
    # my @b = ( 12 );
    # my ($lsb, $msb) = get_data_size(\@b);
    # my $c = make_raw_cmd($lsb, $msb, 12, @b);
    # send_cmd($tty, $c);
    # close $tty;
    exit 0;
} elsif ($args[0] eq 'read'){
    eval { read_data() };
    say "read: $@" if $@;
    exit 0;
} elsif ($args[0] eq 'clock'){
    # my $l = 15;
    my @now = localtime time;
    my $h = $now[2] % 12;
    my $m = $now[1];
    for my $t (0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55){
        draw_hand($t, 60, 31, 28);
    }
    draw_hand($h, 12, 15);
    draw_hand($m, 60, 25);
    exit 0;
}

my $cmd;
$cmd = make_cmd(@args);
send_cmd($tty, $cmd);
# close $tty;

# ---------- End of program ------------------

sub draw_hand {
    my ($val, $full, $end, $start) = @_;
    my $a = time_to_angle($val, $full);
    # say "angle: $a";
    my $orix = 64;              # X center of screen
    my $oriy = 32;              # Y center of screen
    my ($x1, $y1);
    if (defined $start){
        $x1 = $orix + ($start * cos($a));
        $y1 = $oriy - ($start * sin($a));
    } else {
        ($x1, $y1) = ($orix, $oriy);
    }
    my $x2 = $orix + ($end * cos($a));
    my $y2 = $oriy - ($end * sin($a));
    # say "x1 $x1 y1 $y1";
    # say "x2 $x2 y2 $y2";
    # say "cos($a) = ", cos($a); 
    # say "$end * cos($a) = ", $end * cos($a);
    my $st = make_cmd('drawline', $x1, $y1, $x2, $y2);
    send_cmd($tty, $st);
}

sub time_to_angle {
    my ($val, $full) = @_;
    my $a = -( (360 / $full * $val) - 90); # angle
    # $a -= 360 if $a > 360;
    $a = $a * 3.14159265358979 / 180; # convert radians
    return $a;
}

# build the byte array for a command, its parameters, and control bytes
sub make_cmd {
    my $verb = shift;
    my @args = @_;
 
    die "unknowm command '$verb'" unless exists $cmds{$verb};
    my $reqs = $cmds{$verb}->{opts};
    # check is missing arguments
    if ($reqs){
        my $needed = scalar @$reqs - 1;
        my $given = $#args;
        if ($given < $needed){
            my $l = join ',', splice @$reqs, $given + 1;
            printf "'%s' command is missing the following arg(s): %s\n", $verb, $l;
            exit 1;
        }
    }
    if ($verb =~ /bitmap|text/){
        if ($verb eq 'bitmap'){
            @args = build_bitmap($args[0]);
        } else {
            my $t = shift @args;
            push @args, build_text($t);
        } 
    }

    my ($lsb, $msb) = get_data_size(\@args);
    my $size = scalar(@args) + 6;
    
    if ($verb =~ /line|rect/){
        $lsb += 6; #wtf ?!! bug of PIC ?
    }

    if ($verb =~ /bitmap|text/){
        push @args, 0; #extra byte needed by µcontroller of k8101. off-by-one bug?
    }

    # enforce undef = 0 - avoid warning message
    map { $_ = $_ // 0 } @args;

    my $data = pack('C*', $lsb, $msb, $cmds{$verb}->{c}, @args);
    my $chk = checksum($data);

    my $c = pack('C*', $BOC, unpack('C*', $data), $chk, $EOC);
    printf "%s = %s\n", $verb, join(',', unpack("(H2)*", $c));
    printf "size = %d (%x,%x) chk = %x\n", $size, $lsb, $msb, $chk;

    return $c
}

# compute LSB and MSB of size for a command + its parameters
sub get_data_size {
    my $payload = shift;

    # a command is: AA, lsb(size), msb(size), cmd, [data,] chk, 55
    # thus it is 6 bytes + data (if any)
    my $size = scalar(@$payload) + 6;
    my $lsb = $size & 0xff;
    my $msb = ($size >> 8) & 0xff;
    return $lsb, $msb;
}

# build the byte array for an arbitrary command - "raw mode"
sub make_raw_cmd {
    my ($lsb, $msb, $cmd, @args) = @_;
    my @payload = @_;

    #enforce undef = 0
    map { $_ = $_ // 0 } @args;
    
    my $c = pack('C*', $lsb, $msb, $cmd, @args);
    $c = pack('C*', $BOC, unpack('C*', $c), checksum($c), $EOC);
    printf "%s = %s\n", $cmd, join(',', unpack("(H2)*", $c));
    return $c
}

# build the byte array for a text
sub build_text {
    my $txt = shift;
    return unpack('C*', $txt);
}

# build the byte array for a bitmap
sub build_bitmap {
    my $filename = shift;
    
    # use ImageMagick 'convert image.png BMP3:image.bmp'
    # if transparent to specify color 'gm convert amazon.png -background white -flatten  BMP3:amazon.bmp'
    # gm convert ec_splash2.png -resize 128x64! -background white -flatten -threshold 85% BMP3:ec.bmp
    # my $bmp = new Image::BMP(file => 'background.bmp');
    my $bmp = new Image::BMP(file => $filename);
    
    #$bmp->view_ascii;
    my @bits;  # temp buffer for the bits of a single byte
    my @bytes; # buffer for K8101 bitmap

    # original buffer filling, double reverse order (reverse picture + reverse buffer)
    # as done by the dll
    # for my $band (0..7){
    #     for my $x (0..127){
    #         @bits = ();
    #         for my $bit (0..7){
    #             my ($r,$g,$b) = $bmp->xy_rgb(127 - $x, (7 - $band) * 8 + 7 - $bit);
    #             push @bits, ($r > 127 || $g > 127 || $b > 127) ? '0' : '1';
    #         }
    #         # printf "will push %s in bytes\n", join '',@bits;
    #         unshift @bytes, ord(pack('B8', join('',@bits)));
    #     }
    # }
    # print "original compute:";
    # print join(',', @bytes) . $/;

    @bytes = ();
    for my $band (0..7){
        for my $x (0..127){
            @bits = ();
            for (my $bit=7; $bit>=0; $bit--){
                my ($r,$g,$b) = $bmp->xy_rgb($x, $band * 8 + $bit);
                push @bits, ($r > 127 || $g > 127 || $b > 127) ? '0' : '1';
            }
            # printf "will push %s in bytes\n", join '',@bits;
            push @bytes, ord(pack('B8', join('',@bits)));
        }
    }
    #printf "pic size = %d bytes\n", scalar @bytes;
    #print join(',', @bytes) . $/;
    return @bytes;
}

# compute the checksum of a message
sub checksum {
    my $d = shift;
    my $t = 0;
    map { $t += $_ } unpack('C*', $d);
    my $r = $t % 256;
    return $r;
}

# send data to the device
sub send_cmd {
    my $t = shift;
    my $c = shift;
    # print $t $c;
    # flush $t;
    #sleep 1;

    my $ctx = USB::LibUSB->init();
    my $handle = $ctx->open_device_with_vid_pid($vendor, $product);
    if ($handle){
        # $handle->set_auto_detach_kernel_driver(1);
        my $detached = -1;
        if ($handle->kernel_driver_active($iface_number)){
            say "A kernel driver is active for interface, Detaching...";
            $detached = $handle->detach_kernel_driver($iface_number);
        } else {
            # say "No kernel driver active.";
        }
        my $ret = $handle->claim_interface($iface_number);
        die "claim_interface returned $ret" if $ret;

        # actual write
        $handle->bulk_transfer_write($endpoint, $c, length($c));
        
        $handle->release_interface($iface_number);
        if ($detached == 0){
            say "Reattaching driver.";
            $handle->attach_kernel_driver($iface_number);
        }
        $handle->close();
    } else {
        say STDERR "Unable to open K8101";
    }
    $ctx->exit();
}

sub read_data {
    my $ctx = USB::LibUSB->init();
    my $handle = $ctx->open_device_with_vid_pid($vendor, $product);
    if ($handle){
        my $detached = -1;
        if ($handle->kernel_driver_active($iface_number)){
            say "A kernel driver is active for interface, Detaching...";
            $detached = $handle->detach_kernel_driver($iface_number);
        } else {
            # say "No kernel driver active.";
        }
        my $ret = $handle->claim_interface($iface_number);
        die "claim_interface returned $ret" if $ret;
        
        # actual read
        my $data = eval { $handle->bulk_transfer_read(130, 10, 10) };
        if ($@) {
            if ($@ =~ /timed out/){
                say 'No pending event.';
            } else {
                say $@;
            }
        } else {
            my @bytes = unpack("C*", $data);
            say "read " . join(',', unpack("(H2)*", $data));
            if ($bytes[2] == 0xff && $bytes[3] == 0x04){
                say "-> short press";
            } elsif ($bytes[2] == 0xaa && $bytes[3] == 0xaf){
                say "-> long press";
            }
        }
        $handle->release_interface($iface_number);
        if ($detached == 0){
            say "Reattaching driver.";
            $handle->attach_kernel_driver($iface_number);
        }
        $handle->close();
    } else {
        say STDERR "Unable to open K8101";
    }
    $ctx->exit();
}
