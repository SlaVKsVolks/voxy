package me.cortex.voxy.common.world;

import me.cortex.voxy.common.debug.RenderCorrectnessDiagnostics;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.commonImpl.VoxyCommon;

import static me.cortex.voxy.common.world.WorldEngine.*;

public class WorldUpdater {
    //Executes an update to the world and automatically updates all the parent mip layers up to level 4 (e.g. where 1 chunk section is 1 block big)

    //NOTE: THIS RUNS ON THE THREAD IT WAS EXECUTED ON, when this method exits, the calling method may assume that VoxelizedSection is no longer needed
    public static void insertUpdate(WorldEngine into, VoxelizedSection section) {//TODO: add a bitset of levels to update and if it should force update

        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (section.x+512)>>10;
            section.setPosition(section.x-(sector<<10), section.y+16+(256-32-sector*30), section.z);//Note sector size mult is 30 because the top chunk is replicated (and so is bottom chunk)
        }

        if (!into.isLive) throw new IllegalStateException("World is not live");
        boolean shouldCheckEmptiness = false;
        WorldSection previousSection = null;
        for (int lvl = 0; lvl <= MAX_LOD_LAYER; lvl++) {
            var worldSection = into.acquire(lvl, section.x >> (lvl + 1), section.y >> (lvl + 1), section.z >> (lvl + 1));
            if (!canPublish(section, worldSection, lvl)) {
                worldSection.release();
                if (previousSection != null) {
                    previousSection.release();
                }
                return;
            }

            int emptinessStateChange = 0;
            //Propagate the child existence state of the previous iteration to this section
            if (lvl != 0 && shouldCheckEmptiness) {
                emptinessStateChange = worldSection.updateEmptyChildState(previousSection);
                //We kept the previous section acquired, so we need to release it
                previousSection.release();
                previousSection = null;
            }

            long status = insertSectionLvlIntoWorld(section, worldSection);
            boolean didStateChange = (status&1)==1;
            int airCount = (int) ((status>>1)&0x1FFF);
            // Publication metadata is currently section-wide. Parent LoD sections aggregate many
            // children, so stamping a parent as REAL_CHUNK because one child is real incorrectly
            // rejects preview data for sibling cells and leaves large holes. Keep strict
            // confidence/epoch metadata at level 0 until parent metadata becomes per-cell.
            boolean metadataChanged = lvl == 0 && hasPublicationMetadataChanged(section, worldSection);
            if (didStateChange || metadataChanged) {
                worldSection.setPublicationMetadata(section);
            }


            if (lvl == 0) {
                int nonAirCountDelta = section.lvl0NonAirCount-(4096-airCount);
                if (nonAirCountDelta != 0) {
                    worldSection.addNonEmptyBlockCount(nonAirCountDelta);
                    emptinessStateChange = worldSection.updateLvl0State() ? 2 : 0;
                }
            }

            if (didStateChange||metadataChanged||(emptinessStateChange!=0)) {
                //TODO: somehow foward the neighbors that are facing the updated area, this allows forwarding to the dirty consumer
                // which can decide wether to dispatch mesh rebuilds to the surounding sections
                //Bitmask of neighboring sections
                //Note, this may be zero (this is more likely to occure at higher lod levels) if it doesnt face any neighbors
                int neighbors = 0;
                if (didStateChange) {
                    neighbors |= ((section.y^(section.y-1))>>(lvl+1))==0?0:1<<0;//Down
                    neighbors |= ((section.y^(section.y+1))>>(lvl+1))==0?0:1<<1;//Up
                    neighbors |= ((section.x^(section.x-1))>>(lvl+1))==0?0:1<<2;//-x
                    neighbors |= ((section.x^(section.x+1))>>(lvl+1))==0?0:1<<3;//+x
                    neighbors |= ((section.z^(section.z-1))>>(lvl+1))==0?0:1<<4;//-z
                    neighbors |= ((section.z^(section.z+1))>>(lvl+1))==0?0:1<<5;//+z
                }

                into.markDirty(worldSection, (didStateChange?UPDATE_TYPE_BLOCK_BIT:0)|(emptinessStateChange!=0?UPDATE_TYPE_CHILD_EXISTENCE_BIT:0), neighbors);
            }

            //Need to release the section after using it
            if (didStateChange||metadataChanged||(emptinessStateChange==2)) {
                if (emptinessStateChange==2) {
                    //Major state emptiness change, bubble up
                    shouldCheckEmptiness = true;
                    //Dont release the section, it will be released on the next loop
                    previousSection = worldSection;
                } else {
                    //Propagate up without state change
                    shouldCheckEmptiness = false;
                    previousSection = null;
                    worldSection.release();
                }
            } else {
                //If nothing changed just need to release, dont need to update parent mips
                worldSection.release();
                break;
            }
        }

        if (previousSection != null) {
            previousSection.release();
        }
    }

    private static boolean canPublish(VoxelizedSection incoming, WorldSection current, int targetLodLevel) {
        long pos = current.key;
        VoxelizedSection.SourceKind incomingSource = incoming.sourceKind == null
                ? VoxelizedSection.SourceKind.UNKNOWN
                : incoming.sourceKind;
        VoxelizedSection.Confidence incomingConfidence = incoming.confidence == null
                ? VoxelizedSection.Confidence.UNKNOWN
                : incoming.confidence;
        VoxelizedSection.LightSourceKind incomingLight = incoming.lightSourceKind == null
                ? VoxelizedSection.LightSourceKind.UNKNOWN
                : incoming.lightSourceKind;

        if (incomingLight == VoxelizedSection.LightSourceKind.MISSING_SKY_LIGHT) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "missing_light_ready_rejected",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    "missing_light_cannot_publish_visible_lod"
            );
            return false;
        }

        if (incomingLight == VoxelizedSection.LightSourceKind.SYNTHETIC_SURFACE_PREVIEW
                && incomingSource != VoxelizedSection.SourceKind.SURFACE_PREVIEW
                && incomingSource != VoxelizedSection.SourceKind.SYNTHETIC_PREVIEW
                && incomingSource != VoxelizedSection.SourceKind.ZERO_CLEAR) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "synthetic_light_ready_rejected",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    "synthetic_light_requires_provisional_source"
            );
            return false;
        }

        if (targetLodLevel > 0) {
            return true;
        }

        boolean incomingPreview = incoming.isProvisional();
        if (incomingPreview && current.hasTrustedRealData()) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "provisional_over_real_rejected",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    "provisional_cannot_overwrite_trusted_real"
            );
            return false;
        }

        boolean hasCommittedEpoch = current.getDataEpoch() > 0L;
        boolean oldEpoch = hasCommittedEpoch && incoming.dataEpoch > 0L && incoming.dataEpoch < current.getDataEpoch();
        if (oldEpoch) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "old_epoch_rejected",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    "stale_async_publish_rejected"
            );
            return false;
        }

        boolean lowerConfidence = incomingConfidence.rank < current.getConfidence().rank;
        boolean lowerSource = VoxelizedSection.sourceRank(incomingSource) < VoxelizedSection.sourceRank(current.getSourceKind());
        boolean explicitClear = incomingSource == VoxelizedSection.SourceKind.ZERO_CLEAR;
        boolean validationPreviewClear = explicitClear && incomingConfidence.rank < VoxelizedSection.Confidence.HIGH.rank;
        if (validationPreviewClear
                && current.getSourceKind() != VoxelizedSection.SourceKind.UNKNOWN
                && current.getSourceKind() != VoxelizedSection.SourceKind.ZERO_CLEAR
                && current.getNonEmptyBlockCount() > 0) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "preview_zero_clear_rejected",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    "preview_zero_clear_cannot_erase_non_empty_lod"
            );
            return false;
        }
        if ((lowerConfidence || lowerSource) && !explicitClear && current.getSourceKind() != VoxelizedSection.SourceKind.UNKNOWN) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "lower_confidence_rejected",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    lowerSource ? "lower_source_rank_rejected" : "lower_confidence_rejected"
            );
            return false;
        }
        if ((lowerConfidence || lowerSource) && explicitClear) {
            RenderCorrectnessDiagnostics.publicationGuard(
                    "zero_clear_explicit_invalidation",
                    pos,
                    current.getDataEpoch(),
                    incoming.dataEpoch,
                    current.getSourceKind().name(),
                    incomingSource.name(),
                    current.getConfidence().name(),
                    incomingConfidence.name(),
                    "zero_clear_explicit_invalidation"
            );
        }

        return true;
    }

    private static boolean hasPublicationMetadataChanged(VoxelizedSection incoming, WorldSection current) {
        VoxelizedSection.SourceKind incomingSource = incoming.sourceKind == null
                ? VoxelizedSection.SourceKind.UNKNOWN
                : incoming.sourceKind;
        VoxelizedSection.Confidence incomingConfidence = incoming.confidence == null
                ? VoxelizedSection.Confidence.UNKNOWN
                : incoming.confidence;
        VoxelizedSection.LightSourceKind incomingLight = incoming.lightSourceKind == null
                ? VoxelizedSection.LightSourceKind.UNKNOWN
                : incoming.lightSourceKind;
        return current.getDataEpoch() != incoming.dataEpoch
                || current.getSourceKind() != incomingSource
                || current.getConfidence() != incomingConfidence
                || current.getLightSourceKind() != incomingLight;
    }


    private static long insertSectionLvlIntoWorld(VoxelizedSection section, WorldSection worldSection) {
        final long[] vdat = section.section;
        final int lvl = worldSection.lvl;

        final int msk = (1<<(lvl+1))-1;
        final int bx = (section.x&msk)<<(4-lvl);
        final int by = (section.y&msk)<<(4-lvl);
        final int bz = (section.z&msk)<<(4-lvl);

        int airCount = 0;
        boolean didStateChange = false;


        //TODO: remove the nonAirCountDelta stuff if level != 0

        {//Do a bunch of funny math
            var secD = worldSection.data;
            int baseSec = bx | (bz << 5) | (by << 10);
            if (lvl == 0) {
                final int secMsk = 0b1100|(0xf << 5) | (0xf << 10);
                final int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;

                //TODO rotate the loop parralelization
                // i.e. instead of doing 4 consecutive blocks, which would all be in the same cache line
                // do 4 seperate rows so they are in different cache lines, should allow
                // more instruction pipelining (in theory)
                for (int i = 0; i <= 0xFFF; i+=4) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;

                    long oldId0 = secD[cSecIdx+0]; secD[cSecIdx+0] = vdat[i+0];
                    long oldId1 = secD[cSecIdx+1]; secD[cSecIdx+1] = vdat[i+1];
                    long oldId2 = secD[cSecIdx+2]; secD[cSecIdx+2] = vdat[i+2];
                    long oldId3 = secD[cSecIdx+3]; secD[cSecIdx+3] = vdat[i+3];

                    airCount += Mapper.isAir(oldId0)?1:0; didStateChange |= vdat[i+0] != oldId0;
                    airCount += Mapper.isAir(oldId1)?1:0; didStateChange |= vdat[i+1] != oldId1;
                    airCount += Mapper.isAir(oldId2)?1:0; didStateChange |= vdat[i+2] != oldId2;
                    airCount += Mapper.isAir(oldId3)?1:0; didStateChange |= vdat[i+3] != oldId3;
                }
            } else {
                int baseVIdx = VoxelizedSection.getBaseIndexForLevel(lvl);

                int secMsk = 0xF >> lvl;
                secMsk |= (secMsk << 5) | (secMsk << 10);
                int iSecMsk1 = (~secMsk) + 1;

                int secIdx = 0;
                //TODO: manually unroll and do e.g. 4 iterations per loop
                for (int i = baseVIdx; i <= (0xFFF >> (lvl * 3)) + baseVIdx; i++) {
                    int cSecIdx = secIdx + baseSec;
                    secIdx = (secIdx + iSecMsk1) & secMsk;
                    long newId = vdat[i];
                    long oldId = secD[cSecIdx];
                    didStateChange |= newId != oldId;
                    secD[cSecIdx] = newId;
                }
            }
        }

        long status = 0;
        status |= didStateChange?1:0;
        status |= Integer.toUnsignedLong(airCount)<<1;//VERY VERY VERY IMPORTANT NOTE: IS 13 BITS BIG NOT 12 BITS (since it can be 4096 which is 6 bits large)
        return status;
    }
}
