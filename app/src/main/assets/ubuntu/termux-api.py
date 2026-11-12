#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""UbuntuTerm Android API client.

This is a re-implementation of the official ``termux-api`` client
(https://github.com/termux/termux-api-package) for the Ubuntu guest of UbuntuTerm.

The protocol is the one used by Termux:API:

* The client creates two abstract UNIX sockets, one for the API result and one for the
  API input (stdin).
* The client connects to the abstract socket ``com.ubutumerm.app.api://listen`` that is
  served by the UbuntuTerm application and sends a length prefixed command line that
  mimics an ``am broadcast`` invocation.
* The application replies with a single NUL byte once the request has been parsed and
  dispatched, then writes the API result into the result socket.

Unlike the original client this implementation is written in Python so that it can run
inside the glibc based Ubuntu guest (the original client is a Bionic/Android binary).
"""

import errno
import os
import socket
import struct
import sys
import threading
import time
import uuid

LISTEN_ADDRESS = "\0com.ubutumerm.app.api://listen"
CLIENT_VERSION = "0.60.0-ubutumerm"
CONNECT_TIMEOUT = 30.0
IO_TIMEOUT = 300.0


def log(msg):
    sys.stderr.write("termux-api: %s\n" % msg)
    sys.stderr.flush()


def process_starttime(pid):
    """Return the start time of a process as reported in /proc/<pid>/stat."""
    try:
        with open("/proc/%d/stat" % pid, "rb") as f:
            data = f.read().decode("utf-8", "replace")
        idx = data.rfind(")")
        fields = data[idx + 2:].split()
        # field 22 (1-based) of /proc/pid/stat -> index 19 here
        return int(fields[19])
    except Exception:
        return -1


def build_cmdline(method, args, input_uuid, output_uuid):
    """Build the command line understood by the Android side parser."""
    parts = []
    parts.append('--es socket_input "%s"' % input_uuid)
    parts.append('--es socket_output "%s"' % output_uuid)
    parts.append("--ei api_server_pid %d" % os.getpid())
    parts.append("--ei api_server_uid %d" % os.getuid())
    parts.append("--ei api_server_starttime %d" % process_starttime(os.getpid()))
    parts.append('--es api_method "%s"' % method.replace('"', '\\"'))

    index = 0
    while index < len(args):
        arg = args[index]
        if arg in ("--es", "-e", "--esa") and index + 1 < len(args):
            key = args[index + 1]
            value = args[index + 2] if index + 2 < len(args) else ""
            parts.append('%s %s "%s"' % (arg, key, value.replace('"', '\\"')))
            index += 3
        else:
            parts.append(arg)
            index += 1

    return " ".join(parts)


def connect_listen_socket():
    sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    sock.settimeout(CONNECT_TIMEOUT)
    sock.connect(LISTEN_ADDRESS)
    return sock


def pump_stdin(sock, stop_event):
    """Forward stdin to the socket the application reads the API input from."""
    try:
        conn, _ = sock.accept()
    except Exception as exc:
        if stop_event.is_set():
            return
        log("failed to accept API input connection: %s" % exc)
        return
    try:
        while True:
            chunk = sys.stdin.buffer.read1(4096) if hasattr(sys.stdin.buffer, "read1") \
                else sys.stdin.buffer.read(4096)
            if not chunk:
                break
            conn.sendall(chunk)
    except Exception:
        pass
    finally:
        try:
            conn.shutdown(socket.SHUT_WR)
        except Exception:
            pass
        try:
            conn.close()
        except Exception:
            pass


def main(argv):
    if len(argv) == 2 and argv[1] == "--version":
        sys.stdout.write(CLIENT_VERSION + "\n")
        return 0

    if len(argv) < 2:
        log("usage: termux-api <method> [arguments]")
        return 1

    method = argv[1]
    args = argv[2:]

    input_uuid = str(uuid.uuid4())
    output_uuid = str(uuid.uuid4())

    input_server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    output_server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    input_server.bind("\0" + input_uuid)
    output_server.bind("\0" + output_uuid)
    input_server.listen(1)
    output_server.listen(1)

    cmdline = build_cmdline(method, args, input_uuid, output_uuid)
    payload = cmdline.encode("utf-8")

    try:
        listen = connect_listen_socket()
    except socket.error as exc:
        log("cannot reach the UbuntuTerm API service: %s" % exc)
        if exc.errno in (errno.ECONNREFUSED, errno.ENOENT):
            log("is the UbuntuTerm application running?")
        return 1

    try:
        listen.sendall(struct.pack(">H", len(payload)))
        listen.sendall(payload)
        listen.settimeout(CONNECT_TIMEOUT)
        ack = listen.recv(1024)
    except socket.error as exc:
        log("failed to send the API request: %s" % exc)
        return 1
    finally:
        try:
            listen.close()
        except Exception:
            pass

    if not ack:
        log("no response from the UbuntuTerm API service")
        return 1

    if ack != b"\x00":
        sys.stderr.write(ack.decode("utf-8", "replace"))
        sys.stderr.flush()
        return 1

    stop_event = threading.Event()
    stdin_thread = threading.Thread(target=pump_stdin, args=(input_server, stop_event), daemon=True)
    stdin_thread.start()

    exit_code = 0
    try:
        output_server.settimeout(IO_TIMEOUT)
        conn, _ = output_server.accept()
        conn.settimeout(IO_TIMEOUT)
        while True:
            data = conn.recv(4096)
            if not data:
                break
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
    except socket.timeout:
        log("timed out while waiting for the API result")
        exit_code = 1
    except Exception as exc:
        log("failed to read the API result: %s" % exc)
        exit_code = 1
    finally:
        stop_event.set()
        try:
            conn.close()
        except Exception:
            pass
        try:
            input_server.close()
        except Exception:
            pass
        try:
            output_server.close()
        except Exception:
            pass

    return exit_code


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except KeyboardInterrupt:
        sys.exit(130)
