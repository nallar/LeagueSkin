package nallar.leagueskin.riotfiles;

import nallar.leagueskin.models.Obj;
import nallar.leagueskin.models.Skn;
import nallar.leagueskin.util.Throw;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Parses Riot Archive Format (.RAF) files
 * NOT THREADSAFE
 */
public class Raf {
    private static final boolean DEBUG_PARSE = Boolean.getBoolean("leagueskin.debug.parse");
    private static final boolean DEBUG_DUMP = true; //Boolean.getBoolean("leagueskin.debug.dump");
    private static final byte[] inflateBuffer = new byte[1024 * 1024];
    private static final byte[] deflateBuffer = new byte[1024 * 1024];
    private final Path location;
    private final String name;
    private final MappedByteBuffer buffer;
    private List<RAFEntry> rafEntryList = new ArrayList<>();
    private Set<String> fileNames = new HashSet<>();
    private Set<String> shortFileNames = new HashSet<>();

    public Raf(Path location) {
        this.location = location;
        String name = location.toString().replace("\\", "/");
        this.name = name.substring(name.lastIndexOf('/', name.lastIndexOf('/') - 1) + 1);
        MappedByteBuffer b;
        try (RandomAccessFile file = new RandomAccessFile(location.toFile(), "rw")) {
            b = file.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, file.length());
        } catch (IOException e) {
            throw new RuntimeException("Failed to open RAF file " + location, e);
        }
        b.order(ByteOrder.LITTLE_ENDIAN);
        this.buffer = b;
        parse();
    }

    private static void debug(String s) {
        System.out.println(s);
    }

    private static String humanReadableByteCount(long bytes, boolean si) {
        int unit = si ? 1000 : 1024;
        if (bytes < unit) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(unit));
        String pre = (si ? "kMGTPE" : "KMGTPE").charAt(exp - 1) + (si ? "" : "i");
        return String.format("%.1f %sB", bytes / Math.pow(unit, exp), pre);
    }

    /**
     * Returns RAF Hash for given name - Do not use for anything else, terrible hash function. So many collisions!
     *
     * @param name
     * @return
     */
    private static int rafHash(String name) {
        name = name.toLowerCase();
        int temp;
        int hash = 0;
        for (int i = 0; i < name.length(); i++) {
            hash = (hash << 4) + name.charAt(i);
            if (0 != (temp = (hash & 0xF0000000))) {
                hash = hash ^ (temp >> 24);
                hash = hash ^ temp;
            }
        }
        return hash;
    }

    private static byte[] decompress(byte[] input) {
        Inflater inflater = new Inflater();
        inflater.setInput(input);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while (!inflater.finished()) {
            int n;
            try {
                n = inflater.inflate(inflateBuffer);
                if (n == 0) {
                    debug("Needs input: " + inflater.needsInput());
                    debug("Needs dictionary: " + inflater.needsInput());
                    throw new RuntimeException("Failed to decompress, bad input");
                }
            } catch (DataFormatException e) {
                throw Throw.sneaky(e);
            }
            baos.write(inflateBuffer, 0, n);
        }
        return baos.toByteArray();
    }

    private static byte[] compress(byte[] input) {
        Deflater deflater = new Deflater();
        deflater.setInput(input);
        deflater.finish();
        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            while (!deflater.finished()) {
                int n = deflater.deflate(deflateBuffer);
                baos.write(deflateBuffer, 0, n);
            }
            compressed = baos.toByteArray();
        } catch (IOException e) {
            throw Throw.sneaky(e);
        }
        deflater.end();
        if (!Arrays.equals(decompress(compressed), input)) {
            throw new RuntimeException("Mismatch!");
        }
        return compressed;
    }

    public Collection<RAFEntry> getEntries() {
        return new ArrayList<>(rafEntryList);
    }

    public void fixManifest() {
        rafEntryList.forEach(ReleaseManifest.INSTANCE::setSize);
    }

    public void update(Map<String, Object> replacements) {
        if (Collections.disjoint(shortFileNames, replacements.keySet())) {
            return;
        }
        //dump();
        //debug("In common for " + this);
        List<RAFEntry> sortedRafList = new ArrayList<>(rafEntryList);
        Collections.sort(sortedRafList, new Comparator<RAFEntry>() {
            public int compare(RAFEntry s1, RAFEntry s2) {
                return Integer.compareUnsigned(s1.offset, s2.offset);
            }
        });

        // Rename .raf.dat to .raf.dat.bak
        // Open for reading, memory mapped.
        Path rafDat = Paths.get(location.toString() + ".dat");
        Path rafDatBak = Paths.get(location.toString() + ".dat.bak");
        try {
            Files.move(rafDat, rafDatBak);
        } catch (IOException e1) {
            if (Files.exists(rafDatBak)) {
                try {
                    Files.delete(rafDat);
                } catch (IOException e2) {
                    throw Throw.sneaky(e2);
                }
            } else {
                throw Throw.sneaky(e1);
            }
        }
        try (
                RandomAccessFile old = new RandomAccessFile(rafDatBak.toFile(), "r");
                RandomAccessFile created = new RandomAccessFile(rafDat.toFile(), "rw")
        ) {
            for (RAFEntry entry : sortedRafList) {
                int expectedSize = entry.size;
                int offset = (int) created.getFilePointer();
                int decompressedSize = 0;
                Object replacement = replacements.get(entry.getShortName().toLowerCase());
                if (old.getFilePointer() != entry.offset) {
                    debug("FP should already be at correct offset in old data. Should be " + entry.offset + ", got " + old.getFilePointer());
                    old.seek(entry.offset);
                }
                byte[] oldData = new byte[entry.size];
                old.readFully(oldData);
                if (replacement == null) {
                    // Copy old
                    created.write(oldData);
                } else {
                    boolean compressed = false;
                    if (entry.size >= 2) {
                        int magic = ((oldData[0] & 0xff) << 8) | (oldData[1] & 0xff);
                        compressed = (magic == 0x7801 || magic == 0x789c);
                    }
                    //debug("Replacing " + entry.getShortName() + " with " + replacement + ", compressed = " + compressed);
                    // Write new
                    byte[] replacementData;
                    if (replacement instanceof Path) {
                        replacementData = Files.readAllBytes((Path) replacement);
                    } else if (replacement instanceof Obj) {
                        byte[] decompressed = decompress(oldData);
                        Skn skn = new Skn(entry.name, ByteBuffer.wrap(decompressed));
                        Obj replacementObj = (Obj) replacement;
                        if (replacementObj.getVertexes().length != skn.getVertexes().length) {
                            debug("Mismatched vertex counts replacing " + entry);
                            replacementData = decompressed;
                        } else {
                            skn.setVertexes(replacementObj.getVertexes());
                            skn.setIndices(replacementObj.getIndices());
                            replacementData = skn.update();
                        }
                    } else {
                        throw new RuntimeException("Unexpected type for replacement " + replacement.getClass());
                    }
                    decompressedSize = replacementData.length;
                    if (compressed) {
                        replacementData = compress(replacementData);
                    }
                    expectedSize = replacementData.length;
                    entry.expectedRawBytes = replacementData;
                    created.write(replacementData);
                    //debug("Wrote file " + entry.name + ". Old size: " + entry.size + ", old offset: " + entry.offset);
                }
                entry.offset = offset;
                entry.size = (int) created.getFilePointer() - offset;
                if (replacement != null) {
                    //debug("New size: " + entry.size + ", new offset: " + entry.offset);
                    ReleaseManifest.INSTANCE.setSize(entry.name, entry.size, decompressedSize);
                }
                if (entry.size != expectedSize) {
                    debug("Mismatched sizes! Expected " + expectedSize + ", got " + entry.size);
                    throw null;
                }
            }
        } catch (IOException e) {
            try {
                Files.move(rafDatBak, rafDat);
            } catch (IOException e1) {
                throw Throw.sneaky(e1);
            }
            throw new RuntimeException("Failed to open RAF.dat file " + rafDatBak, e);
        }

        try {
            Files.delete(rafDatBak);
        } catch (IOException e) {
            throw Throw.sneaky(e);
        }

        for (RAFEntry entry : rafEntryList) {
            buffer.position(entry.rafOffset + 4); // skip hash
            buffer.putInt(entry.offset);
            buffer.putInt(entry.size);
        }
        buffer.force();

        rafEntryList.forEach(Raf.RAFEntry::checkExpectedBytes);

        List<RAFEntry> oldList = new ArrayList<>(rafEntryList);
        rafEntryList.clear();
        parse();
        for (int i = 0; i < rafEntryList.size(); i++) {
            RAFEntry old = oldList.get(i);
            RAFEntry n = rafEntryList.get(i);
            if (!old.toString().equals(n.toString())) {
                debug("Mismatch");
                debug(String.valueOf(old));
                debug(String.valueOf(n));
            }
        }
    }

    private void parse() {
        buffer.position(0);

        // Header
        int magic = buffer.getInt();
        int version = buffer.getInt();
        int riotIndex = buffer.getInt();
        int fileListOffset = buffer.getInt();
        int stringTableOffset = buffer.getInt();

        if (DEBUG_PARSE) {
            debug("Magic is " + Integer.toHexString(magic));
            debug("Version is " + Integer.toHexString(version));
            debug("Riot index is " + Integer.toHexString(riotIndex));
            debug("File list offset is " + Integer.toHexString(fileListOffset));
            debug("String table offset is " + Integer.toHexString(stringTableOffset));
        }

        // File list
        buffer.position(fileListOffset);
        int count = buffer.getInt();
        if (DEBUG_PARSE) {
            debug("Entries in file list: " + count);
        }

        for (int i = 0; i < count; i++) {
            int rafOffset = buffer.position();
            int hash = buffer.getInt();
            int offset = buffer.getInt();
            int size = buffer.getInt();
            int stringTableIndex = buffer.getInt();
            if (DEBUG_PARSE) {
                debug("Hash is " + hash);
                debug("Offset is " + offset);
                debug("Size is " + size);
                debug("String table index is " + stringTableIndex);
            }
            rafEntryList.add(new RAFEntry(rafOffset, offset, size, stringTableIndex));
        }

        // String table
        buffer.position(stringTableOffset);
        int stringTableSize = buffer.getInt();
        if (count != buffer.getInt()) {
            throw new RuntimeException("Disagreeing counts - string table count does not match file count");
        }

        for (int i = 0; i < count; i++) {
            int offset = buffer.getInt();

            int length = buffer.getInt();
            byte[] bytes = new byte[length - 1]; // -1 to remove null char, ignored.
            int oldPos = buffer.position();
            buffer.position(offset + stringTableOffset);
            buffer.get(bytes);
            String name;
            try {
                name = new String(bytes, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                throw Throw.sneaky(e);
            }
            for (RAFEntry rafEntry : rafEntryList) {
                if (rafEntry.stringTableIndex == i) {
                    rafEntry.name = name;
                    shortFileNames.add(rafEntry.getShortName().toLowerCase());
                    break;
                }
            }
            fileNames.add(name);
            if (DEBUG_PARSE) {
                debug("String " + i + " offset " + offset);
                debug("String " + i + " length " + length);
                debug("String " + i + " is \"" + name + '"');
            }
            buffer.position(oldPos);
        }
    }

    public void dump() {
        int entries = rafEntryList.size();
        int size = 0;
        for (RAFEntry rafEntry : rafEntryList) {
            if (DEBUG_DUMP) {
                debug(rafEntry + " in " + name);
            }
            size += rafEntry.size;
        }
        debug(entries + " entries in " + name + " RAF of total size " + humanReadableByteCount(size, false));
    }

    public String toString() {
        return name + " RAF with " + rafEntryList.size() + " entries";
    }

    public class RAFEntry {
        int rafOffset;
        public String name;
        int offset;
        int size;
        int stringTableIndex;
        byte[] expectedRawBytes;

        public RAFEntry(int rafOffset, int offset, int size, int stringTableIndex) {
            this.rafOffset = rafOffset;
            this.offset = offset;
            this.size = size;
            this.stringTableIndex = stringTableIndex;
        }

        public String toString() {
            return name + " is of size " + humanReadableByteCount(size, false) + " at offset " + offset;
        }

        public String getShortName() {
            return name.substring(name.lastIndexOf('/') + 1);
        }

        public void checkExpectedBytes() {
            if (expectedRawBytes != null && !Arrays.equals(expectedRawBytes, getRawBytes())) {
                throw new RuntimeException("Mismatch");
            }
        }

        public byte[] getRawBytes() {
            Path rafDat = Paths.get(location.toString() + ".dat");
            byte[] data;
            try (RandomAccessFile raf = new RandomAccessFile(rafDat.toFile(), "r")) {
                data = new byte[size];
                raf.seek(offset);
                raf.readFully(data);
            } catch (IOException e) {
                throw Throw.sneaky(e);
            }
            return data;
        }

        public byte[] getBytes() {
            byte[] data = getRawBytes();
            if (size >= 2) {
                short magic = (short) ((((data[0] & 0xff) << 8)) | (data[1] & 0xff));
                if (magic == 0x7801 || magic == 0x789c) {
                    data = decompress(data);
                }
            }
            return data;
        }
    }
}
