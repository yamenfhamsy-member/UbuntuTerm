#!/usr/bin/perl
#
# UbuntuTerm Android API client (Perl implementation).
#
# This is a re-implementation of the official "termux-api" client
# (https://github.com/termux/termux-api-package) for the Ubuntu guest of UbuntuTerm.
#
# The protocol is the one used by Termux:API:
#
#   1. The client creates two abstract UNIX sockets, one for the API result and one for
#      the API input (stdin).
#   2. The client connects to the abstract socket "com.ubutumerm.app.api://listen" that is
#      served by the UbuntuTerm application and sends a length prefixed command line that
#      mimics an "am broadcast" invocation.
#   3. The application answers with a single NUL byte once the request has been parsed and
#      dispatched and then writes the API result into the result socket.
#
# Perl is part of the Ubuntu base image, therefore the API works directly after the first
# start, without installing additional packages.
#
# This program is free software: you can redistribute it and/or modify it under the terms
# of the GNU General Public License as published by the Free Software Foundation, either
# version 3 of the License, or (at your option) any later version.

use strict;
use warnings;

use constant AF_UNIX => 1;
use constant SOCK_STREAM => 1;
use constant SOL_SOCKET => 1;
use constant SO_PEERCRED => 17;

my $LISTEN_ADDRESS = "com.ubutumerm.app.api://listen";
my $CLIENT_VERSION = "0.60.0-ubutumerm";
my $CONNECT_TIMEOUT = 30;
my $IO_TIMEOUT = 300;

sub log_error {
    my ($message) = @_;
    print STDERR "termux-api: $message\n";
}

# Build a sockaddr_un structure for an abstract socket.
#
# The name of an abstract socket is the string that follows the leading NUL byte. The
# structure must NOT be padded with NUL bytes: the kernel uses the length of the passed
# address as the length of the socket name, and Android's LocalServerSocket creates the
# socket with the exact name.
sub sockaddr_un {
    my ($name) = @_;
    return pack("S", AF_UNIX) . "\0" . $name;
}

# Start time of a process, read from /proc/<pid>/stat (field 22).
sub process_starttime {
    my ($pid) = @_;
    open(my $fh, "<", "/proc/$pid/stat") or return -1;
    my $line = <$fh>;
    close($fh);
    return -1 unless defined $line;
    my $idx = rindex($line, ")");
    return -1 if $idx < 0;
    my @fields = split(/\s+/, substr($line, $idx + 2));
    return -1 if @fields < 20;
    return $fields[19] + 0;
}

# Quote a value the way the Android side parser expects it.
sub quote_value {
    my ($value) = @_;
    $value =~ s/\\/\\\\/g;
    $value =~ s/"/\\"/g;
    return '"' . $value . '"';
}

sub build_cmdline {
    my ($method, $args, $input_uuid, $output_uuid) = @_;
    my @args = @{ $args || [] };

    my @parts;
    push @parts, "--es socket_input " . quote_value($input_uuid);
    push @parts, "--es socket_output " . quote_value($output_uuid);
    push @parts, "--ei api_server_pid " . $$;
    push @parts, "--ei api_server_uid " . $<;
    push @parts, "--ei api_server_starttime " . process_starttime($$);
    push @parts, "--es api_method " . quote_value($method);

    my $index = 0;
    while ($index < @args) {
        my $arg = $args->[$index];
        if (($arg eq "--es" || $arg eq "-e" || $arg eq "--esa") && $index + 1 < @args) {
            my $key = $args->[$index + 1];
            my $value = ($index + 2 < @args) ? $args->[$index + 2] : "";
            push @parts, "$arg $key " . quote_value($value);
            $index += 3;
        } else {
            push @parts, $arg;
            $index += 1;
        }
    }

    return join(" ", @parts);
}

sub make_uuid {
    my @bytes = map { chr(int(rand(256))) } (1 .. 16);
    $bytes[6] = chr((ord($bytes[6]) & 0x0f) | 0x40);
    $bytes[8] = chr((ord($bytes[8]) & 0x3f) | 0x80);
    my $hex = join("", map { sprintf("%02x", ord($_)) } @bytes);
    return substr($hex, 0, 8) . "-" . substr($hex, 8, 4) . "-" . substr($hex, 12, 4) . "-" .
        substr($hex, 16, 4) . "-" . substr($hex, 20, 12);
}

sub main {
    if (@ARGV == 1 && $ARGV[0] eq "--version") {
        print "$CLIENT_VERSION\n";
        return 0;
    }

    if (@ARGV < 1) {
        log_error("usage: termux-api <method> [arguments]");
        return 1;
    }

    my $method = $ARGV[0];
    my @args = @ARGV[1 .. $#ARGV];

    my $input_uuid = make_uuid();
    my $output_uuid = make_uuid();

    socket(my $input_server, AF_UNIX, SOCK_STREAM, 0) or do {
        log_error("socket(): $!");
        return 1;
    };
    socket(my $output_server, AF_UNIX, SOCK_STREAM, 0) or do {
        log_error("socket(): $!");
        return 1;
    };

    bind($input_server, sockaddr_un($input_uuid)) or do {
        log_error("bind(): $!");
        return 1;
    };
    bind($output_server, sockaddr_un($output_uuid)) or do {
        log_error("bind(): $!");
        return 1;
    };

    listen($input_server, 1) or do {
        log_error("listen(): $!");
        return 1;
    };
    listen($output_server, 1) or do {
        log_error("listen(): $!");
        return 1;
    };

    my $cmdline = build_cmdline($method, \@args, $input_uuid, $output_uuid);
    my $payload = $cmdline;

    socket(my $listen, AF_UNIX, SOCK_STREAM, 0) or do {
        log_error("socket(): $!");
        return 1;
    };
    if (!connect($listen, sockaddr_un($LISTEN_ADDRESS))) {
        log_error("cannot reach the UbuntuTerm API service: $!");
        log_error("is the UbuntuTerm application running?");
        return 1;
    }

    my $length = pack("n", length($payload));
    if (!syswrite($listen, $length . $payload)) {
        log_error("failed to send the API request: $!");
        return 1;
    }

    my $ack = "";
    my $read = sysread($listen, $ack, 1024);
    if (!defined $read || $read == 0) {
        log_error("no response from the UbuntuTerm API service");
        return 1;
    }
    close($listen);

    if ($ack ne "\0") {
        print STDERR $ack;
        return 1;
    }

    # Fork: the child forwards stdin to the API, the parent copies the API result to stdout.
    my $pid = fork();
    if (!defined $pid) {
        log_error("fork(): $!");
        return 1;
    }

    if ($pid == 0) {
        close($output_server);
        my $connection;
        if (accept($connection, $input_server)) {
            binmode(STDIN);
            binmode($connection);
            my $buffer;
            while (1) {
                my $count = sysread(STDIN, $buffer, 4096);
                last if !defined $count || $count == 0;
                syswrite($connection, $buffer);
            }
            shutdown($connection, 1);
            close($connection);
        }
        exit(0);
    }

    close($input_server);
    my $connection;
    my $exit_code = 0;
    if (accept($connection, $output_server)) {
        binmode($connection);
        binmode(STDOUT);
        my $buffer;
        while (1) {
            my $count = sysread($connection, $buffer, 4096);
            last if !defined $count || $count == 0;
            print STDOUT $buffer;
        }
        close($connection);
    } else {
        log_error("failed to receive the API result: $!");
        $exit_code = 1;
    }
    close($output_server);

    waitpid($pid, 0);
    return $exit_code;
}

exit(main());
