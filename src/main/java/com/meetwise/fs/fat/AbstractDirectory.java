/*
 * $Id: AbstractDirectory.java 4975 2009-02-02 08:30:52Z lsantha $
 *
 * Copyright (C) 2003-2009 JNode.org
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but 
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public 
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; If not, write to the Free Software Foundation, Inc., 
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
 
package com.meetwise.fs.fat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Vector;
import com.meetwise.fs.util.LittleEndian;

/**
 * 
 *
 * @author Ewout Prangsma &lt;epr at jnode.org&gt;
 * @author Matthias Treydte &lt;waldheinz at gmail.com&gt;
 */
abstract class AbstractDirectory {
    
    final Vector<AbstractDirectoryEntry> entries;
    private final boolean readOnly;
    private final boolean isRoot;
    
    private boolean dirty;
    
    protected AbstractDirectory(
            int entryCount, boolean readOnly, boolean isRoot) {
        
        this.entries = new Vector<AbstractDirectoryEntry>(entryCount);
        this.entries.setSize(entryCount);
        this.readOnly = readOnly;
        this.isRoot = isRoot;
    }
    
    protected abstract void read(ByteBuffer data) throws IOException;
    
    protected abstract void write(ByteBuffer data) throws IOException;

    protected abstract long getStorageCluster();

    /**
     *
     * @param entryCount
     * @throws IOException
     * @see #sizeChanged(long)
     * @see #checkEntryCount(int) 
     */
    protected abstract void changeSize(int entryCount) throws IOException;

    /**
     * Checks if the entry count passed to {@link #changeSize(int)} is at
     * least one, as we always have at least the {@link ShortName#DOT dot}
     * entry.
     *
     * @param entryCount the entry count to check for validity
     * @throws IllegalArgumentException if {@code entryCount <= 0}
     */
    protected final void checkEntryCount(int entryCount)
            throws IllegalArgumentException {
        
        if (entryCount <= 0) throw new IllegalArgumentException(
                "invalid entry count of " + entryCount);
    }

    /**
     * 
     * @param newSize the new storage space for the directory in bytes
     * @see #changeSize(int) 
     */
    protected final void sizeChanged(long newSize) throws IOException {
        final long newCount = newSize / FatDirEntry.SIZE;
        if (newCount > Integer.MAX_VALUE)
            throw new IOException("directory too large");
        
        this.entries.setSize((int) newSize);
    }

    public final void setEntry(int idx, AbstractDirectoryEntry entry) {
        this.entries.set(idx, entry);
    }

    public final AbstractDirectoryEntry getEntry(int idx) {
        return this.entries.get(idx);
    }
    
    /**
     * Returns the current capacity of this {@code AbstractDirectory}.
     *
     * @return the number of entries this directory can hold in its current
     *      storage space
     * @see #changeSize(int)
     */
    public final int getCapacity() {
        return this.entries.capacity();
    }

    /**
     * The number of entries that are currently stored in this
     * {@code AbstractDirectory}.
     *
     * @return the current number of directory entries
     */
    public final int getEntryCount() {
        return this.entries.size();
    }
    
    public boolean isReadOnly() {
        return readOnly;
    }

    public final boolean isRoot() {
        return this.isRoot;
    }
    
    /**
     * Gets the number of directory entries in this directory
     * 
     * @return int
     */
    public int getSize() {
        return entries.size();
    }

    /**
     * Search for an entry with a given name.ext
     * 
     * @param nameExt
     * @return FatDirEntry null == not found
     */
    protected FatDirEntry getFatEntry(String nameExt) {
        final ShortName toFind = ShortName.get(nameExt);

        for (int i = 0; i < entries.size(); i++) {
            final AbstractDirectoryEntry entry = entries.get(i);

            if (entry != null && entry instanceof FatDirEntry) {
                final FatDirEntry fde = (FatDirEntry) entry;
                
                if (fde.getShortName().equals(toFind)) return fde;
            }
        }
        
        return null;
    }

    /**
     * Remove a chain or directory with a given name
     * 
     * @param nameExt
     */
    public void remove(String nameExt) throws IOException {
        final FatDirEntry entry = getFatEntry(nameExt);

        if (entry == null) throw new FileNotFoundException(nameExt);
        
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == entry) {
                entries.set(i, null);
                setDirty();
                return;
            }
        }
    }
    
    /**
     * Returns the dirty.
     * 
     * @return boolean
     */
    public boolean isDirty() {
        if (dirty)  return true;
        
        for (int i = 0; i < entries.size(); i++) {
            AbstractDirectoryEntry entry = entries.get(i);
            
            if ((entry != null) && (entry instanceof FatDirEntry)) {
                if (((FatDirEntry) entry).isDirty()) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Mark this directory as dirty.
     */
    protected final void setDirty() {
        this.dirty = true;
    }

    /**
     * Mark this directory as not dirty.
     */
    private final void resetDirty() {
        this.dirty = false;
    }
    
    /**
     * Sets the first two entries '.' and '..' in the directory
     * 
     * @param myCluster 
     * @param parentCluster
     */
    protected void initialize(long myCluster, long parentCluster) {
        final FatDirEntry dot = new FatDirEntry(this, ShortName.DOT);
        
        dot.setFlags(AbstractDirectoryEntry.F_DIRECTORY);
        dot.setStartCluster((int) myCluster);
        entries.set(0, dot);

        if (!isRoot) {
            final FatDirEntry dotDot = new FatDirEntry(this, ShortName.DOT_DOT);
            dotDot.setFlags(AbstractDirectoryEntry.F_DIRECTORY);
            dotDot.setStartCluster((int) parentCluster);
            entries.set(1, dotDot);
        }
    }
    

    /**
     * Flush the contents of this directory to the persistent storage
     */
    public void flush() throws IOException {
        final ByteBuffer data = ByteBuffer.allocate(
                entries.size() * AbstractDirectoryEntry.SIZE);

        final byte[] empty = new byte[32];
        
        for (int i = 0; i < entries.size(); i++) {
            final AbstractDirectoryEntry entry = entries.get(i);

            if (entry != null) {
                entry.write(data.array(), i * 32);
            } else {
                System.arraycopy(empty, 0, data.array(), i * 32, 32);
            }
        }

        write(data);

        resetDirty();
    }

    private AbstractDirectoryEntry parseEntry(byte[] src, int offset) {
        final int flags = LittleEndian.getUInt8(
                src, offset + AbstractDirectoryEntry.FLAGS_OFFSET);
        
        boolean r = (flags & AbstractDirectoryEntry.F_READONLY) != 0;
        boolean h = (flags & AbstractDirectoryEntry.F_HIDDEN) != 0;
        boolean s = (flags & AbstractDirectoryEntry.F_SYSTEM) != 0;
        boolean v = (flags & AbstractDirectoryEntry.F_LABEL) != 0;

        if (r && h && s && v) {
            // this is a LFN entry, don't need to parse it!
            return new FatLfnDirEntry(this, src, offset);
        }
        
        return new FatDirEntry(this, src, offset);
    }
    
    protected final void read() throws IOException {
        final ByteBuffer data = ByteBuffer.allocate(
                entries.size() * AbstractDirectoryEntry.SIZE);
                
        read(data);

        final byte[] src = data.array();

        for (int i = 0; i < entries.size(); i++) {
            int index = i * 32;
            if (src[index] == 0) {
                entries.set(i, null);
            } else {
                entries.set(i, parseEntry(src, index));
            }
        }
    }
}
