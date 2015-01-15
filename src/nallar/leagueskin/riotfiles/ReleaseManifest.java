package nallar.leagueskin.riotfiles;

import nallar.leagueskin.util.RiotUtil;
import nallar.leagueskin.util.Throw;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReleaseManifest {
    public static final ReleaseManifest INSTANCE = new ReleaseManifest(RiotUtil.releaseDirectory().resolve("releasemanifest"));
    private static final int EXPECTED_MAX_SIZE = 1024 * 1024 * 75; // 100MB
    private final Path location;
    private final String name;
    private final MappedByteBuffer buffer;
    private final Map<String, FileEntry> fileEntryMap = new HashMap<>();
    private DirEntry[] dirEntries;
    private FileEntry[] fileEntries;
    private String[] strings;

    public ReleaseManifest(Path location) {
        Path backup = location.getParent().resolve("releasemanifest.bak");
        if (!Files.exists(backup)) {
            try {
                Files.copy(location, backup);
            } catch (IOException e) {
                throw Throw.sneaky(e);
            }
        }
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
        sanityCheck();
        throw null;
    }

    private static void debug(String s) {
        System.out.println(s);
    }

    public void sanityCheck() {
        for (FileEntry fileEntry : fileEntries) {
            fileEntry.sanityCheck();
        }
    }

    public void setSize(Raf.RAFEntry entry) {
        String fullName = entry.name;
        if (!fullName.startsWith("/")) {
            fullName = '/' + fullName;
        }
        int compressedSize = entry.size;
        FileEntry fileEntry = fileEntryMap.get(fullName);
        if (fileEntry == null) {
            return;
        }
        if (fileEntry.compressedSize != compressedSize) {
            int uncompressedSize = entry.getBytes().length;
            fileEntry.setSize(compressedSize, uncompressedSize, buffer);
        }
    }

    public void setSize(String fullName, int compressedSize, int uncompressedSize) {
        if (fullName.startsWith("DATA/")) {
            fullName = '/' + fullName;
        }
        FileEntry fileEntry = fileEntryMap.get(fullName);
        if (fileEntry == null) {
            throw new RuntimeException("Didn't find " + fullName + " in releasemanifest");
        }
        if (fileEntry.compressedSize != compressedSize) {
            fileEntry.setSize(compressedSize, uncompressedSize, buffer);
        }
    }

    private void parse() {
        buffer.position(0);

        int magic = buffer.getInt();
        if (magic != 0x4D534C52) {
            debug("Wrong magic, got 0x" + Integer.toHexString(magic) + ", expected 0x4D534C52");
            return;
        }

        int fileType = buffer.getInt(); // Another "magic" value... not really sure what this does
        if (fileType != 0x00010001) {
            debug("Wrong fileType, got 0x" + Integer.toHexString(fileType) + ", expected 0x00010001");
            return;
        }

        int numberItems = buffer.getInt();
        int checkCount = numberItems; // Decrement this as we go along, should reach 0 at end if we have parsed correct number of items;

        int version = buffer.getInt();

        final int dirHeaderPos = 16;
        if (buffer.position() != dirHeaderPos) {
            debug("Unexpected dirHeaderPos, expected 16 got " + buffer.position());
        }

        int directoryCount = buffer.getInt();
        debug("Directories: " + directoryCount);
        //checkCount -= directoryCount;

        dirEntries = new DirEntry[directoryCount];
        for (int i = 0; i < directoryCount; i++) {
            dirEntries[i] = new DirEntry(buffer);
        }

        final int fileHeaderPos = dirHeaderPos + 4 + (directoryCount * 20);
        if (buffer.position() != fileHeaderPos) {
            debug("Unexpected fileHeaderPos, expected 16 got " + buffer.position());
        }
        int fileCount = buffer.getInt();
        checkCount -= fileCount;
        if (checkCount != 0) {
            //throw new RuntimeException("Mismatch! " + checkCount);
            // This always fails... is entry count not correct?
        }

        fileEntries = new FileEntry[fileCount];
        for (int i = 0; i < fileCount; i++) {
            fileEntries[i] = new FileEntry(buffer);
        }

        final int stringHeaderPos = fileHeaderPos + 4 + (fileCount * 44);
        if (buffer.position() != stringHeaderPos) {
            debug("Unexpected fileHeaderPos, expected 16 got " + buffer.position());
        }
        int stringCount = buffer.getInt();
        buffer.getInt(); //Unknown int.

        strings = new String[stringCount];

        byte[] buf = new byte[256];

        for (int i = 0; i < stringCount; i++) {
            int part = 0;
            byte read;
            do {
                read = buffer.get();
                buf[part++] = read;
            } while (read != 0);
            strings[i] = new String(buf, 0, part - 1);
        }

        assert buffer.remaining() == 0;

        for (int i = 0; i < directoryCount; i++) {
            DirEntry entry = dirEntries[i];
            if (entry.nameIndex == 0) {
                entry.name = "";
            } else {
                entry.name = strings[entry.nameIndex];
            }
        }

        for (int i = 0; i < fileCount; i++) {
            FileEntry entry = fileEntries[i];
            if (entry.nameIndex == 0) {
                entry.name = "";
            } else {
                entry.name = strings[entry.nameIndex];
            }
        }

        recursiveDirectorySearch();

        for (FileEntry fileEntry : fileEntries) {
            fileEntryMap.put(fileEntry.getPath(), fileEntry);
        }
    }

    private void recursiveDirectorySearch() {
        recursiveDirectorySearch(0, null);
    }

    private DirEntry recursiveDirectorySearch(int entryNumber, DirEntry lastDir) {
        DirEntry entry = this.dirEntries[entryNumber];
        DirEntry currentEntry = entry;
        int fileOffset = entry.fileIndex;
        if (lastDir != null) {
            int lastFileOffset = lastDir.fileIndex;
            for (int fiOffset = lastFileOffset; fiOffset < fileOffset; fiOffset++) {
                FileEntry fileEntry = fileEntries[fiOffset];
                fileEntry.parentFolder = lastDir;
                lastDir.files.add(fileEntry);
            }
        }
        try {
            for (int dirOffset = 0; dirOffset < entry.subdirCount; dirOffset++) {
                int innerDirEntry = entry.subdirIndex + dirOffset;
                DirEntry nextEntry = dirEntries[innerDirEntry];
                nextEntry.parentFolder = entry;
                currentEntry = recursiveDirectorySearch(innerDirEntry, currentEntry);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return currentEntry;
    }

    public static class DirEntry {
        public List<FileEntry> files = new ArrayList<>();
        public DirEntry parentFolder;
        String name;
        int nameIndex;
        int subdirIndex;
        int subdirCount;
        int fileIndex;
        int fileCount;

        public DirEntry() {
        }

        public DirEntry(ByteBuffer buffer) {
            nameIndex = buffer.getInt();
            subdirIndex = buffer.getInt();
            subdirCount = buffer.getInt();
            fileIndex = buffer.getInt();
            fileCount = buffer.getInt();
        }

        public String getPath() {
            if (this.parentFolder != null) {
                return parentFolder.getPath() + '/' + name;
            }
            return name;
        }

        public String toString() {
            return
                    name +
                            " nameIdx: " + nameIndex +
                            " subdirIdx: " + subdirIndex +
                            " subdirCount: " + subdirCount +
                            " fileIdx: " + fileIndex +
                            " fileCount: " + fileCount;
        }
    }

    public static class FileEntry {
        public DirEntry parentFolder;
        int offset;
        String name;
        int nameIndex;
        int version;
        int size;
        int compressedSize;

        public FileEntry() {
        }

        public FileEntry(ByteBuffer buffer) {
            offset = buffer.position();
            nameIndex = buffer.getInt();
            version = buffer.getInt();
            buffer.position(buffer.position() + 16); // skip md5[16]
            buffer.getInt(); // skip uint flags
            size = buffer.getInt();
            compressedSize = buffer.getInt();
            buffer.getInt(); // skip uint unk1
            buffer.getInt(); // skip uint unk4
        }

        public String toString() {
            return
                    getPath() +
                            " nameIdx: " + nameIndex +
                            " parentFolder: " + parentFolder +
                            " version: " + version +
                            " size: " + size +
                            " compressedSize: " + compressedSize;
        }

        public String getPath() {
            if (this.parentFolder != null) {
                return parentFolder.getPath() + '/' + name;
            }
            return name;
        }

        public void setSize(int compressedSize, int uncompressedSize, ByteBuffer byteBuffer) {
            size = uncompressedSize;
            this.compressedSize = compressedSize;
            sanityCheck();

            byteBuffer.position(offset + 28);
            byteBuffer.putInt(size);
            byteBuffer.putInt(compressedSize);
        }

        public void sanityCheck() {
            if (compressedSize < 0 || compressedSize > EXPECTED_MAX_SIZE) {
                throw new RuntimeException("Unexpected size: " + compressedSize + " in " + toString());
            }
            if (size < 0 || size > EXPECTED_MAX_SIZE) {
                throw new RuntimeException("Unexpected size: " + size + " in " + toString());
            }
            if (name == null || name.isEmpty()) {
                throw new RuntimeException("No name in " + toString());
            }
            if (parentFolder == null) {
                debug("No parent folder in " + toString()); // Expected? Some .luaobjs aren't in any folders. Why?
            }
            if (offset < 1000) {
                throw new RuntimeException("Unexpected offset - too low in " + toString());
            }
        }
    }

    /*
        uint magic;
        uint type;
        uint entries; // dir + files
        uint version;
        //dirsheader
        uint dirCount;
        //dirs
        struct {
            uint nameIndex;
            uint subdirIndex;
            uint subdirCount;
            uint fileIndex;
            uint fileCount;
        } dir[dirCount];
        //fileheader
        uint fileCount;
        //files
        struct {
            uint nameIndex;
            uint version;
            char md5[16];
            uint flags;
            uint size;
            uint compressedSize;
            uint unk1;
            uint unk4; //30375612
        }files[fileCount];
        //string table header
        uint stringCount;
        uint dataSize;
        //string table
        struct {
            string name;
        }strings[stringCount];
     */
}