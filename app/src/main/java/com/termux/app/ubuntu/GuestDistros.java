package com.termux.app.ubuntu;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of all Linux distributions supported by UbuntuTerm.
 *
 * <p>Ubuntu is the default distro. Arch and Kali are available as additional guests.
 */
public final class GuestDistros {

    private GuestDistros() {
    }

    private static final Map<String, GuestDistro> DISTROS = new LinkedHashMap<>();

    static {
        DISTROS.put(UbuntuDistro.UBUNTU.id(), UbuntuDistro.UBUNTU);
        DISTROS.put(ArchDistro.ARCH.id(), ArchDistro.ARCH);
    }

    /** The default distro of UbuntuTerm (Ubuntu). */
    public static GuestDistro defaultDistro() {
        return UbuntuDistro.UBUNTU;
    }

    /** Look up a distro by its internal identifier. */
    public static GuestDistro get(String id) {
        return DISTROS.get(id);
    }

    /** All supported distros in UI order. */
    public static java.util.List<GuestDistro> all() {
        return java.util.List.copyOf(DISTROS.values());
    }

    /** Whether a distro with the given id is supported. */
    public static boolean contains(String id) {
        return DISTROS.containsKey(id);
    }
}
