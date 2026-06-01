package me.cortex.voxy.common.world.other;

import com.mojang.serialization.Dynamic;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.util.Pair;
import me.cortex.voxy.common.world.other.Mapper.BiomeEntry;
import me.cortex.voxy.common.world.other.Mapper.StateEntry;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;


//There are independent mappings for biome and block states, these get combined in the shader and allow for more
// variaty of things
public class Mapper {
    private static final int BLOCK_STATE_TYPE = 1;
    private static final int BIOME_TYPE = 2;

    private final IMappingStorage storage;
    public static final long UNKNOWN_MAPPING = -1;
    public static final long AIR = 0;

    private final ReentrantLock blockLock = new ReentrantLock();
    private final ConcurrentHashMap<BlockState, StateEntry> block2stateEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<StateEntry> blockId2stateEntry = new ObjectArrayList<>();


    private final ReentrantLock biomeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, BiomeEntry> biome2biomeEntry = new ConcurrentHashMap<>(2000,0.75f, 10);
    private final ObjectArrayList<BiomeEntry> biomeId2biomeEntry = new ObjectArrayList<>();

    private Consumer<StateEntry> newStateCallback;
    private Consumer<BiomeEntry> newBiomeCallback;
    public Mapper(IMappingStorage storage) {
        this.storage = storage;
        if (Boolean.getBoolean("voxy.offlineImportMinimalMapper")) {
            return;
        }
        //Insert air since its a special entry (index 0)
        var airEntry = new StateEntry(0, Blocks.AIR.defaultBlockState());
        this.block2stateEntry.put(airEntry.state, airEntry);
        this.blockId2stateEntry.add(airEntry);

        this.loadFromStorage();
    }


    public static boolean isAir(long id) {
        //Note: air can mean void, cave or normal air, as the block state is remapped during ingesting
        return (id&(((1L<<20)-1)<<27)) == 0;
    }

    public static int getBlockId(long id) {
        return (int) ((id>>27)&((1<<20)-1));
    }

    public static int getBiomeId(long id) {
        return (int) ((id>>47)&0x1FF);
    }

    public static int getLightId(long id) {
        return (int) ((id>>56)&0xFF);
    }

    public static long withLight(long id, int light) {
        return (id&(~(0xFFL<<56)))|(Integer.toUnsignedLong(light&0xFF)<<56);
    }

    public static long withBlockBiome(long id, int block, int biome) {
        return (id&(0xFFL<<56))|(Integer.toUnsignedLong(block)<<27)|(Integer.toUnsignedLong(biome)<<47);
    }

    public static long airWithLight(int light) {
        return Integer.toUnsignedLong(light&0xFF)<<56;
    }

    public void setStateCallback(Consumer<StateEntry> stateCallback) {
        this.newStateCallback = stateCallback;
    }

    public void setBiomeCallback(Consumer<BiomeEntry> biomeCallback) {
        this.newBiomeCallback = biomeCallback;
    }

    private void loadFromStorage() {
        //TODO: FIXME: have/store the minecraft version the mappings are from (the data version)
        // SharedConstants.getGameVersion().dataVersion().id()
        // then use this to create an update path instead

        var mappings = this.storage.getIdMappingsData();
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();
        List<Pair<byte[], Integer>> sentryErrors = new ArrayList<>();

        boolean[] forceResave = new boolean[1];
        for (var entry : mappings.int2ObjectEntrySet()) {
            int entryType = entry.getIntKey()>>>30;
            int id = entry.getIntKey() & ((1<<30)-1);
            if (entryType == BLOCK_STATE_TYPE) {
                var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                if (sentry.state.isAir()) {
                    Logger.error("Deserialization was air, removed block");
                    sentryErrors.add(new Pair<>(entry.getValue(), id));
                    continue;
                }
                sentries.add(sentry);
                var oldEntry = this.block2stateEntry.putIfAbsent(sentry.state, sentry);
                if (oldEntry != null) {
                    //forceResave[0] |= true;
                    Logger.warn("Multiple mappings for blockstate, using old state, expect things to possibly go really badly. " + oldEntry.id + ":" + sentry.id + ":" + sentry.state );
                }
            } else if (entryType == BIOME_TYPE) {
                var bentry = BiomeEntry.deserialize(id, entry.getValue());
                bentries.add(bentry);
                var oldEntry = this.biome2biomeEntry.putIfAbsent(bentry.biome, bentry);
                if (oldEntry != null) {
                    Logger.warn("Multiple mappings for biome entry, preserving numeric id " + bentry.id
                            + " and using first id " + oldEntry.id + " for future writes: " + bentry.biome);
                }
            } else {
                throw new IllegalStateException("Unknown entryType");
            }
        }

        if (!sentryErrors.isEmpty()) {
            forceResave[0] |= true;
            //Insert garbage types into the mapping for those blocks, TODO:FIXME: Need to upgrade the type or have a solution to error blocks
            var rand = new Random();
            for (var error : sentryErrors) {
                while (true) {
                    var state = new StateEntry(error.right(), Block.BLOCK_STATE_REGISTRY.byId(rand.nextInt(Block.BLOCK_STATE_REGISTRY.size() - 1)));
                    if (this.block2stateEntry.put(state.state, state) == null) {
                        sentries.add(state);
                        break;
                    }
                }
            }
        }

        //Insert into the arrays
        int[] filledBlockMappingIds = new int[1];
        int[] firstFilledBlockMappingId = {-1};
        int[] lastFilledBlockMappingId = {-1};
        sentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            while (this.blockId2stateEntry.size() < entry.id) {
                int missingId = this.blockId2stateEntry.size();
                if (firstFilledBlockMappingId[0] == -1) {
                    firstFilledBlockMappingId[0] = missingId;
                }
                lastFilledBlockMappingId[0] = missingId;
                filledBlockMappingIds[0]++;
                this.blockId2stateEntry.add(new StateEntry(missingId, Blocks.AIR.defaultBlockState()));
            }
            if (this.blockId2stateEntry.size() == entry.id) {
                this.blockId2stateEntry.add(entry);
            } else {
                Logger.warn("Ignoring duplicate Voxy block mapping id " + entry.id
                        + " after sparse mapping repair");
            }
        });

        if (filledBlockMappingIds[0] > 0) {
            Logger.warn("Filled " + filledBlockMappingIds[0] + " missing Voxy block mapping ids "
                    + firstFilledBlockMappingId[0] + ".." + lastFilledBlockMappingId[0]
                    + " while loading sparse/corrupt mapping storage");
        }

        int[] filledBiomeMappingIds = new int[1];
        int[] firstFilledBiomeMappingId = {-1};
        int[] lastFilledBiomeMappingId = {-1};
        bentries.stream().sorted(Comparator.comparing(a->a.id)).forEach(entry -> {
            while (this.biomeId2biomeEntry.size() < entry.id) {
                int missingId = this.biomeId2biomeEntry.size();
                if (firstFilledBiomeMappingId[0] == -1) {
                    firstFilledBiomeMappingId[0] = missingId;
                }
                lastFilledBiomeMappingId[0] = missingId;
                filledBiomeMappingIds[0]++;
                this.biomeId2biomeEntry.add(new BiomeEntry(missingId, "minecraft:plains"));
            }
            if (this.biomeId2biomeEntry.size() == entry.id) {
                this.biomeId2biomeEntry.add(entry);
            } else {
                Logger.warn("Ignoring duplicate Voxy biome mapping id " + entry.id
                        + " after sparse mapping repair");
            }
        });
        if (filledBiomeMappingIds[0] > 0) {
            Logger.warn("Filled " + filledBiomeMappingIds[0] + " missing Voxy biome mapping ids "
                    + firstFilledBiomeMappingId[0] + ".." + lastFilledBiomeMappingId[0]
                    + " while loading sparse/corrupt mapping storage");
        }

        if (forceResave[0]) {
            Logger.warn("Forced state resave triggered");
            this.forceResaveStates();
        }
    }

    public void importMappings(Int2ObjectOpenHashMap<byte[]> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        List<StateEntry> sentries = new ArrayList<>();
        List<BiomeEntry> bentries = new ArrayList<>();
        boolean[] forceResave = new boolean[1];

        for (var entry : mappings.int2ObjectEntrySet()) {
            int entryType = entry.getIntKey() >>> 30;
            int id = entry.getIntKey() & ((1 << 30) - 1);
            try {
                if (entryType == BLOCK_STATE_TYPE) {
                    var sentry = StateEntry.deserialize(id, entry.getValue(), forceResave);
                    if (!sentry.state.isAir() || sentry.id == 0) {
                        sentries.add(sentry);
                    }
                } else if (entryType == BIOME_TYPE) {
                    bentries.add(BiomeEntry.deserialize(id, entry.getValue()));
                }
            } catch (RuntimeException exception) {
                Logger.warn("Ignoring invalid imported Voxy server LoD mapping id " + id + ": " + exception.getMessage());
            }
        }

        sentries.stream().sorted(Comparator.comparing(entry -> entry.id)).forEach(this::importStateEntry);
        bentries.stream().sorted(Comparator.comparing(entry -> entry.id)).forEach(this::importBiomeEntry);
    }

    private void importStateEntry(StateEntry entry) {
        this.blockLock.lock();
        boolean added = false;
        try {
            if (entry.id < this.blockId2stateEntry.size()) {
                var existing = this.blockId2stateEntry.get(entry.id);
                if (entry.id > 0 && existing.state.isAir() && !entry.state.isAir()) {
                    this.blockId2stateEntry.set(entry.id, entry);
                    this.block2stateEntry.putIfAbsent(entry.state, entry);
                    added = true;
                }
                return;
            }
            this.fillImportedStateMappingGap(entry.id);
            this.blockId2stateEntry.add(entry);
            this.block2stateEntry.putIfAbsent(entry.state, entry);
            added = true;
        } finally {
            this.blockLock.unlock();
        }
        if (added && this.newStateCallback != null) {
            this.newStateCallback.accept(entry);
        }
    }

    private void importBiomeEntry(BiomeEntry entry) {
        this.biomeLock.lock();
        boolean added = false;
        try {
            if (entry.id < this.biomeId2biomeEntry.size()) {
                var existing = this.biomeId2biomeEntry.get(entry.id);
                if (entry.id > 0 && "minecraft:plains".equals(existing.biome) && !existing.biome.equals(entry.biome)) {
                    this.biomeId2biomeEntry.set(entry.id, entry);
                    this.biome2biomeEntry.putIfAbsent(entry.biome, entry);
                    added = true;
                }
                return;
            }
            this.fillImportedBiomeMappingGap(entry.id);
            this.biomeId2biomeEntry.add(entry);
            this.biome2biomeEntry.putIfAbsent(entry.biome, entry);
            added = true;
        } finally {
            this.biomeLock.unlock();
        }
        if (added && this.newBiomeCallback != null) {
            this.newBiomeCallback.accept(entry);
        }
    }

    private void fillImportedStateMappingGap(int targetId) {
        int firstMissing = this.blockId2stateEntry.size();
        while (this.blockId2stateEntry.size() < targetId) {
            int missingId = this.blockId2stateEntry.size();
            this.blockId2stateEntry.add(new StateEntry(missingId, Blocks.AIR.defaultBlockState()));
        }
        if (this.blockId2stateEntry.size() != firstMissing) {
            Logger.warn("Filled imported Voxy server LoD block mapping gap "
                    + firstMissing + ".." + (this.blockId2stateEntry.size() - 1)
                    + " before imported id " + targetId);
        }
    }

    private void fillImportedBiomeMappingGap(int targetId) {
        int firstMissing = this.biomeId2biomeEntry.size();
        while (this.biomeId2biomeEntry.size() < targetId) {
            int missingId = this.biomeId2biomeEntry.size();
            this.biomeId2biomeEntry.add(new BiomeEntry(missingId, "minecraft:plains"));
        }
        if (this.biomeId2biomeEntry.size() != firstMissing) {
            Logger.warn("Filled imported Voxy server LoD biome mapping gap "
                    + firstMissing + ".." + (this.biomeId2biomeEntry.size() - 1)
                    + " before imported id " + targetId);
        }
    }

    public void ensureImportedMappingCoverage(int maxBlockId, int maxBiomeId) {
        if (maxBlockId >= 0) {
            this.blockLock.lock();
            try {
                if (maxBlockId >= this.blockId2stateEntry.size()) {
                    this.fillImportedStateMappingGap(maxBlockId + 1);
                }
            } finally {
                this.blockLock.unlock();
            }
        }

        if (maxBiomeId >= 0) {
            this.biomeLock.lock();
            try {
                if (maxBiomeId >= this.biomeId2biomeEntry.size()) {
                    this.fillImportedBiomeMappingGap(maxBiomeId + 1);
                }
            } finally {
                this.biomeLock.unlock();
            }
        }
    }

    public final int getBlockStateCount() {
        return this.blockId2stateEntry.size();
    }

    private StateEntry registerNewBlockState(BlockState state) {
        this.blockLock.lock();
        var entry = this.block2stateEntry.get(state);
        if (entry != null) {
            this.blockLock.unlock();
            return entry;
        }

        entry = new StateEntry(this.blockId2stateEntry.size(), state);
        this.blockId2stateEntry.add(entry);
        this.block2stateEntry.put(state, entry);
        this.blockLock.unlock();

        byte[] serialized = entry.serialize();
        ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
        buffer.put(serialized);
        buffer.rewind();
        this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
        MemoryUtil.memFree(buffer);
        //this.storage.flush();

        if (this.newStateCallback!=null)this.newStateCallback.accept(entry);
        return entry;
    }

    private BiomeEntry registerNewBiome(String biome) {
        this.biomeLock.lock();
        var entry = this.biome2biomeEntry.get(biome);
        if (entry != null) {
            this.biomeLock.unlock();
            return entry;
        }
        entry = new BiomeEntry(this.biomeId2biomeEntry.size(), biome);
        this.biomeId2biomeEntry.add(entry);
        this.biome2biomeEntry.put(biome, entry);
        this.biomeLock.unlock();

        byte[] serialized = entry.serialize();
        ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
        buffer.put(serialized);
        buffer.rewind();
        this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
        MemoryUtil.memFree(buffer);
        //this.storage.flush();

        if (this.newBiomeCallback!=null)this.newBiomeCallback.accept(entry);
        return entry;
    }


    //TODO:FIXME: IS VERY SLOW NEED TO MAKE IT LOCK FREE, or at minimum use a concurrent map
    public long getBaseId(byte light, BlockState state, Holder<Biome> biome) {
        if (state.isAir()) return Byte.toUnsignedLong(light) <<56;//Special case and fast return for air, dont care about the biome
        return composeMappingId(light, this.getIdForBlockState(state), this.getIdForBiome(biome));
    }

    public BlockState getBlockStateFromBlockId(int blockId) {
        return this.blockId2stateEntry.get(blockId).state;
    }

    public int getIdForBlockState(BlockState state) {
        if (state.isAir()) {
            return 0;
        }
        var mapping = this.block2stateEntry.get(state);
        if (mapping == null) {
            mapping = this.registerNewBlockState(state);
        }
        return mapping.id;
    }

    public int getBlockStateOpacity(long mappingId) {
        return this.getBlockStateOpacity(getBlockId(mappingId));
    }

    public int getBlockStateOpacity(int blockId) {
        return this.blockId2stateEntry.get(blockId).opacity;
    }

    public int getIdForBiome(Holder<Biome> biome) {
        String biomeId = biome.unwrapKey().get().location().toString();
        var entry = this.biome2biomeEntry.get(biomeId);
        if (entry == null) {
            entry = this.registerNewBiome(biomeId);
        }
        return entry.id;
    }

    public static long composeMappingId(byte light, int blockId, int biomeId) {
        if (blockId == AIR) {//Dont care about biome for air
            return Byte.toUnsignedLong(light)<<56;
        }
        return (Byte.toUnsignedLong(light)<<56)|(Integer.toUnsignedLong(biomeId) << 47)|(Integer.toUnsignedLong(blockId)<<27);
    }

    //TODO: fixme: synchronize access to this.blockId2stateEntry
    public StateEntry[] getStateEntries() {
        this.blockLock.lock();
        var set = new ArrayList<>(this.blockId2stateEntry);
        StateEntry[] out = new StateEntry[set.size()];
        int i = 0;
        for (var entry : set) {
            if (entry.id != i++) {
                throw new IllegalStateException();
            }
            out[i-1] = entry;
        }
        this.blockLock.unlock();
        return out;
    }

    //TODO: fixme: synchronize access to this.biomeId2biomeEntry
    public BiomeEntry[] getBiomeEntries() {
        this.biomeLock.lock();
        var set = new ArrayList<>(this.biomeId2biomeEntry);
        BiomeEntry[] out = new BiomeEntry[set.size()];
        int i = 0;
        for (var entry : set) {
            if (entry.id != i++) {
                throw new IllegalStateException();
            }
            out[i-1] = entry;
        }
        this.biomeLock.unlock();
        return out;
    }

    public void forceResaveStates() {
        var blocks = new ArrayList<>(this.block2stateEntry.values());
        var biomes = new ArrayList<>(this.biomeId2biomeEntry);


        for (var entry : blocks) {
            if (entry.state.isAir() && entry.id == 0) {
                continue;
            }
            if (this.blockId2stateEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("State Id NOT THE SAME, very critically bad. arr:" + this.blockId2stateEntry.indexOf(entry) + " entry: " + entry.id);
            }
            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BLOCK_STATE_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        for (var entry : biomes) {
            if (this.biomeId2biomeEntry.indexOf(entry) != entry.id) {
                throw new IllegalStateException("Biome Id NOT THE SAME, very critically bad");
            }

            byte[] serialized = entry.serialize();
            ByteBuffer buffer = MemoryUtil.memAlloc(serialized.length);
            buffer.put(serialized);
            buffer.rewind();
            this.storage.putIdMapping(entry.id | (BIOME_TYPE<<30), buffer);
            MemoryUtil.memFree(buffer);
        }

        this.storage.flush();
    }

    public void close() {

    }


    public static final class StateEntry {
        public final int id;
        public final BlockState state;
        public final int opacity;
        public StateEntry(int id, BlockState state) {
            this.id = id;
            this.state = state;
            //Override opacity of leaves to be solid
            if (state.getBlock() instanceof LeavesBlock) {
                this.opacity = 15;
            } else {
                this.opacity = state.getLightBlock(new BlockGetter() {

                    @Override
                    public int getHeight() {
                        return 0;
                    }

                    @Override
                    public int getMinBuildHeight() {
                        return 0;
                    }

                    @Override
                    public BlockEntity getBlockEntity(BlockPos arg0) {
                        return null;
                    }

                    @Override
                    public BlockState getBlockState(BlockPos blockPos) {
                        return state;
                    }

                    @Override
                    public FluidState getFluidState(BlockPos blockPos) {
                        return state.getFluidState();
                    }
                    
                }, BlockPos.ZERO);
            }
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.put("block_state", BlockState.CODEC.encodeStart(NbtOps.INSTANCE, this.state).result().get());
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static StateEntry deserialize(int id, byte[] data, boolean[] forceResave) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                if (compound.getInt("id") != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                var bsc = compound.getCompound("block_state");
                var state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                if (state.isError()) {
                    Logger.info("Could not decode blockstate, attempting fixes, error: "+ state.error().get().message());
                    bsc = (CompoundTag) DataFixers.getDataFixer().update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE,bsc),0, SharedConstants.getCurrentVersion().getDataVersion().getVersion()).getValue();
                    state = BlockState.CODEC.parse(NbtOps.INSTANCE, bsc);
                    if (state.isError()) {
                        Logger.error("Could not decode blockstate setting to air. id:" + id + " error: " + state.error().get().message());
                        return new StateEntry(id, Blocks.AIR.defaultBlockState());
                    } else {
                        Logger.info("Fixed blockstate to: " + state.getOrThrow());
                        forceResave[0] |= true;
                        return new StateEntry(id, state.getOrThrow());
                    }
                } else {
                    return new StateEntry(id, state.getOrThrow());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static final class BiomeEntry {
        public final int id;
        public final String biome;

        public BiomeEntry(int id, String biome) {
            this.id = id;
            this.biome = biome;
        }

        public byte[] serialize() {
            try {
                var serialized = new CompoundTag();
                serialized.putInt("id", this.id);
                serialized.putString("biome_id", this.biome);
                var out = new ByteArrayOutputStream();
                NbtIo.writeCompressed(serialized, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public static BiomeEntry deserialize(int id, byte[] data) {
            try {
                var compound = NbtIo.readCompressed(new ByteArrayInputStream(data), NbtAccounter.unlimitedHeap());
                if (compound.getInt("id") != id) {
                    throw new IllegalStateException("Encoded id != expected id");
                }
                String biome = compound.getString("biome_id");
                return new BiomeEntry(id, biome);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
