/*
 * Copyright (c) 2025, Your Name or Organization. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package sun.nio.ch;

import java.io.IOException;
import jdk.internal.misc.Unsafe;

/**
 * Poller implementation based on the HaikuOS wait_for_objects facility.
 */
class HQueuePoller extends Poller {
    private static final int MAX_EVENTS_TO_POLL = 512;
    private static final Unsafe unsafe = Unsafe.getUnsafe();

    private final long address; // Memory address for object_wait_info array
    private final int events;   // Event mask (EVENT_READ or EVENT_WRITE)

    HQueuePoller(boolean read) throws IOException {
        super(read);
        this.address = HQueue.allocatePollArray(MAX_EVENTS_TO_POLL);
        this.events = read ? HQueue.EVENT_READ : HQueue.EVENT_WRITE;
    }

    @Override
    int fdVal() {
        // No single fd for wait_for_objects; return -1 as placeholder
        return -1;
    }

    @Override
    void implRegister(int fdVal) throws IOException {
        // Find an empty slot in the poll array to register the fd
        for (int i = 0; i < MAX_EVENTS_TO_POLL; i++) {
            long eventAddress = HQueue.getEvent(address, i);
            if (HQueue.getDescriptor(eventAddress) == 0 && HQueue.getType(eventAddress) == 0) {
                HQueue.setDescriptor(eventAddress, fdVal);
                HQueue.setType(eventAddress, HQueue.TYPE_FD);
                HQueue.setEvents(eventAddress, events);
                return;
            }
        }
        throw new IOException("No free slots in poll array");
    }

    @Override
    void implDeregister(int fdVal) {
        // Clear the slot for the given fd
        for (int i = 0; i < MAX_EVENTS_TO_POLL; i++) {
            long eventAddress = HQueue.getEvent(address, i);
            if (HQueue.getDescriptor(eventAddress) == fdVal && HQueue.getType(eventAddress) == HQueue.TYPE_FD) {
                HQueue.setDescriptor(eventAddress, 0);
                HQueue.setType(eventAddress, 0);
                HQueue.setEvents(eventAddress, 0);
                break;
            }
        }
    }

    @Override
    int poll(int timeout) throws IOException {
        // Poll using wait_for_objects via native method
        int n = HQueue.heventPoll(address, MAX_EVENTS_TO_POLL);
        int i = 0;
        while (i < n) {
            long eventAddress = HQueue.getEvent(address, i);
            int fdVal = HQueue.getDescriptor(eventAddress);
            int eventType = HQueue.getType(eventAddress);
            int eventFlags = HQueue.getEvents(eventAddress);
            if (eventType == HQueue.TYPE_FD && (eventFlags & events) != 0) {
                polled(fdVal);
                // Emulate one-shot behavior by clearing events
                HQueue.setEvents(eventAddress, 0);
            }
            i++;
        }
        return n;
    }

    // Clean up resources
    void close() {
        HQueue.freePollArray(address);
    }
}
